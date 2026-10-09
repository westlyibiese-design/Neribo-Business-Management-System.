package com.westly.nbms.features.checkout

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.rooms.Cleanliness
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomDisplayStatus
import com.westly.nbms.features.rooms.RoomEvent
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

internal val LAGOS: TimeZone = TimeZone.of("Africa/Lagos")

internal fun ts(iso: String): Timestamp = Timestamp(Instant.parse(iso).epochSeconds, 0)

internal fun at(iso: String): Instant = Instant.parse(iso)

/** A guest checked in 10 Oct 2026 14:00 (Lagos), checking out 12 Oct 2026 (scheduled 11:00 Lagos = 10:00 UTC). */
internal fun testBooking(
    paid: Boolean = true,
    total: Double = 50_000.0,
    roomId: String = "r1",
    method: String? = "cash"
) = Booking(
    id = "b1abcdef99",
    bookingId = "WI-AB12CD",
    guestId = "g1",
    guestName = "Ada Obi",
    guestEmail = "ada@x.com",
    guestPhone = "08031234567",
    roomId = roomId,
    roomNumber = "101",
    roomType = "Deluxe Room",
    checkIn = ts("2026-10-10T13:00:00Z"),
    checkInAt = ts("2026-10-10T13:00:00Z"),
    checkOut = ts("2026-10-12T10:00:00Z"),
    nights = 2,
    totalAmount = total,
    paymentMethod = method,
    roomPaymentStatus = if (paid) "paid" else "pending",
    status = "checked_in"
)

internal val STAFF = CheckOutStaff("u1", "Rita")

internal fun testInput(
    actual: Instant? = at("2026-10-12T10:00:00Z"),
    extras: Double = 0.0,
    method: CheckOutPaymentMethod = CheckOutPaymentMethod.CASH,
    notes: String = ""
) = CheckOutInput(
    actualAt = actual,
    extraCharges = extras,
    method = method,
    notes = notes,
    staff = STAFF,
    zone = LAGOS,
    officialCheckOutTime = "11:00",
    businessName = "Westly Hotel",
    currencySymbol = "₦"
)

internal class FakeCheckOutStore(var booking: Booking? = testBooking()) : CheckOutStore {
    val commits = mutableListOf<CheckOutCommit>()
    var failWith: Exception? = null
    var neverFinish = false

    override fun newIds(): CheckOutIds = CheckOutIds("co1", "pay1")

    override suspend fun commit(bookingDocId: String, build: (LiveBooking) -> CheckOutWrites): CheckOutCommit {
        yield()
        if (neverFinish) delay(Long.MAX_VALUE)
        failWith?.let { throw it }
        val b = booking
        bookingCheckError(b != null, b?.status)?.let { throw CheckOutException(it) }
        val live = b!!.toLive()
        val commit = CheckOutCommit(live, build(live))
        commits += commit
        return commit
    }
}

internal class FakeExtendStayStore(
    var live: LiveStay? = testStay(),
    var roomExists: Boolean = true,
    var roomBookingId: String? = "b1abcdef99"
) : ExtendStayStore {
    val commits = mutableListOf<ExtendWrites>()
    var failWith: Exception? = null

    override fun newPaymentId(): String = "pay1"

    override suspend fun commit(bookingDocId: String, build: (LiveStay) -> ExtendWrites): ExtendWrites {
        yield()
        failWith?.let { throw it }
        stayCheckError(live != null, live?.status)?.let { throw ExtendStayException(it) }
        roomAssignmentError(roomExists, roomBookingId, bookingDocId)?.let { throw ExtendStayException(it) }
        val writes = build(live!!)
        commits += writes
        return writes
    }
}

internal fun testStay(
    pricePerNight: Double? = 25_000.0,
    total: Double = 50_000.0,
    nights: Int? = 2,
    roomPrice: Double? = 30_000.0,
    overdue: Boolean = false
) = LiveStay(
    id = "b1abcdef99",
    status = "checked_in",
    guestName = "Ada Obi",
    roomId = "r1",
    roomNumber = "101",
    checkIn = at("2026-10-10T13:00:00Z"),
    checkOut = at("2026-10-12T10:00:00Z"),
    nights = nights,
    pricePerNight = pricePerNight,
    totalAmount = total,
    roomPrice = roomPrice,
    roomCheckoutOverdue = overdue
)

internal class FakeNetwork(var online: Boolean = true) : CheckOutNetwork {
    override fun isOnline(): Boolean = online
}

internal class FakeRoomLogic : RoomLogic {
    var conflict = false
    data class Check(val roomId: String, val checkIn: Instant, val checkOut: Instant, val exclude: String?)
    val checks = mutableListOf<Check>()

    override fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean = aIn < bOut && aOut > bIn
    override suspend fun detectConflict(roomId: String, checkIn: Instant, checkOut: Instant, excludeBookingId: String?): Boolean {
        checks += Check(roomId, checkIn, checkOut, excludeBookingId)
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
