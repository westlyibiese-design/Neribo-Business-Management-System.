package com.westly.nbms.features.inventory

import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_INVENTORY_LOAD_FAILED = "We couldn't load inventory."
internal const val MSG_ITEM_MISSING = "This item no longer exists."
internal const val MSG_QUANTITY_TOO_LARGE = "That quantity is too large."
internal const val MSG_RESTOCK_AMOUNT = "Enter a whole number of 1 or more."
internal const val MSG_CHECK_FORM = "Please check the form."

// ── forms and pure rules (unit-tested without Android) ──

/** What the person typed in the Add Item dialog. [categoryKey] holds the stored key. */
data class AddItemForm(
    val name: String = "",
    val categoryKey: String = "hotel_supplies",
    val unit: String = "pcs",
    val quantityText: String = "",
    val minStockText: String = "",
    val costText: String = "",
    val supplier: String = ""
)

internal data class AddItemErrors(
    val name: String? = null,
    val quantity: String? = null,
    val minStock: String? = null,
    val cost: String? = null
) {
    val any: Boolean get() = name != null || quantity != null || minStock != null || cost != null
    val first: String? get() = name ?: quantity ?: minStock ?: cost
}

/** The typed text as a whole number of 0 or more; blank, decimals, letters and numbers too big for an Int are null. */
internal fun parseWholeNumber(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    return clean.toIntOrNull()
}

/** Keeps only digits (at most 9, so the number always fits an Int) while the person types. */
internal fun filterWholeNumberInput(text: String): String = text.filter { it in '0'..'9' }.take(9)

/** The typed cost: blank means 0, otherwise digits with at most one dot and 0 or more. Anything else is null. */
internal fun parseCost(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return 0.0
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps only digits and the first dot while the person types a cost. */
internal fun filterCostInput(text: String): String {
    var seenDot = false
    val out = StringBuilder()
    for (c in text) {
        if (c in '0'..'9') out.append(c)
        else if (c == '.' && !seenDot) {
            seenDot = true
            out.append(c)
        }
    }
    return out.toString()
}

internal fun validateAddItemForm(form: AddItemForm): AddItemErrors = AddItemErrors(
    name = if (form.name.isBlank()) "Item name is required." else null,
    quantity = if (parseWholeNumber(form.quantityText) == null) "Enter a whole number of 0 or more." else null,
    minStock = if (parseWholeNumber(form.minStockText) == null) "Enter a whole number of 0 or more." else null,
    cost = if (parseCost(form.costText) == null) "Enter an amount of 0 or more." else null
)

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object InventoryServerTime

/**
 * The new `inventory/{id}` document, exactly as Westly wrote it: `isDeleted` false, `lastRestocked` and `createdAt`
 * server time, `costPerUnit` 0 when blank and `supplier` null when blank. A blank unit falls back to "pcs".
 */
internal fun buildAddItemPayload(form: AddItemForm): Map<String, Any?> = mapOf(
    "name" to form.name.trim(),
    "category" to (InventoryCategory.fromKey(form.categoryKey) ?: InventoryCategory.HOTEL_SUPPLIES).key,
    "quantity" to (parseWholeNumber(form.quantityText) ?: 0),
    "minStock" to (parseWholeNumber(form.minStockText) ?: 0),
    "unit" to form.unit.trim().ifEmpty { "pcs" },
    "costPerUnit" to (parseCost(form.costText) ?: 0.0),
    "supplier" to form.supplier.trim().ifEmpty { null },
    "isDeleted" to false,
    "lastRestocked" to InventoryServerTime,
    "createdAt" to InventoryServerTime
)

/** The quantity after a restock. Throws [InventoryException] if the sum would not fit an Int. */
internal fun restockedQuantity(before: Int, amount: Int): Int {
    if (amount < 1) throw InventoryException(MSG_RESTOCK_AMOUNT)
    return try {
        Math.addExact(before, amount)
    } catch (e: ArithmeticException) {
        throw InventoryException(MSG_QUANTITY_TOO_LARGE)
    }
}

/** The fields a restock writes to `inventory/{id}` (the times are server time). */
internal fun buildRestockFields(after: Int): Map<String, Any?> = mapOf(
    "quantity" to after,
    "lastRestocked" to InventoryServerTime,
    "updatedAt" to InventoryServerTime
)

/** A problem with a clear sentence for the person (shown in the "Error" toast). */
class InventoryException(message: String) : Exception(message)

/** The quantity the transaction found, and the quantity it committed. */
data class RestockResult(val before: Int, val after: Int)

// ── the store ──

/** The database calls Inventory needs. [FirestoreInventoryStore] is the real one; the unit tests use a fake. */
interface InventoryStore {
    /** Every `inventory` document, live (deleted ones included; the repository leaves them out). */
    fun observe(): Flow<Resource<List<InventoryItem>>>

    /** Adds `inventory/{new}` and returns the new id. The payload may hold [InventoryServerTime]. */
    suspend fun add(payload: Map<String, Any?>): String

    /**
     * ONE transaction: re-read the live item, add [amount] to the live quantity, write `quantity`, `lastRestocked` and
     * `updatedAt`. A missing or deleted item stops with an [InventoryException].
     */
    suspend fun restock(itemId: String, amount: Int): RestockResult
}

@Singleton
class FirestoreInventoryStore @Inject constructor(
    private val firestore: BusinessFirestore
) : InventoryStore {

    override fun observe(): Flow<Resource<List<InventoryItem>>> =
        firestore.observeList("inventory", InventoryItem::class.java) { it.orderBy("name") }

    override suspend fun add(payload: Map<String, Any?>): String =
        firestore.add("inventory", payload.resolveServerTime())

    override suspend fun restock(itemId: String, amount: Int): RestockResult {
        try {
            return firestore.runTransaction { tx, fs ->
                val ref = fs.doc("inventory", itemId)
                val snap = tx.get(ref)
                if (!snap.exists() || snap.getBoolean("isDeleted") == true) throw InventoryException(MSG_ITEM_MISSING)
                val before = snap.getLong("quantity")?.toInt() ?: 0
                val after = restockedQuantity(before, amount)
                tx.update(ref, buildRestockFields(after).resolveServerTime())
                RestockResult(before, after)
            }
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.inventoryCause() ?: e
        }
    }
}

private fun Map<String, Any?>.resolveServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === InventoryServerTime) FieldValue.serverTimestamp() else v }

private fun Throwable.inventoryCause(): InventoryException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is InventoryException) return current
        current = current.cause
        depth++
    }
    return null
}

// ── the repository ──

/** Reads `inventory` live, adds items and restocks them. Every change is written to the audit log. */
@Singleton
class InventoryRepository @Inject constructor(
    private val store: InventoryStore,
    private val audit: AuditLogger
) {

    /** Every item that is not deleted, in the order the database returns them (by name). */
    fun observe(): Flow<Resource<List<InventoryItem>>> =
        store.observe().map { resource ->
            if (resource is Resource.Success) Resource.Success(resource.data.filter { !it.isDeleted }) else resource
        }

    /** Adds the item and returns its id. A form that fails the checks throws [InventoryException] and writes nothing. */
    suspend fun add(form: AddItemForm): String {
        val errors = validateAddItemForm(form)
        if (errors.any) throw InventoryException(errors.first ?: MSG_CHECK_FORM)
        val id = store.add(buildAddItemPayload(form))
        guarded { audit.log("inventory_added", "inventory", id, null, mapOf("name" to form.name.trim())) }
        return id
    }

    /**
     * Adds [amount] to the item's LIVE quantity (not the number on screen), so two people restocking at once both count.
     * The audit entry and the result use the committed numbers.
     */
    suspend fun restock(item: InventoryItem, amount: Int): RestockResult {
        if (amount < 1) throw InventoryException(MSG_RESTOCK_AMOUNT)
        val result = store.restock(item.id, amount)
        guarded {
            audit.log(
                "inventory_restocked", "inventory", item.id,
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
