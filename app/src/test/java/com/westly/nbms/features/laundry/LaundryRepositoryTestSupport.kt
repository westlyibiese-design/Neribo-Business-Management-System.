package com.westly.nbms.features.laundry

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
import java.time.Instant

internal val LAUNDRY22A_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun laundry22aRequestOf(
    id: String,
    guest: String? = "Mr Okoro",
    room: String? = "201",
    status: LaundryStatus = LaundryStatus.RECEIVED,
    payment: PaymentStatus = PaymentStatus.UNPAID,
    charge: Double = 4000.0,
    at: Instant? = LAUNDRY22A_NOW,
    deleted: Boolean = false,
    items: String? = "3 shirts",
    count: Int = 3
) = LaundryRequest(
    id = id, guestName = guest, roomNumber = room, itemsDescription = items, itemCount = count, charge = charge,
    paymentMethod = "room_charge", paymentStatus = payment, notes = null, status = status,
    laundryValetId = "u1", laundryValetName = "Ada", createdAt = at, receivedAt = at, deliveredAt = null, isDeleted = deleted
)

internal class Laundry22aFakeSession(
    role: Role = Role.LAUNDRY_VALET, uid: String = "u1", usesPin: Boolean = false, signedIn: Boolean = true
) : SessionManager {
    var signOuts = 0
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser(uid, "biz1", role, "Ada", "a@x.com", null, "active", usesPin),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() { signOuts++ }
    override suspend fun refresh() {}
}

/** In-memory `laundry_requests`. Records every create and update. */
internal class Laundry22aFakeStore : LaundryStore {
    val requests = MutableStateFlow<Resource<List<LaundryRequest>>>(Resource.Success(emptyList()))
    val created = mutableListOf<Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null
    var hang = false
    private var counter = 0

    override fun observe(): Flow<Resource<List<LaundryRequest>>> = requests

    override suspend fun create(payload: Map<String, Any?>): String {
        if (hang) kotlinx.coroutines.awaitCancellation()
        failWith?.let { throw it }
        created += payload
        return "req${++counter}"
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        failWith?.let { throw it }
        updates += id to fields
    }
}

internal class Laundry22aFakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class Laundry22aFakeNotifier : Notifier {
    data class Call(val title: String, val message: String, val link: String?)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(title, message, link)
    }
}
