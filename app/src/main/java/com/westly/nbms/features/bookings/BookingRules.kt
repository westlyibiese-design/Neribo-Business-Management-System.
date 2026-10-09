package com.westly.nbms.features.bookings

import com.google.firebase.Timestamp
import com.westly.nbms.core.rbac.Role

// ---- Who may do what ------------------------------------------------------------------------------

/** Confirm, reject, cancel, no-show. The Operations Manager is read-only. */
internal val CHANGE_STATUS_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST)

/** Extend Stay. */
internal val EXTEND_STAY_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST)

internal fun canChangeStatus(role: Role): Boolean = role in CHANGE_STATUS_ROLES

internal fun canExtendStay(role: Role): Boolean = role in EXTEND_STAY_ROLES

// ---- Status transitions ---------------------------------------------------------------------------

/**
 * Manual status changes: pending -> confirmed | rejected | cancelled; confirmed -> cancelled | no_show.
 * Check-in and check-out are done by the check-in / check-out pages, so nothing else changes by hand.
 */
internal fun allowedTransitions(from: BookingStatus): Set<BookingStatus> = when (from) {
    BookingStatus.PENDING -> setOf(BookingStatus.CONFIRMED, BookingStatus.REJECTED, BookingStatus.CANCELLED)
    BookingStatus.CONFIRMED -> setOf(BookingStatus.CANCELLED, BookingStatus.NO_SHOW)
    else -> emptySet()
}

internal fun canTransition(from: BookingStatus, to: BookingStatus): Boolean = to in allowedTransitions(from)

// ---- Labels ---------------------------------------------------------------------------------------

/** "checked_in" -> "checked in" (chips, toasts). */
internal fun statusWords(statusKey: String): String = statusKey.replace('_', ' ')

/** "no_show" -> "No Show" (pills, badges). */
internal fun statusTitle(statusKey: String): String =
    statusKey.split('_').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

/** Toast text after a status change: "Booking confirmed." / "Booking no show." */
internal fun statusChangedMessage(newStatusKey: String): String = "Booking ${statusWords(newStatusKey)}."

/** Description of the detail dialog: the booking code, or the first 8 characters of the document id in capitals. */
internal fun bookingCode(b: Booking): String = b.bookingId?.takeIf { it.isNotBlank() } ?: b.id.take(8).uppercase()

/** "1 booking", "12 bookings". */
internal fun bookingCountText(n: Int): String = if (n == 1) "1 booking" else "$n bookings"

/** "{adults} adults, {children} children" (defaults 1 / 0 are already in the model). */
internal fun guestsText(b: Booking): String = "${b.adults} adults, ${b.children} children"

/** "1 stay" / "3 stays". */
internal fun stayCountText(n: Int): String = if (n == 1) "1 stay" else "$n stays"

internal fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

// ---- Filters --------------------------------------------------------------------------------------

/** Key to label of the status dropdown. */
internal val STATUS_FILTER_OPTIONS: List<Pair<String, String>> = listOf(
    "all" to "All Status",
    "pending" to "Pending",
    "confirmed" to "Confirmed",
    "checked_in" to "Checked In",
    "checked_out" to "Checked Out",
    "cancelled" to "Cancelled",
    "rejected" to "Rejected",
    "no_show" to "No Show"
)

/** The chips row: only these statuses get a chip. */
internal val STATUS_CHIP_KEYS: List<String> = listOf("all", "pending", "confirmed", "checked_in", "checked_out")

/** "All (12)" and "pending (3)", lowercase like Westly. */
internal fun chipLabel(key: String, counts: Map<String, Int>): String {
    val name = if (key == "all") "All" else statusWords(key)
    return "$name (${counts[key] ?: 0})"
}

/** Booking count for "all" and for every status key. */
internal fun statusCounts(bookings: List<Booking>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    counts["all"] = bookings.size
    BookingStatus.entries.forEach { s -> counts[s.key] = bookings.count { it.status == s.key } }
    return counts
}

private fun Timestamp?.millisOrMin(): Long = if (this == null) Long.MIN_VALUE else this.seconds * 1000L + this.nanoseconds / 1_000_000

/** Live bookings (deleted ones dropped), newest `createdAt` first; bookings without a date go last. */
internal fun visibleBookings(all: List<Booking>): List<Booking> =
    all.filter { !it.isDeleted }.sortedByDescending { it.createdAt.millisOrMin() }

/** Search (guest name, email, room number, booking id; ignoring capitals) plus the status filter ("all" or a key). */
internal fun filterBookings(bookings: List<Booking>, query: String, statusKey: String): List<Booking> {
    val q = query.trim().lowercase()
    return bookings.filter { b ->
        val statusOk = statusKey == "all" || b.status == statusKey
        val searchOk = q.isEmpty() ||
            b.guestName.lowercase().contains(q) ||
            (b.guestEmail?.lowercase()?.contains(q) == true) ||
            b.roomNumber.lowercase().contains(q) ||
            (b.bookingId?.lowercase()?.contains(q) == true)
        statusOk && searchOk
    }
}

// ---- What a status change writes ------------------------------------------------------------------

/** Fields of `bookings/{id}`. [updatedAt] is a server timestamp in the real app. */
internal fun bookingStatusFields(newStatus: BookingStatus, updatedAt: Any, uid: String, userName: String): Map<String, Any?> =
    mapOf(
        "status" to newStatus.key,
        "updatedAt" to updatedAt,
        "updatedBy" to uid,
        "updatedByName" to userName
    )

/**
 * Fields merged into the per-night lock `booking_dates/{id}`: roomId, checkIn, checkOut and the new status.
 * Dates the booking does not have are left out so a merge never blanks an existing lock.
 */
internal fun bookingLockFields(booking: Booking, newStatus: BookingStatus): Map<String, Any?> {
    val fields = LinkedHashMap<String, Any?>()
    fields["roomId"] = booking.roomId
    booking.checkIn?.let { fields["checkIn"] = it }
    booking.checkOut?.let { fields["checkOut"] = it }
    fields["status"] = newStatus.key
    return fields
}

/** Statuses that do not send a "booking modified" alert (the check-in and check-out pages send their own). */
internal fun sendsModifiedAlert(newStatus: BookingStatus): Boolean =
    newStatus != BookingStatus.CHECKED_IN && newStatus != BookingStatus.CHECKED_OUT

// ---- Guests ---------------------------------------------------------------------------------------

/** Live guests (deleted ones dropped), A to Z by name. */
internal fun visibleGuests(all: List<Guest>): List<Guest> =
    all.filter { !it.isDeleted }.sortedBy { it.name.trim().lowercase() }

/** Search by name, email or phone, ignoring capitals. */
internal fun filterGuests(guests: List<Guest>, query: String): List<Guest> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return guests
    return guests.filter { g ->
        g.name.lowercase().contains(q) ||
            (g.email?.lowercase()?.contains(q) == true) ||
            (g.phone?.lowercase()?.contains(q) == true)
    }
}

/** A guest's bookings: same guest id or same name. Newest check-in first. */
internal fun guestStays(guest: Guest, bookings: List<Booking>): List<Booking> =
    bookings
        .filter { !it.isDeleted && ((guest.id.isNotEmpty() && it.guestId == guest.id) || it.guestName == guest.name) }
        .sortedByDescending { (it.checkInAt ?: it.checkIn).millisOrMin() }
