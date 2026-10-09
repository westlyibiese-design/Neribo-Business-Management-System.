package com.westly.nbms.features.restaurant

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The raw `cms_content/restaurant_menu` document. `data` is read as "anything" so a missing field, a field that is not
 * a list, or odd entries never stop the menu from loading (see `parseMenuDocument`). Public with defaults so Firestore can build it.
 */
class RawMenuDocument(val data: Any? = null)

/** The database calls the menu needs. [FirestoreMenuStore] is the real one; the unit tests use a fake. */
interface MenuStore {
    /** The live menu. A missing document, a missing `data` field or a non-list `data` is `Success(emptyList())`; a read failure is `Error`. */
    fun observe(): Flow<Resource<List<MenuItem>>>

    /**
     * ONE transaction: re-read `cms_content/restaurant_menu`, apply [change] to the LIVE array, write `{data, updatedAt}` (merge).
     * Throws a [MenuException] (clear sentence) when the change cannot be applied.
     */
    suspend fun save(change: MenuChange)
}

@Singleton
class FirestoreMenuStore @Inject constructor(
    private val firestore: BusinessFirestore
) : MenuStore {

    override fun observe(): Flow<Resource<List<MenuItem>>> =
        firestore.observeDoc(MENU_COLLECTION, MENU_DOC_ID, RawMenuDocument::class.java).map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                is Resource.Success -> Resource.Success(parseMenuDocument(resource.data?.data))
            }
        }

    override suspend fun save(change: MenuChange) {
        try {
            firestore.runTransaction { tx, fs ->
                val ref = fs.doc(MENU_COLLECTION, MENU_DOC_ID)
                val snap = tx.get(ref)
                // The LIVE array, read inside the transaction (not what is on the screen).
                val live: List<Any?> = if (snap.exists()) (snap.get("data") as? List<*>) ?: emptyList<Any?>() else emptyList<Any?>()
                val next = applyMenuChange(live, change)
                tx.set(ref, buildMenuPayload(next).resolveServerTime(), SetOptions.merge())
                Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.menuCause() ?: MenuException(e.message?.takeIf { it.isNotBlank() } ?: MSG_MENU_SAVE_FAILED)
        }
    }
}

private fun Map<String, Any?>.resolveServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === MenuServerTime) FieldValue.serverTimestamp() else v }

private fun Throwable.menuCause(): MenuException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is MenuException) return current
        current = current.cause
        depth++
    }
    return null
}

/** Reads the restaurant menu live and saves one change at a time. Every save is written to the audit log. */
@Singleton
class MenuRepository @Inject constructor(
    private val store: MenuStore,
    private val audit: AuditLogger
) {

    /** Live menu. A missing document, a missing `data` field or a non-list `data` = Resource.Success(emptyList()). A read failure = Resource.Error. */
    fun observe(): Flow<Resource<List<MenuItem>>> = store.observe()

    /**
     * One transaction that re-reads the live document, applies [change], writes {data, updatedAt}, then audits.
     * Throws with a message for the person on failure; an item with no name or a negative price writes nothing.
     */
    suspend fun save(change: MenuChange) {
        when (change) {
            is MenuChange.Add -> if (!isValidMenuItem(change.item)) throw MenuException(MSG_MENU_CHECK_ITEM)
            is MenuChange.Replace -> if (!isValidMenuItem(change.item)) throw MenuException(MSG_MENU_CHECK_ITEM)
            is MenuChange.Remove, is MenuChange.SetAvailable -> Unit
        }
        store.save(change)
        // The audit entry is an extra: a failure there never turns a saved change into an error.
        try {
            audit.log("restaurant_menu_updated", MENU_COLLECTION, MENU_DOC_ID, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
