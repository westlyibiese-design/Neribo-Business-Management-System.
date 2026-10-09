package com.westly.nbms.features.bar

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
 * The raw `cms_content/bar_menu` document. `data` is read as "anything" so a missing field, a field that is not a list,
 * or odd entries never stop the menu from loading (see `parseDrinksDocument`). Public with defaults so Firestore can build it.
 */
class RawDrinksDocument(val data: Any? = null)

/** The database calls the drinks menu needs. [FirestoreDrinksStore] is the real one; the unit tests use a fake. */
interface DrinksStore {
    /** The live menu. A missing document, a missing `data` field or a non-list `data` is `Success(emptyList())`; a read failure is `Error`. */
    fun observe(): Flow<Resource<List<DrinkItem>>>

    /**
     * ONE transaction: re-read `cms_content/bar_menu`, apply [change] to the LIVE array, write `{data, updatedAt}` (merge).
     * Throws a [DrinksMenuException] (clear sentence) when the change cannot be applied.
     */
    suspend fun save(change: DrinkChange)
}

class FirestoreDrinksStore(
    private val firestore: BusinessFirestore
) : DrinksStore {

    override fun observe(): Flow<Resource<List<DrinkItem>>> =
        firestore.observeDoc(BAR_MENU_COLLECTION, BAR_MENU_DOC_ID, RawDrinksDocument::class.java).map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                is Resource.Success -> Resource.Success(parseDrinksDocument(resource.data?.data))
            }
        }

    override suspend fun save(change: DrinkChange) {
        try {
            firestore.runTransaction { tx, fs ->
                val ref = fs.doc(BAR_MENU_COLLECTION, BAR_MENU_DOC_ID)
                val snap = tx.get(ref)
                // The LIVE array, read inside the transaction (not what is on the screen).
                val live: List<Any?> = if (snap.exists()) (snap.get("data") as? List<*>) ?: emptyList<Any?>() else emptyList<Any?>()
                val next = applyDrinkChange(live, change)
                tx.set(ref, buildDrinksPayload(next).resolveBarServerTime(), SetOptions.merge())
                Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.drinksCause() ?: DrinksMenuException(e.message?.takeIf { it.isNotBlank() } ?: MSG_DRINKS_SAVE_FAILED)
        }
    }
}

private fun Map<String, Any?>.resolveBarServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === BarServerTime) FieldValue.serverTimestamp() else v }

private fun Throwable.drinksCause(): DrinksMenuException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is DrinksMenuException) return current
        current = current.cause
        depth++
    }
    return null
}

/** Reads the drinks menu live and saves one change at a time. Every save is written to the audit log. */
@Singleton
class DrinksMenuRepository(
    private val store: DrinksStore,
    private val audit: AuditLogger
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, audit: AuditLogger) : this(FirestoreDrinksStore(firestore), audit)

    /** Live menu. A missing document, a missing `data` field or a non-list `data` = Resource.Success(emptyList()). A read failure = Resource.Error. */
    fun observe(): Flow<Resource<List<DrinkItem>>> = store.observe()

    /**
     * One transaction that re-reads the live document, applies [change], writes {data, updatedAt}, then audits.
     * Throws with a message for the person on failure; a drink with no name or a negative price writes nothing.
     */
    suspend fun save(change: DrinkChange) {
        when (change) {
            is DrinkChange.Add -> if (!isValidDrink(change.item)) throw DrinksMenuException(MSG_DRINK_CHECK)
            is DrinkChange.Replace -> if (!isValidDrink(change.item)) throw DrinksMenuException(MSG_DRINK_CHECK)
            is DrinkChange.Remove, is DrinkChange.SetAvailable -> Unit
        }
        store.save(change)
        // The audit entry is an extra: a failure there never turns a saved change into an error.
        try {
            audit.log("bar_menu_updated", BAR_MENU_COLLECTION, BAR_MENU_DOC_ID, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
