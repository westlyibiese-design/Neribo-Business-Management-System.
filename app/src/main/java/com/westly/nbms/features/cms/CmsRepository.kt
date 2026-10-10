package com.westly.nbms.features.cms

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CmsRepository"

internal const val CMS_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val CMS_POST_STEP_TIMEOUT_MS = 5_000L
internal const val MSG_CMS_SAVE_TIMEOUT = "The save took too long. Check your connection and try again."

/** What is written by every save: only these two top-level fields, so any other field of the document is left alone. */
internal val CMS_MERGE_FIELDS: List<String> = listOf("data", "updatedAt")

/** The document as it was read: [data] is the `data` field exactly as stored (a Map, a List, or null). */
data class CmsRawDoc(val exists: Boolean, val data: Any?)

/** The ONE change a list save makes to the freshly read list. */
sealed interface ListOp<T> {
    data class Add<T>(val item: T) : ListOp<T>
    data class Replace<T>(val item: T) : ListOp<T>
    data class Remove<T>(val id: String) : ListOp<T>
    data class Move<T>(val id: String, val delta: Int) : ListOp<T>
}

/** How one kind of list entry is read, written and identified. */
class ListCodec<T>(val parse: (Any?) -> List<T>, val toMap: (T) -> Map<String, Any?>, val idOf: (T) -> String)

sealed interface MutateResult {
    object Done : MutateResult
    object AlreadyChanged : MutateResult
    object LimitReached : MutateResult
}

/** Thrown for a save that cannot finish; the message is safe to show. */
class CmsException(message: String) : Exception(message)

/** Stands for "the server's time" in a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object CmsServerTime

internal fun Map<String, Any?>.resolveCmsServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === CmsServerTime) FieldValue.serverTimestamp() else v }

/** `{data: <whole map>, updatedAt}`: what an object save writes. The `data` field is REPLACED as a whole. */
internal fun buildObjectSavePayload(data: Map<String, Any?>): Map<String, Any?> =
    mapOf("data" to data, "updatedAt" to CmsServerTime)

/** `{data: [<entries>], updatedAt}`: what a list save writes. */
internal fun buildListSavePayload(entries: List<Map<String, Any?>>): Map<String, Any?> =
    mapOf("data" to entries, "updatedAt" to CmsServerTime)

// ───────────────────────── applying one list operation ─────────────────────────

internal sealed interface ListOpOutcome<out T> {
    /** The new list to write. */
    data class Changed<T>(val list: List<T>) : ListOpOutcome<T>

    /** Nothing to write and nothing wrong (for example moving the first item up). */
    data object Unchanged : ListOpOutcome<Nothing>

    /** The item the change was about is gone (or already there, for an add). */
    data object AlreadyChanged : ListOpOutcome<Nothing>

    data object LimitReached : ListOpOutcome<Nothing>
}

/** Applies [op] to [current] (the freshly read list). Pure; the limit is only checked for an Add. */
internal fun <T> applyListOp(current: List<T>, op: ListOp<T>, idOf: (T) -> String, limit: Int): ListOpOutcome<T> = when (op) {
    is ListOp.Add -> when {
        current.size >= limit -> ListOpOutcome.LimitReached
        current.any { idOf(it) == idOf(op.item) } -> ListOpOutcome.AlreadyChanged
        else -> ListOpOutcome.Changed(current + op.item)
    }
    is ListOp.Replace -> {
        val at = current.indexOfFirst { idOf(it) == idOf(op.item) }
        if (at < 0) ListOpOutcome.AlreadyChanged
        else ListOpOutcome.Changed(current.toMutableList().also { it[at] = op.item })
    }
    is ListOp.Remove -> {
        val at = current.indexOfFirst { idOf(it) == op.id }
        if (at < 0) ListOpOutcome.AlreadyChanged
        else ListOpOutcome.Changed(current.toMutableList().also { it.removeAt(at) })
    }
    is ListOp.Move -> {
        val at = current.indexOfFirst { idOf(it) == op.id }
        val to = at + op.delta
        when {
            at < 0 -> ListOpOutcome.AlreadyChanged
            op.delta == 0 || to < 0 || to > current.lastIndex -> ListOpOutcome.Unchanged
            else -> ListOpOutcome.Changed(current.toMutableList().also { val moved = it.removeAt(at); it.add(to, moved) })
        }
    }
}

// ───────────────────────── the database calls ─────────────────────────

/** What a list-save transaction can do. The read comes first, then the write (a Firestore rule). */
interface CmsTx {
    /** Re-reads the document. */
    fun read(): CmsRawDoc

    /** Writes [payload] with `mergeFields("data","updatedAt")`. May hold [CmsServerTime]. */
    fun write(payload: Map<String, Any?>)
}

/** The Firestore calls the CMS needs. [FirestoreCmsStore] is the real one; the unit tests use a fake. */
interface CmsStore {
    fun observe(docId: String): Flow<Resource<CmsRawDoc>>

    /** Sets [payload] on `cms_content/{docId}` merging only [mergeFields]. Throws on failure. */
    suspend fun setMerged(docId: String, payload: Map<String, Any?>, mergeFields: List<String>)

    suspend fun <R> inTransaction(docId: String, block: (CmsTx) -> R): R
}

/** Public with a default so Firestore can build it. */
class RawCmsDocument(val data: Any? = null)

class FirestoreCmsStore(private val firestore: BusinessFirestore) : CmsStore {

    override fun observe(docId: String): Flow<Resource<CmsRawDoc>> =
        firestore.observeDoc(CMS_COLLECTION, docId, RawCmsDocument::class.java).map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                // A missing document is normal: it means "nothing saved yet".
                is Resource.Success -> Resource.Success(
                    resource.data?.let { CmsRawDoc(exists = true, data = it.data) } ?: CmsRawDoc(exists = false, data = null)
                )
            }
        }

    override suspend fun setMerged(docId: String, payload: Map<String, Any?>, mergeFields: List<String>) {
        firestore.doc(CMS_COLLECTION, docId)
            .set(payload.resolveCmsServerTime(), SetOptions.mergeFields(mergeFields))
            .await()
    }

    override suspend fun <R> inTransaction(docId: String, block: (CmsTx) -> R): R =
        firestore.runTransaction { tx, fs ->
            val ref = fs.doc(CMS_COLLECTION, docId)
            val real = object : CmsTx {
                override fun read(): CmsRawDoc {
                    val snap = tx.get(ref)
                    return if (snap.exists()) CmsRawDoc(true, snap.get("data")) else CmsRawDoc(false, null)
                }

                override fun write(payload: Map<String, Any?>) {
                    tx.set(ref, payload.resolveCmsServerTime(), SetOptions.mergeFields(CMS_MERGE_FIELDS))
                }
            }
            block(real)
        }
}

// ───────────────────────── the repository ─────────────────────────

/** What the CMS ViewModels need from the repository. Lets the unit tests use a fake. */
interface CmsSource {
    fun observeDoc(docId: String): Flow<Resource<CmsRawDoc>>
    suspend fun saveObject(docId: String, data: Map<String, Any?>)
    suspend fun <T> mutateList(
        docId: String,
        op: ListOp<T>,
        codec: ListCodec<T>,
        limit: Int,
        auditAction: String,
        normalize: (List<T>) -> List<T> = { it }
    ): MutateResult
}

/**
 * Reads and writes the website content documents `businesses/{bid}/cms_content/{docId}`.
 * Objects (hero, about, contact, banners) are saved whole with [saveObject]; lists (testimonials, FAQs, facilities, gallery)
 * are changed one operation at a time with [mutateList], inside a transaction that re-reads the list first, so two people
 * editing different items never overwrite each other. This class never shows toasts; the page ViewModels do.
 */
@Singleton
class CmsRepository internal constructor(
    private val store: CmsStore,
    private val audit: AuditLogger
) : CmsSource {

    @Inject
    constructor(firestore: BusinessFirestore, audit: AuditLogger) : this(FirestoreCmsStore(firestore), audit)

    /** A MISSING document is `Success(CmsRawDoc(exists = false, data = null))`, never an error. */
    override fun observeDoc(docId: String): Flow<Resource<CmsRawDoc>> = store.observe(docId)

    /**
     * Replaces the whole `data` field of the document (so a key removed from [data] really disappears) and sets `updatedAt`,
     * touching no other field; then audits `cms_updated:{docId}`. Throws when the write fails.
     */
    override suspend fun saveObject(docId: String, data: Map<String, Any?>) {
        val finished: Boolean = try {
            withTimeoutOrNull(CMS_TRANSACTION_TIMEOUT_MS) {
                store.setMerged(docId, buildObjectSavePayload(data), CMS_MERGE_FIELDS)
                true
            } ?: false
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (!finished) throw CmsException(MSG_CMS_SAVE_TIMEOUT)
        auditBestEffort("cms_updated:$docId", docId)
    }

    /**
     * One transaction (20 s limit): re-read the document, apply the ONE [op] to the freshly read list, enforce [limit] (only an
     * Add can go over it), apply [normalize], write `{data: newList, updatedAt}`. A Replace, Remove or Move of an id that is no
     * longer there is [MutateResult.AlreadyChanged] and nothing is written. Moving the first item up (or the last down) is a
     * quiet no-op (nothing written, nothing audited). Otherwise audits [auditAction]. Throws when the transaction fails.
     */
    override suspend fun <T> mutateList(
        docId: String,
        op: ListOp<T>,
        codec: ListCodec<T>,
        limit: Int,
        auditAction: String,
        normalize: (List<T>) -> List<T>
    ): MutateResult {
        var wrote = false
        val outcome: MutateResult? = try {
            withTimeoutOrNull(CMS_TRANSACTION_TIMEOUT_MS) {
                store.inTransaction(docId) { tx ->
                    wrote = false // the block can run again when Firestore retries
                    val current = codec.parse(tx.read().data)
                    when (val applied = applyListOp(current, op, codec.idOf, limit)) {
                        is ListOpOutcome.Changed -> {
                            tx.write(buildListSavePayload(normalize(applied.list).map(codec.toMap)))
                            wrote = true
                            MutateResult.Done
                        }
                        ListOpOutcome.Unchanged -> MutateResult.Done
                        ListOpOutcome.AlreadyChanged -> MutateResult.AlreadyChanged
                        ListOpOutcome.LimitReached -> MutateResult.LimitReached
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        val result = outcome ?: throw CmsException(MSG_CMS_SAVE_TIMEOUT)
        if (result == MutateResult.Done && wrote) auditBestEffort(auditAction, docId)
        return result
    }

    private suspend fun auditBestEffort(action: String, docId: String) {
        try {
            withTimeoutOrNull(CMS_POST_STEP_TIMEOUT_MS) { audit.log(action, CMS_COLLECTION, docId, null, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "CMS audit step failed", e)
        }
    }
}
