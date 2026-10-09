package com.westly.nbms.features.checkin

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.rooms.Cleanliness
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomDisplayStatus
import com.westly.nbms.features.rooms.RoomEvent
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The store the tests use: records what the transaction would write and can be told to fail or wait. */
internal class FakeWalkInStore : WalkInStore {
    val commits = mutableListOf<WalkInWrites>()
    var failWith: Exception? = null
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var neverFinish = false
    private var counter = 0

    override fun newIds(): WalkInIds {
        counter++
        return WalkInIds("guest$counter", "booking${counter}abcdef", "checkin$counter", "payment$counter")
    }

    override suspend fun commit(writes: WalkInWrites) {
        yield()
        if (neverFinish) kotlinx.coroutines.delay(Long.MAX_VALUE)
        gate?.await()
        failWith?.let { throw it }
        commits += writes
    }
}

internal class FakeRoomLogic : RoomLogic {
    var conflict = false
    val conflictChecks = mutableListOf<Triple<String, Instant, Instant>>()

    override fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean = aIn < bOut && aOut > bIn
    override suspend fun detectConflict(roomId: String, checkIn: Instant, checkOut: Instant, excludeBookingId: String?): Boolean {
        conflictChecks += Triple(roomId, checkIn, checkOut)
        return conflict
    }
    override suspend fun findAvailableRooms(roomType: String, checkIn: Instant, checkOut: Instant): List<Room> = emptyList()
    override suspend fun updateRoomStatus(roomId: String, newStatus: RoomStatus, extra: Map<String, Any?>, allowOccupiedOverride: Boolean) =
        throw UnsupportedOperationException()
    override suspend fun updateRoomCleanliness(roomId: String, cleanliness: Cleanliness, extra: Map<String, Any?>) =
        throw UnsupportedOperationException()
    override fun getRoomDisplayStatus(room: Room): RoomDisplayStatus = throw UnsupportedOperationException()
    override suspend fun triggerRoomStatus(roomId: String, event: RoomEvent) = throw UnsupportedOperationException()
}

internal class FakeRealtime : BusinessRealtime {
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val sets = mutableListOf<Pair<String, Any?>>()
    var fail = false

    override fun <T : Any> observe(path: String, clazz: Class<T>): Flow<Resource<T?>> = emptyFlow()
    override fun <T : Any> observeMap(path: String, clazz: Class<T>): Flow<Resource<Map<String, T>>> = emptyFlow()
    override suspend fun set(path: String, value: Any?) {
        if (fail) throw IllegalStateException("realtime is down")
        sets += path to value
    }
    override suspend fun update(path: String, fields: Map<String, Any?>) {
        if (fail) throw IllegalStateException("realtime is down")
        updates += path to fields
    }
    override suspend fun increment(path: String, delta: Long) = Unit
}

internal class FakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)

    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class FakeNotifier : Notifier {
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

internal class FakeConnectivity(
    var online: Boolean = true,
    var lossEvents: Flow<Unit> = emptyFlow()
) : Connectivity {
    override fun isOnline(): Boolean = online
    override fun losses(): Flow<Unit> = lossEvents
}

internal class FakeSession(role: Role? = Role.RECEPTIONIST, usesPin: Boolean = false) : SessionManager {
    var signOuts = 0
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (role == null) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Rita", "r@x.com", null, "active", usesPin),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = "Africa/Lagos"),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {
        signOuts++
    }
    override suspend fun refresh() {}
}

internal fun testRoom(
    id: String = "r1",
    number: String = "101",
    status: String = "available",
    price: Double = 25_000.0
) = Room(id = id, number = number, type = "Deluxe Room", price = price, status = status)

/** A filled-in form: check-in 10 Oct 2026 14:00, check-out 12 Oct 2026 (two nights). */
internal fun testForm(
    option: PaymentOption = PaymentOption.PAY_AT_CHECKIN,
    method: PaymentMethod = PaymentMethod.CASH
) = WalkInForm(
    fullName = "  Ada Obi ",
    phone = "08031234567",
    email = "",
    nationality = "Nigerian",
    idDocumentRef = "A1234567",
    roomId = "r1",
    checkInDate = LocalDate(2026, 10, 10),
    checkInTime = LocalTime(14, 0),
    checkOutDate = LocalDate(2026, 10, 12),
    adults = 2,
    children = 1,
    paymentOption = option,
    paymentMethod = method,
    notes = "Late arrival"
)
