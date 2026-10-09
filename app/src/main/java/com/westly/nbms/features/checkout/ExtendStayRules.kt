package com.westly.nbms.features.checkout

import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// ---- Texts (copied from Westly) --------------------------------------------------------------------

internal const val TITLE_EXTEND_FAILED = "Extension Failed"
internal const val TITLE_EXTEND_DONE = "Stay Extended"
internal const val TITLE_NOT_AUTHORIZED = "Not Authorized"
internal const val MSG_NOT_AUTHORIZED = "You don't have permission to extend a guest's stay."
internal const val TITLE_INVALID_EXTENSION = "Invalid Extension"
internal const val MSG_INVALID_EXTENSION = "Enter at least 1 additional night."
internal const val MSG_NO_CHECK_OUT = "This booking has no valid checkout date to extend from."
internal const val MSG_EXTEND_CONFLICT =
    "This room is already reserved by another guest during part of the requested extension. " +
        "Try fewer nights or move the guest to a different room."
internal const val MSG_EXTEND_NOT_CHECKED_IN = "This guest is no longer checked in — the stay can't be extended."
internal const val MSG_ROOM_MISSING = "Room not found."
internal const val MSG_ROOM_REASSIGNED =
    "This room is no longer assigned to this guest's booking — please refresh and try again."
internal const val MSG_CONFLICT_TIMEOUT =
    "Couldn't verify room availability — please check your connection and try again."
internal const val MSG_NO_PERMISSION_BOX = "You don't have permission to extend a guest's stay. Ask a Receptionist or Super Admin."
internal const val EXTEND_DESCRIPTION = "Extend this guest's stay instead of checking them out."

/** Longest wait for the room-availability check. */
internal const val CONFLICT_TIMEOUT_MS = 15_000L

/** The quick-pick chips (1, 2 and 3 nights). */
internal val QUICK_NIGHTS: List<Int> = listOf(1, 2, 3)

// ---- Who may extend --------------------------------------------------------------------------------

/** Only the Super Admin and the Receptionist may extend a guest's stay. */
internal val EXTEND_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST)

internal fun mayExtend(role: Role?): Boolean = role != null && role in EXTEND_ROLES

// ---- How the guest pays ----------------------------------------------------------------------------

/** The payment methods of the Extend Stay dialog. The stored key is the lower-case text. */
enum class ExtendPaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    CARD("card", "Card"),
    TRANSFER("transfer", "Bank Transfer"),
    POS("pos", "POS")
}

// ---- Rate and totals -------------------------------------------------------------------------------

/**
 * The price of one extra night: the booking's own price per night when it has one (above 0), otherwise the
 * booking total divided by its nights (when it has any), otherwise the room's price, otherwise 0.
 */
internal fun nightlyRate(pricePerNight: Double?, totalAmount: Double, nights: Int?, roomPrice: Double?): Double = when {
    pricePerNight != null && pricePerNight > 0.0 -> pricePerNight
    nights != null && nights > 0 -> totalAmount / nights
    else -> roomPrice ?: 0.0
}

/** What the extra nights cost. */
internal fun additionalAmount(rate: Double, extraNights: Int): Double = rate * extraNights

/**
 * The night field: text that is not a number (or is 0) counts as 1, like Westly's `parseInt(value) || 1`.
 */
internal fun parseExtraNights(text: String): Int = text.trim().toIntOrNull()?.takeIf { it != 0 } ?: 1

// ---- Dates -----------------------------------------------------------------------------------------

/** The check-out moved by whole calendar days in the business zone; the time of day stays the same. */
internal fun addDays(checkOut: Instant, days: Int, zone: TimeZone): Instant {
    val local = checkOut.toLocalDateTime(zone)
    return LocalDateTime(local.date.plus(DatePeriod(days = days)), local.time).toInstant(zone)
}

/** "Oct 12, 2026" (the format of the toast and of the stay-extended alert). */
internal fun mmmDYyyy(date: LocalDate): String {
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${months[date.monthNumber - 1]} ${date.dayOfMonth}, ${date.year}"
}

internal fun mmmDYyyy(instant: Instant, zone: TimeZone): String = mmmDYyyy(instant.localDate(zone))

/** Whole nights between two moments (by calendar date in [zone]); never below 0. */
internal fun nightsBetween(from: Instant?, to: Instant?, zone: TimeZone): Int? =
    if (from == null || to == null) null else from.localDate(zone).daysUntil(to.localDate(zone)).coerceAtLeast(0)

// ---- Texts that depend on the stay -----------------------------------------------------------------

/** "{n} night(s)" in the preview. */
internal fun extensionText(n: Int): String = "$n night(s)"

internal fun extendedMessage(roomNumber: String, newCheckOut: String): String = "Room $roomNumber now checks out $newCheckOut."

internal fun extendActivityText(staffName: String, guestName: String, roomNumber: String, nights: Int): String =
    "$staffName extended $guestName's stay in Room $roomNumber by $nights night(s)"

internal fun chipText(n: Int): String = if (n == 1) "1 night" else "$n nights"

// ---- What the transaction finds --------------------------------------------------------------------

/** On the booking: null = fine, otherwise the message that stops the extension. */
internal fun stayCheckError(exists: Boolean, status: String?): String? = when {
    !exists -> MSG_BOOKING_MISSING
    status != "checked_in" -> MSG_EXTEND_NOT_CHECKED_IN
    else -> null
}

/**
 * On the room: it must exist, and when it names a booking that must be this one.
 * A room that names no booking at all is not taken by someone else, so it passes.
 */
internal fun roomAssignmentError(roomExists: Boolean, currentBookingId: String?, bookingDocId: String): String? = when {
    !roomExists -> MSG_ROOM_MISSING
    !currentBookingId.isNullOrBlank() && currentBookingId != bookingDocId -> MSG_ROOM_REASSIGNED
    else -> null
}
