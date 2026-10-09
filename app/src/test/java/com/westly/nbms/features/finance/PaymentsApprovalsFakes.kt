package com.westly.nbms.features.finance

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** A [RevenueLedger] that records every approve and reject call. Set [gate] to hold a call open, or [failWith] to make it throw. */
internal class ApprovalsFakeLedger(
    initial: Resource<List<RevenueTransaction>> = Resource.Success(emptyList())
) : RevenueLedger {
    val flow = MutableStateFlow(initial)
    val approved = mutableListOf<RevenueTransaction>()
    val rejected = mutableListOf<Pair<RevenueTransaction, String?>>()
    var failWith: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var observeCalls = 0

    override fun observe(): Flow<Resource<List<RevenueTransaction>>> {
        observeCalls += 1
        return flow
    }

    override suspend fun approve(txn: RevenueTransaction) {
        approved += txn
        gate?.await()
        failWith?.let { throw it }
    }

    override suspend fun reject(txn: RevenueTransaction, reason: String?) {
        rejected += txn to reason
        gate?.await()
        failWith?.let { throw it }
    }
}

/** A signed-in (or signed-out, when [role] is null) session for the given role. */
internal class ApprovalsFakeSession(role: Role?, timezone: String = "Africa/Lagos") : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (role == null) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Test User", "t@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** A transaction for the Approvals tests. Defaults: a pending 1,000 cash Room Payment from "Ada Obi" on 7 Oct 2026. */
internal fun approvalsTxn(
    id: String,
    status: ApprovalStatus = ApprovalStatus.PENDING,
    amount: Double = 1_000.0,
    date: String? = "2026-10-07T10:00:00Z",
    guest: String = "Ada Obi",
    typeLabel: String = "Room Payment",
    category: RevenueCategory = RevenueCategory.ROOM,
    source: SourceCollection = SourceCollection.PAYMENTS,
    method: String = "cash",
    approvedByName: String? = null,
    approvedAt: String? = null,
    reason: String? = null
): RevenueTransaction = RevenueTransaction(
    id = id,
    source = source,
    category = category,
    typeLabel = typeLabel,
    guestName = guest,
    amount = amount,
    paymentMethod = method,
    date = date?.let { Instant.parse(it) },
    recordedBy = "u9",
    recordedByName = "Rita",
    approvalStatus = status,
    approvedBy = approvedByName?.let { "u8" },
    approvedByName = approvedByName,
    approvedAt = approvedAt?.let { Instant.parse(it) },
    rejectedReason = reason
)
