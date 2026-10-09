package com.westly.nbms.features.bar

import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.inventory.InventoryItem
import com.westly.nbms.features.inventory.RestockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The database calls Bar Inventory needs. [FirestoreBarInventoryStore] is the real one; the unit tests use a fake. */
interface BarInventoryStore {
    /** The `inventory` documents with `category == "drinks"`, live (deleted ones included; the repository leaves them out). */
    fun observe(): Flow<Resource<List<InventoryItem>>>

    /** Creates `inventory/{new}` from [payload] in ONE transaction and returns the new id. The payload may hold [BarStockServerTime]. */
    suspend fun add(payload: Map<String, Any?>): String

    /**
     * ONE transaction: re-read the live item, add [amount] to the LIVE quantity, write `{quantity, updatedAt}`.
     * A missing or deleted item stops with a [BarStockException].
     */
    suspend fun restock(itemId: String, amount: Int): RestockResult
}

class FirestoreBarInventoryStore(
    private val firestore: BusinessFirestore
) : BarInventoryStore {

    override fun observe(): Flow<Resource<List<InventoryItem>>> =
        firestore.observeList(BAR_STOCK_PATH, InventoryItem::class.java) { it.whereEqualTo("category", BAR_STOCK_CATEGORY) }

    override suspend fun add(payload: Map<String, Any?>): String {
        val itemId = firestore.collection(BAR_STOCK_PATH).document().id
        try {
            firestore.runTransaction { tx, fs ->
                tx.set(fs.doc(BAR_STOCK_PATH, itemId), payload.resolveBarStockTime())
                Unit
            }
        } catch (e: Exception) {
            throw e.barStockCause() ?: e
        }
        return itemId
    }

    override suspend fun restock(itemId: String, amount: Int): RestockResult {
        try {
            return firestore.runTransaction { tx, fs ->
                val ref = fs.doc(BAR_STOCK_PATH, itemId)
                val snap = tx.get(ref)
                if (!snap.exists() || snap.getBoolean("isDeleted") == true) throw BarStockException(MSG_BAR_STOCK_ITEM_MISSING)
                val before = snap.getLong("quantity")?.toInt() ?: 0
                val after = barRestockedQuantity(before, amount)
                tx.update(ref, buildBarRestockFields(after).resolveBarStockTime())
                RestockResult(before, after)
            }
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.barStockCause() ?: e
        }
    }
}

private fun Map<String, Any?>.resolveBarStockTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === BarStockServerTime) FieldValue.serverTimestamp() else v }

private fun Throwable.barStockCause(): BarStockException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is BarStockException) return current
        current = current.cause
        depth++
    }
    return null
}

/**
 * Bar stock is a filtered view of the shared Phase 18 `inventory` collection (`category == "drinks"`), not a second store.
 * Selling a drink never changes it. Every change is written to the audit log.
 */
@Singleton
class BarInventoryRepository(
    private val store: BarInventoryStore,
    private val audit: AuditLogger
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, audit: AuditLogger) :
        this(FirestoreBarInventoryStore(firestore), audit)

    /** Every drinks item that is not deleted (deleted ones are filtered here, on the client), by name. */
    fun observe(): Flow<Resource<List<InventoryItem>>> =
        store.observe().map { resource ->
            if (resource is Resource.Success) Resource.Success(drinksStockOf(resource.data)) else resource
        }

    /** Adds a drinks item and returns its id. A form that fails the checks throws [BarStockException] and writes nothing. */
    suspend fun add(form: BarStockForm): String {
        val errors = validateBarStockForm(form)
        if (errors.any) throw BarStockException(errors.first ?: MSG_BAR_STOCK_CHECK_FORM)
        val id = store.add(buildBarStockPayload(form))
        guarded {
            audit.log(
                "bar_inventory_added", BAR_STOCK_PATH, id, null,
                mapOf("name" to form.name.trim(), "category" to BAR_STOCK_CATEGORY)
            )
        }
        return id
    }

    /**
     * Adds [amount] to the item's LIVE quantity (re-read inside the transaction, not the number on screen), so two people
     * restocking at once both count. The audit entry and the result use the committed numbers.
     */
    suspend fun restock(item: InventoryItem, amount: Int): RestockResult {
        if (amount < 1) throw BarStockException(MSG_BAR_STOCK_RESTOCK_AMOUNT)
        val result = store.restock(item.id, amount)
        guarded {
            audit.log(
                "bar_inventory_restocked", BAR_STOCK_PATH, item.id,
                mapOf("quantity" to result.before), mapOf("quantity" to result.after)
            )
        }
        return result
    }

    /** The audit entry is an extra: a failure there never turns a saved change into an error. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
