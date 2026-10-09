package com.westly.nbms.features.sales

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.inventory.InventoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory inventory + sales with real all-or-nothing transaction behaviour (writes apply only if the block finishes). */
internal class FakeSalesStore : SalesStore {
    val stock = mutableMapOf<String, StockRead>()
    val deleted = mutableSetOf<String>()
    val sales = mutableMapOf<String, Map<String, Any?>>()
    var transactions = 0
    private var counter = 0

    override fun observeInventory(): Flow<Resource<List<InventoryItem>>> = emptyFlow()
    override fun observeSales(staffId: String?): Flow<Resource<List<Sale>>> = emptyFlow()

    override suspend fun <R> inTransaction(block: (SaleTx, String) -> R): Pair<String, R> {
        transactions++
        val id = "sale${++counter}"
        val stockWrites = mutableMapOf<String, Int>()
        val saleWrites = mutableListOf<Map<String, Any?>>()
        val tx = object : SaleTx {
            override fun readItem(itemId: String): StockRead? = if (itemId in deleted) null else stock[itemId]
            override fun writeStock(itemId: String, newQuantity: Int) { stockWrites[itemId] = newQuantity }
            override fun createSale(payload: Map<String, Any?>) { saleWrites += payload }
        }
        val result = block(tx, id) // a throw here leaves every buffer unapplied
        stockWrites.forEach { (k, v) -> stock[k] = stock.getValue(k).copy(quantity = v) }
        saleWrites.forEach { sales[id] = it }
        return id to result
    }
}

internal class FakeSalesAudit : AuditLogger {
    val entries = mutableListOf<List<Any?>>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += listOf(action, collection, documentId, newValue)
    }
}

internal class FakeSalesNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message)
    }
}

internal class FakeSalesSession(signedIn: Boolean = true) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", Role.STAFF, "Sam", "s@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(Role.STAFF)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

internal fun item(
    id: String = "i1", name: String = "Bottled Water", category: String = "drinks",
    quantity: Int = 23, minStock: Int = 5, cost: Double = 200.0, deleted: Boolean = false
) = InventoryItem(id = id, name = name, category = category, quantity = quantity, minStock = minStock, unit = "bottles", costPerUnit = cost, isDeleted = deleted)

internal fun sale(
    id: String, staff: String = "Sam", customer: String? = null, seconds: Long? = null, total: Double = 100.0, deleted: Boolean = false
) = Sale(id = id, staffName = staff, customerName = customer, total = total, createdAt = seconds?.let { Timestamp(it, 0) }, isDeleted = deleted)
