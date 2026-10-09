package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
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

/** 2026-10-09 11:00 in Lagos (UTC+1). */
internal val LAUNDRY_HISTORY_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun laundryHistoryRequestOf(
    id: String,
    guest: String? = "Mr Okoro",
    room: String? = "201",
    items: String? = "2 shirts",
    count: Int = 2,
    charge: Double = 4000.0,
    payment: PaymentStatus = PaymentStatus.UNPAID,
    status: LaundryStatus = LaundryStatus.RECEIVED,
    valetId: String? = "u1",
    valet: String = "Wale",
    at: Instant? = LAUNDRY_HISTORY_NOW,
    deliveredAt: Instant? = null,
    deleted: Boolean = false
) = LaundryRequest(
    id = id, guestName = guest, roomNumber = room, itemsDescription = items, itemCount = count, charge = charge,
    paymentMethod = "room_charge", paymentStatus = payment, notes = null, status = status,
    laundryValetId = valetId, laundryValetName = valet, createdAt = at, receivedAt = at, deliveredAt = deliveredAt, isDeleted = deleted
)

internal class LaundryHistoryFakeSession(
    role: Role = Role.LAUNDRY_VALET,
    uid: String = "u1",
    signedIn: Boolean = true
) : SessionManager {
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

/** In-memory `laundry_requests`. Records every `observe` call. The store has no write call at all: History is read-only. */
internal class LaundryHistoryFakeStore : LaundryHistoryStore {
    val requests = MutableStateFlow<Resource<List<LaundryRequest>>>(Resource.Success(emptyList()))
    val observedWith = mutableListOf<String?>()

    override fun observe(valetId: String?): Flow<Resource<List<LaundryRequest>>> {
        observedWith += valetId
        return requests
    }
}
