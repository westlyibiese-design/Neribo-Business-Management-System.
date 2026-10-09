package com.westly.nbms.features.finance

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

/** In-memory stand-in for the five income collections. */
internal class FinanceFakeStore : RevenueSourceStore {
    data class Update(val source: SourceCollection, val id: String, val fields: Map<String, Any?>)

    val paymentsFlow = MutableStateFlow<Resource<List<RawPayment>>>(Resource.Success(emptyList()))
    val salesFlow = MutableStateFlow<Resource<List<RawSale>>>(Resource.Success(emptyList()))
    val ordersFlow = MutableStateFlow<Resource<List<RawOrder>>>(Resource.Success(emptyList()))
    val barOrdersFlow = MutableStateFlow<Resource<List<RawBarOrder>>>(Resource.Success(emptyList()))
    val laundryFlow = MutableStateFlow<Resource<List<RawLaundry>>>(Resource.Success(emptyList()))

    val updates = mutableListOf<Update>()
    var failUpdate: Exception? = null

    override fun payments(): Flow<Resource<List<RawPayment>>> = paymentsFlow
    override fun sales(): Flow<Resource<List<RawSale>>> = salesFlow
    override fun orders(): Flow<Resource<List<RawOrder>>> = ordersFlow
    override fun barOrders(): Flow<Resource<List<RawBarOrder>>> = barOrdersFlow
    override fun laundryRequests(): Flow<Resource<List<RawLaundry>>> = laundryFlow

    override suspend fun updateFields(source: SourceCollection, id: String, fields: Map<String, Any?>) {
        failUpdate?.let { throw it }
        updates += Update(source, id, fields)
    }
}

internal class FinanceFakeAudit : AuditLogger {
    data class Entry(
        val action: String,
        val collection: String,
        val id: String,
        val previous: Map<String, Any?>?,
        val new: Map<String, Any?>?
    )

    val entries = mutableListOf<Entry>()
    var fail: Exception? = null

    override suspend fun log(
        action: String,
        collection: String,
        documentId: String,
        previousValue: Map<String, Any?>?,
        newValue: Map<String, Any?>?
    ) {
        fail?.let { throw it }
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class FinanceFakeNotifier : Notifier {
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

internal class FinanceFakeSession(role: Role?, timezone: String = "Africa/Lagos") : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (role == null) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Ada Accounts", "a@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** A ready-made transaction for tests; override only what a test cares about. */
internal fun financeTxn(
    id: String = "t1",
    source: SourceCollection = SourceCollection.PAYMENTS,
    category: RevenueCategory = RevenueCategory.ROOM,
    amount: Double = 1000.0,
    status: ApprovalStatus = ApprovalStatus.PENDING,
    date: Instant? = Instant.parse("2026-10-07T10:00:00Z"),
    guestName: String = "Ada Obi"
) = RevenueTransaction(
    id = id,
    source = source,
    category = category,
    typeLabel = "Room Payment",
    guestName = guestName,
    amount = amount,
    paymentMethod = "cash",
    date = date,
    recordedBy = "u9",
    recordedByName = "Rita",
    approvalStatus = status,
    approvedBy = null,
    approvedByName = null,
    approvedAt = null,
    rejectedReason = null
)
