package com.westly.nbms.features.gym

import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GymContent"

internal const val MSG_CONTENT_SAVE_TIMEOUT = "The save took too long. Check your connection and try again."
internal const val AUDIT_GYM_CONTENT_UPDATED = "gym_content_updated"

// ── what the transaction reads and writes ──

/** What the save transaction can do. The read comes first, then the write (a Firestore rule). */
interface GymContentTx {
    /** Re-reads `cms_content/gym`; null when the document does not exist yet. */
    fun readDocument(): Map<String, Any?>?

    /** Merges [payload] into the document (only the keys inside it are touched). May hold [GymServerTime]. */
    fun mergeWrite(payload: Map<String, Any?>)
}

/** The database calls the gym content needs. [FirestoreGymContentStore] is the real one; the unit tests use a fake. */
interface GymContentStore {
    /** The raw `data` map of `cms_content/gym`. A missing document is `Success(null)`. */
    fun observeData(): Flow<Resource<Map<String, Any?>?>>

    suspend fun <R> inTransaction(block: (GymContentTx) -> R): R
}

/** `{data: {<key>: <value>}, updatedAt: server time}`: the only thing a save writes. Merged, so other keys are never touched. */
internal fun buildSectionMergePayload(section: GymSection, value: Any): Map<String, Any?> =
    mapOf("data" to mapOf(section.key to value), "updatedAt" to GymServerTime)

/** The transaction body: re-read the document, then write only this section's key (plus `updatedAt`). */
internal fun performSectionSave(tx: GymContentTx, section: GymSection, value: Any) {
    tx.readDocument()
    tx.mergeWrite(buildSectionMergePayload(section, value))
}

/** The real store, built by [GymContentRepository] (nothing else needs to inject it). */
class FirestoreGymContentStore(
    private val firestore: BusinessFirestore
) : GymContentStore {

    override fun observeData(): Flow<Resource<Map<String, Any?>?>> =
        firestore.observeDoc(GYM_CMS_COLLECTION, GYM_CMS_DOC_ID, RawGymDocument::class.java).map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                is Resource.Success -> Resource.Success(stringKeyed(resource.data?.data))
            }
        }

    override suspend fun <R> inTransaction(block: (GymContentTx) -> R): R {
        try {
            return firestore.runTransaction { tx, fs ->
                val ref = fs.doc(GYM_CMS_COLLECTION, GYM_CMS_DOC_ID)
                val real = object : GymContentTx {
                    override fun readDocument(): Map<String, Any?>? {
                        val snap = tx.get(ref)
                        return if (snap.exists()) snap.data else null
                    }

                    override fun mergeWrite(payload: Map<String, Any?>) {
                        tx.set(ref, payload.resolveGymServerTime(), SetOptions.merge())
                    }
                }
                block(real)
            }
        } catch (e: Exception) {
            throw e.gymCause() ?: e
        }
    }
}

/** A map whose keys are all text, or null for anything else. */
private fun stringKeyed(raw: Any?): Map<String, Any?>? {
    val map = raw as? Map<*, *> ?: return null
    return map.entries.filter { it.key is String }.associate { (k, v) -> (k as String) to v }
}

/** What [GymContentViewModel] needs from the repository. Lets the unit tests use a fake. */
interface GymContentSource {
    fun observe(): Flow<Resource<GymContent>>

    /** Throws on failure. */
    suspend fun saveSection(section: GymSection, value: Any)
}

/**
 * Reads the gym content live and saves ONE section at a time. A save is a transaction that re-reads `cms_content/gym` and
 * writes only `data.<key>` plus `updatedAt`, so two people saving different sections never overwrite each other.
 */
@Singleton
class GymContentRepository internal constructor(
    private val store: GymContentStore,
    private val audit: AuditLogger
) : GymContentSource {

    @Inject
    constructor(firestore: BusinessFirestore, audit: AuditLogger) : this(FirestoreGymContentStore(firestore), audit)

    /** A missing document is an empty [GymContent], not an error. */
    override fun observe(): Flow<Resource<GymContent>> =
        store.observeData().map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                is Resource.Success -> Resource.Success(GymContent.parse(resource.data))
            }
        }

    /**
     * Saves [value] under `data.<section key>` in one transaction (20 s limit), then writes the audit entry
     * `gym_content_updated`. A failed save throws and writes nothing; a failed audit entry never turns a saved change into an error.
     */
    override suspend fun saveSection(section: GymSection, value: Any) {
        val finished: Boolean = try {
            withTimeoutOrNull(GYM_TRANSACTION_TIMEOUT_MS) {
                store.inTransaction { tx -> performSectionSave(tx, section, value) }
                true
            } ?: false
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (!finished) throw GymException(MSG_CONTENT_SAVE_TIMEOUT)
        try {
            withTimeoutOrNull(GYM_POST_STEP_TIMEOUT_MS) {
                audit.log(AUDIT_GYM_CONTENT_UPDATED, GYM_CMS_COLLECTION, GYM_CMS_DOC_ID, null, mapOf("section" to section.key))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gym content audit step failed", e)
        }
    }
}
