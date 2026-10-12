package com.westly.nbms.features.dashboard.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// Small row projections for the management dashboards (Phase 33A1).
//
// Each dashboard row has defaults and carries only the fields the dashboards need; Firestore ignores every other field.
// Date fields are java.time.Instant (converted from Firestore Timestamp by the Raw* classes below).
// Soft-deleted rows are NOT removed here: DashboardMath filters `isDeleted` everywhere.
// ─────────────────────────────────────────────────────────────────────────────

data class DashRoom(
    val id: String = "",
    val roomNumber: String? = null,
    val status: String? = null,
    val isDeleted: Boolean = false
)

data class DashBooking(
    val id: String = "",
    val guestName: String? = null,
    val roomNumber: String? = null,
    val roomType: String? = null,
    val status: String? = null,
    val source: String? = null,
    val checkIn: Instant? = null,
    val checkOut: Instant? = null,
    val checkInAt: Instant? = null,
    val checkOutAt: Instant? = null,
    val createdAt: Instant? = null,
    val isDeleted: Boolean = false
)

data class DashExpense(
    val id: String = "",
    val amount: Double = 0.0,
    val date: Instant? = null,
    val isDeleted: Boolean = false
)

/** Used for BOTH `orders` (restaurant) and `bar_orders`. */
data class DashOrder(
    val id: String = "",
    val status: String? = null,
    val waiterId: String? = null,
    val customerName: String? = null,
    val roomNumber: String? = null,
    val tableNumber: String? = null,
    val createdAt: Instant? = null,
    val isDeleted: Boolean = false
)

data class DashLaundry(
    val id: String = "",
    val status: String? = null,
    val guestName: String? = null,
    val roomNumber: String? = null,
    val isDeleted: Boolean = false
)

data class DashMaintenance(
    val id: String = "",
    val status: String? = null,
    val issue: String? = null,
    val title: String? = null,
    val roomOrArea: String? = null,
    val isDeleted: Boolean = false
)

data class DashAttendance(
    val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val dateKey: String? = null,
    val status: String? = null,
    val isDeleted: Boolean = false
)

data class DashTask(
    val id: String = "",
    val status: String? = null,
    val dueAt: Instant? = null,
    val isDeleted: Boolean = false
)

/** Internal to Phase 33A (GymOverviewCard / gymSummary). [status] is the STORED status; the effective one comes from GymLogic. */
data class DashGymMember(
    val id: String = "",
    val name: String = "",
    val status: String? = null,
    val endDate: Instant? = null,
    val isDeleted: Boolean = false
)

/** Internal to Phase 33A. One gym visit; `checkOutAt == null` means the member is still in the gym. */
data class DashGymVisit(
    val id: String = "",
    val memberId: String = "",
    val checkInAt: Instant? = null,
    val checkOutAt: Instant? = null,
    val dateKey: String? = null,
    val isDeleted: Boolean = false
)

/** One line of the live activity feed (Realtime Database `activity_feed/{pushId}`: `{type, text, at, by}`). `at` = epoch millis. */
data class ActivityItem(
    val type: String = "",
    val text: String = "",
    val at: Long = 0L,
    val by: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// What Firestore actually stores (Timestamps, owning phases' field names) → mapped to the rows above.
// ─────────────────────────────────────────────────────────────────────────────

internal fun Timestamp?.toDashInstant(): Instant? =
    this?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

internal val DASH_DEFAULT_ZONE: ZoneId = ZoneId.of("Africa/Lagos")

/** The business zone, or Africa/Lagos for a missing or unknown zone name. */
internal fun dashZoneOf(timezone: String?): ZoneId =
    if (timezone.isNullOrBlank()) DASH_DEFAULT_ZONE else try {
        ZoneId.of(timezone)
    } catch (e: Exception) {
        DASH_DEFAULT_ZONE
    }

private val DATE_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** `rooms` (Phase 11): the room number is stored as `number`. */
internal data class RawDashRoom(
    @DocumentId val id: String = "",
    val number: String? = null,
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashRoom(id = id, roomNumber = number, status = status, isDeleted = isDeleted)
}

/** `bookings` (Phase 12). */
internal data class RawDashBooking(
    @DocumentId val id: String = "",
    val guestName: String? = null,
    val roomNumber: String? = null,
    val roomType: String? = null,
    val status: String? = null,
    val source: String? = null,
    val checkIn: Timestamp? = null,
    val checkOut: Timestamp? = null,
    val checkInAt: Timestamp? = null,
    val checkOutAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashBooking(
        id = id, guestName = guestName, roomNumber = roomNumber, roomType = roomType, status = status, source = source,
        checkIn = checkIn.toDashInstant(), checkOut = checkOut.toDashInstant(),
        checkInAt = checkInAt.toDashInstant(), checkOutAt = checkOutAt.toDashInstant(),
        createdAt = createdAt.toDashInstant(), isDeleted = isDeleted
    )
}

/** `expenses` (Phase 17). */
internal data class RawDashExpense(
    @DocumentId val id: String = "",
    val amount: Double = 0.0,
    val date: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashExpense(id = id, amount = amount, date = date.toDashInstant(), isDeleted = isDeleted)
}

/**
 * `orders` (Phase 20: `waiterId`) and `bar_orders` (Phase 21: `barAttendantId`). Both map to [DashOrder.waiterId]:
 * the restaurant's `waiterId` wins, a bar order's `barAttendantId` is the fallback.
 */
internal data class RawDashOrder(
    @DocumentId val id: String = "",
    val status: String? = null,
    val waiterId: String? = null,
    val barAttendantId: String? = null,
    val customerName: String? = null,
    val roomNumber: String? = null,
    val tableNumber: String? = null,
    val createdAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashOrder(
        id = id, status = status,
        waiterId = waiterId?.takeIf { it.isNotBlank() } ?: barAttendantId,
        customerName = customerName, roomNumber = roomNumber, tableNumber = tableNumber,
        createdAt = createdAt.toDashInstant(), isDeleted = isDeleted
    )
}

/** `laundry_requests` (Phase 22). */
internal data class RawDashLaundry(
    @DocumentId val id: String = "",
    val status: String? = null,
    val guestName: String? = null,
    val roomNumber: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashLaundry(id = id, status = status, guestName = guestName, roomNumber = roomNumber, isDeleted = isDeleted)
}

/**
 * `maintenance` (Phase 25). Stored fields are `title`, `description`, `roomNumber` and `status` ("open" | "closed").
 * [DashMaintenance.issue] = `issue` when present, else `description`; [DashMaintenance.roomOrArea] = `roomNumber`, else `area`.
 */
internal data class RawDashMaintenance(
    @DocumentId val id: String = "",
    val status: String? = null,
    val issue: String? = null,
    val description: String? = null,
    val title: String? = null,
    val roomNumber: String? = null,
    val area: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashMaintenance(
        id = id, status = status,
        issue = issue?.takeIf { it.isNotBlank() } ?: description,
        title = title,
        roomOrArea = roomNumber?.takeIf { it.isNotBlank() && it != "—" } ?: area,
        isDeleted = isDeleted
    )
}

/**
 * `attendance` (Phase 26). Older records may have no `dateKey`; then it is read from the `date` timestamp as a local date
 * in the business [zone] (same rule as the attendance phase).
 */
internal data class RawDashAttendance(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val dateKey: String? = null,
    val date: Timestamp? = null,
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash(zone: ZoneId) = DashAttendance(
        id = id, staffId = staffId, staffName = staffName,
        dateKey = dateKey?.takeIf { it.isNotBlank() }
            ?: date.toDashInstant()?.atZone(zone)?.toLocalDate()?.format(DATE_KEY_FORMAT),
        status = status, isDeleted = isDeleted
    )
}

/** `tasks` (Phase 24). */
internal data class RawDashTask(
    @DocumentId val id: String = "",
    val status: String? = null,
    val dueAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashTask(id = id, status = status, dueAt = dueAt.toDashInstant(), isDeleted = isDeleted)
}

/** `gym_members` (Phase 29). */
internal data class RawDashGymMember(
    @DocumentId val id: String = "",
    val name: String = "",
    val status: String? = null,
    val endDate: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashGymMember(id = id, name = name, status = status, endDate = endDate.toDashInstant(), isDeleted = isDeleted)
}

/** `gym_attendance` (Phase 29): one document per visit. */
internal data class RawDashGymVisit(
    @DocumentId val id: String = "",
    val memberId: String = "",
    val checkInAt: Timestamp? = null,
    val checkOutAt: Timestamp? = null,
    val dateKey: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toDash() = DashGymVisit(
        id = id, memberId = memberId, checkInAt = checkInAt.toDashInstant(), checkOutAt = checkOutAt.toDashInstant(),
        dateKey = dateKey, isDeleted = isDeleted
    )
}
