package com.westly.nbms.features.inventory

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The store the tests use. [items] is what `observe()` shows. [live] holds the quantity the database really has for each
 * id, which is what a restock transaction re-reads (it can differ from the number on the screen).
 */
internal class FakeInventoryStore : InventoryStore {
    val items = MutableStateFlow<Resource<List<InventoryItem>>>(Resource.Success(emptyList()))
    val adds = mutableListOf<Map<String, Any?>>()
    val live = mutableMapOf<String, Int>()
    val restocks = mutableListOf<Pair<String, Int>>()
    var failWith: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override fun observe(): Flow<Resource<List<InventoryItem>>> = items

    override suspend fun add(payload: Map<String, Any?>): String {
        gate?.await()
        failWith?.let { throw it }
        adds += payload
        return "new1"
    }

    override suspend fun restock(itemId: String, amount: Int): RestockResult {
        gate?.await()
        failWith?.let { throw it }
        val before = live[itemId] ?: throw InventoryException(MSG_ITEM_MISSING)
        val after = restockedQuantity(before, amount)
        live[itemId] = after
        restocks += itemId to amount
        return RestockResult(before, after)
    }
}

internal class FakeInventoryAudit : AuditLogger {
    data class Entry(
        val action: String,
        val collection: String,
        val documentId: String,
        val previous: Map<String, Any?>?,
        val new: Map<String, Any?>?
    )

    val entries = mutableListOf<Entry>()
    var fail = false

    override suspend fun log(
        action: String, collection: String, documentId: String,
        previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?
    ) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

/** An inventory item for the tests. Defaults: 10 "pcs" of Hotel Supplies with a minimum of 5. */
internal fun invItem(
    id: String,
    name: String = "Item $id",
    category: String = "hotel_supplies",
    quantity: Int = 10,
    minStock: Int = 5,
    unit: String = "pcs",
    cost: Double = 0.0,
    supplier: String? = null,
    deleted: Boolean = false
) = InventoryItem(
    id = id, name = name, category = category, quantity = quantity, minStock = minStock,
    unit = unit, costPerUnit = cost, supplier = supplier, isDeleted = deleted
)
