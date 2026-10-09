package com.westly.nbms.features.reservations

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant

internal fun resBooking(
    id: String = "b1",
    name: String = "Ada Obi",
    room: String = "101",
    email: String? = "$id@example.com",
    code: String? = "WEB-AB12CD",
    status: String = "pending",
    source: String? = "website",
    createdAt: Long? = 100,
    deleted: Boolean = false,
    nights: Int? = 2,
    total: Double = 50_000.0,
    roomType: String? = "Deluxe Room"
) = Booking(
    id = id, bookingId = code, guestName = name, roomNumber = room, roomId = "room-$room", roomType = roomType,
    guestEmail = email, status = status, source = source, createdAt = createdAt?.let { Timestamp(it, 0) },
    isDeleted = deleted, nights = nights, totalAmount = total
)

internal fun resOutcome(paidNow: Boolean = true) = CheckInOutcome(
    guestName = "Ada Obi", roomNumber = "101", roomType = "Deluxe Room",
    checkInAt = Instant.parse("2026-03-10T13:05:00Z"), entitledCheckOut = Instant.parse("2026-03-12T10:00:00Z"),
    totalAmount = 50_000.0, paidNow = paidNow
)

internal class ResSession(timezone: String = "Africa/Lagos", role: Role = Role.RECEPTIONIST) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Rita", "r@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

internal class ResSource(initial: Resource<List<Booking>> = Resource.Loading) : ReservationsSource {
    val flow = MutableStateFlow(initial)
    override fun observe(): Flow<Resource<List<Booking>>> = flow
}

internal class ResNetwork(var online: Boolean = true) : ReservationsNetwork {
    override fun isOnline(): Boolean = online
}

/** A fake of the Part 15C-1 service. A gate holds a call open until the test releases it. */
internal class ResService : ReservationsService {
    var officialTime: String = "11:00"
    var officialGate: CompletableDeferred<String>? = null

    var statusResult: Result<Unit> = Result.success(Unit)
    var statusGate: CompletableDeferred<Unit>? = null
    val statusCalls = mutableListOf<Pair<Booking, BookingStatus>>()

    var checkInResult: () -> Result<CheckInOutcome> = { Result.success(resOutcome()) }
    var checkInGate: CompletableDeferred<Unit>? = null
    val checkInCalls = mutableListOf<Pair<Booking, CheckInForm>>()

    override suspend fun loadCheckOutTime(): String = officialGate?.await() ?: officialTime

    override suspend fun changeStatus(booking: Booking, newStatus: BookingStatus): Result<Unit> {
        statusCalls += booking to newStatus
        statusGate?.await()
        return statusResult
    }

    override suspend fun checkIn(booking: Booking, form: CheckInForm): Result<CheckInOutcome> {
        checkInCalls += booking to form
        checkInGate?.await()
        return checkInResult()
    }
}

/** Collects every toast the controller shows, from now on. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.collectToasts(toast: ToastController): List<ToastEvent> {
    val events = mutableListOf<ToastEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
    return events
}

/** Keeps the list state flow active (it only runs while someone collects it). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.keepActive(vm: ReservationsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { } }
}
