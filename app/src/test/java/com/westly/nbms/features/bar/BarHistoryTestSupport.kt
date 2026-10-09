package com.westly.nbms.features.bar

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.inventory.InventoryItem
import com.westly.nbms.features.inventory.RestockResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

internal val BAR_HISTORY_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun barHistorySaleOf(
    id: String,
    attendant: String = "Wale",
    customer: String? = null,
    room: String? = null,
    table: String? = null,
    total: Double = 1000.0,
    status: BarSaleStatus = BarSaleStatus.PENDING,
    at: Instant? = BAR_HISTORY_NOW,
    deleted: Boolean = false,
    items: List<BarSaleLine> = emptyList(),
    attendantId: String = "u1"
) = BarSale(
    id = id, barAttendantId = attendantId, barAttendantName = attendant,
    customerName = customer, roomNumber = room, tableNumber = table,
    items = items, total = total, paymentMethod = "cash", notes = null,
    hasManualItems = false, status = status, createdAt = at, isDeleted = deleted
)

internal fun barHistoryLine(name: String, quantity: Int = 1) =
    BarSaleLine(id = name.lowercase(), name = name, price = 100.0, quantity = quantity, subtotal = 100.0 * quantity, isManual = false)

internal fun barHistoryItemOf(
    id: String,
    name: String = "Heineken 60cl",
    category: String = "drinks",
    quantity: Int = 10,
    minStock: Int = 5,
    unit: String = "bottles",
    deleted: Boolean = false
) = InventoryItem(id = id, name = name, category = category, quantity = quantity, minStock = minStock, unit = unit, isDeleted = deleted)

internal class BarHistoryFakeSession(role: Role = Role.BAR_ATTENDANT, uid: String = "u1", signedIn: Boolean = true) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser(uid, "biz1", role, "Wale", "w@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** In-memory `bar_orders`. Records every `observe` call and every update. */
internal class BarHistoryFakeStore : BarSalesHistoryStore {
    val sales = MutableStateFlow<Resource<List<BarSale>>>(Resource.Success(emptyList()))
    val observedWith = mutableListOf<String?>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null
    var hang = false
    var gate: CompletableDeferred<Unit>? = null

    override fun observe(attendantId: String?): Flow<Resource<List<BarSale>>> {
        observedWith += attendantId
        return sales
    }

    override suspend fun update(saleId: String, fields: Map<String, Any?>) {
        gate?.await()
        if (hang) awaitCancellation()
        failWith?.let { throw it }
        updates += saleId to fields
    }
}

internal class BarHistoryFakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val documentId: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)

    val entries = mutableListOf<Entry>()
    var fail = false

    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

/**
 * In-memory drinks stock. [items] is what `observe()` shows; [live] holds the quantity the database really has for each id,
 * which is what a restock transaction re-reads (it can differ from the number on screen).
 */
internal class BarHistoryFakeStockStore : BarInventoryStore {
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
        val before = live[itemId] ?: throw BarStockException(MSG_BAR_STOCK_ITEM_MISSING)
        val after = barRestockedQuantity(before, amount)
        live[itemId] = after
        restocks += itemId to amount
        return RestockResult(before, after)
    }
}
