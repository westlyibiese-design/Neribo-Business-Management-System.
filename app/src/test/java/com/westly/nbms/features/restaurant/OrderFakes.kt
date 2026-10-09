package com.westly.nbms.features.restaurant

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory orders. `createOrder` and `updateOrder` record what the real store would write. */
internal class FakeOrdersStore : OrdersStore {
    val created = mutableMapOf<String, Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var createCalls = 0
    var failCreateWith: Exception? = null
    var failUpdateWith: Exception? = null
    var hang = false
    private var counter = 0

    override fun observeOrders(waiterId: String?): Flow<Resource<List<Order>>> = emptyFlow()

    override suspend fun createOrder(payload: Map<String, Any?>): String {
        createCalls++
        if (hang) kotlinx.coroutines.awaitCancellation()
        failCreateWith?.let { throw it }
        val id = "order${++counter}"
        created[id] = payload
        return id
    }

    override suspend fun updateOrder(id: String, fields: Map<String, Any?>) {
        failUpdateWith?.let { throw it }
        updates += id to fields
    }
}

internal class FakeOrderAudit : AuditLogger {
    val entries = mutableListOf<List<Any?>>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += listOf(action, collection, documentId, newValue)
    }
}

internal class FakeOrderNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String, val link: String?)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message, link)
    }
}

internal class FakeOrderSession(signedIn: Boolean = true, role: Role = Role.WAITER) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Wale", "w@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

internal fun testOrder(
    id: String, waiter: String = "Wale", customer: String? = null, room: String? = null, table: String? = null,
    status: String = "pending", seconds: Long? = null, total: Double = 100.0, deleted: Boolean = false,
    items: List<OrderLine> = emptyList()
) = Order(
    id = id, waiterName = waiter, customerName = customer, roomNumber = room, tableNumber = table,
    status = status, total = total, createdAt = seconds?.let { Timestamp(it, 0) }, isDeleted = deleted, items = items
)
