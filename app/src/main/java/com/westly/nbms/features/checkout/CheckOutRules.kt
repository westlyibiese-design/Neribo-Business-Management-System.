package com.westly.nbms.features.checkout

import com.google.firebase.Timestamp
import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.random.Random

// ---- Texts (copied from Westly) --------------------------------------------------------------------

const val DEFAULT_CHECK_OUT_TIME = "11:00"

internal const val TITLE_OFFLINE = "You're Offline"
internal const val MSG_OFFLINE = "No internet connection detected. Please reconnect and try again."
internal const val TITLE_CHECKOUT_FAILED = "Check-Out Failed"
internal const val TITLE_CHECKOUT_COMPLETE = "Check-Out Complete"
internal const val MSG_BAD_DATE = "Please enter a valid check-out date and time."
internal const val MSG_BEFORE_CHECK_IN = "Check-out time cannot be before the guest's check-in time."
internal const val MSG_BOOKING_MISSING = "Booking not found."
internal const val MSG_NOT_CHECKED_IN = "Booking is not in checked-in state."
internal const val MSG_SAVE_TIMEOUT = "Saving took too long — please check your connection and try again."
internal const val MSG_GENERIC = "Something went wrong. Please try again."
internal const val MSG_LOAD_ERROR = "We couldn't load checked-in guests."
internal const val MSG_RECEIPT_FAILED = "The receipt could not be created. Please try again."
internal const val TITLE_RECEIPT_FAILED = "Receipt Failed"

internal const val SEARCH_PLACEHOLDER = "Search by guest name, room number, email…"
internal const val MSG_NO_GUESTS = "No guests currently checked in"
internal const val MSG_NO_MATCH = "No guests match this filter"
internal const val MSG_ROOM_ALREADY_PAID =
    "The room charge was already recorded at check-in and won't be charged again — only extra charges (if any) are due now."
internal const val MSG_STILL_WORKING =
    "Still working — please don't refresh or leave this page. This can take longer on a slow connection."
internal const val MSG_PIN_ENDING = "Ending session for security — enter your PIN again to check out another guest."

/** Within this many minutes (either side) of the scheduled time a check-out counts as on time. */
internal const val ON_TIME_TOLERANCE_MINUTES = 15.0

/** Longest wait for the one transaction. */
internal const val TRANSACTION_TIMEOUT_MS = 20_000L

/** Longest wait for each best-effort step after the commit. */
internal const val POST_STEP_TIMEOUT_MS = 8_000L

/** After this long of saving, the "Still working" line appears. */
internal const val SLOW_NOTICE_DELAY_MS = 6_000L

/** Pause before a shared-device (PIN) session is ended on the success screen. */
internal const val PIN_SIGN_OUT_DELAY_MS = 2_500L

// ---- Times -----------------------------------------------------------------------------------------

/** "11:00" -> 11:00. Null for anything that is not a 24-hour HH:mm time. */
internal fun parseClockTime(text: String?): LocalTime? {
    val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(text?.trim().orEmpty()) ?: return null
    return LocalTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

/** [date] at [hhmm] in [zone]. A missing or broken time text falls back to the default check-out time (11:00). */
internal fun combineDateAndTime(date: LocalDate, hhmm: String?, zone: TimeZone): Instant {
    val time = parseClockTime(hhmm) ?: parseClockTime(DEFAULT_CHECK_OUT_TIME)!!
    return LocalDateTime(date, time).toInstant(zone)
}

/** The business time zone; Africa/Lagos when the saved name is missing or unknown. */
internal fun zoneOrLagos(id: String?): TimeZone = try {
    if (id.isNullOrBlank()) TimeZone.of("Africa/Lagos") else TimeZone.of(id)
} catch (e: Exception) {
    TimeZone.of("Africa/Lagos")
}

/** `settings/hotel.checkOutTime`, or "11:00" when it is missing or not a valid time. */
internal fun officialCheckOutTime(saved: String?): String =
    saved?.trim()?.takeIf { parseClockTime(it) != null } ?: DEFAULT_CHECK_OUT_TIME

/**
 * Not named toInstant: a Firestore Timestamp has its own toInstant() (java.time) that would win over an extension.
 * Gives a kotlinx-datetime Instant (null stays null).
 */
internal fun Timestamp?.asInstant(): Instant? =
    this?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()) }

internal fun Instant.toFirebase(): Timestamp = Timestamp(epochSeconds, nanosecondsOfSecond)

internal fun Instant.localDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

/**
 * The scheduled check-out: the booking's check-out DATE (in the business zone) at the hotel's official check-out
 * time. Null when the booking has no check-out. It is worked out on the fly and never saved on the booking.
 */
internal fun scheduledCheckOutAt(checkOut: Instant?, officialTime: String?, zone: TimeZone): Instant? =
    checkOut?.let { combineDateAndTime(it.localDate(zone), officialTime, zone) }

internal fun scheduledCheckOutAt(booking: Booking, officialTime: String?, zone: TimeZone): Instant? =
    scheduledCheckOutAt(booking.checkOut.asInstant(), officialTime, zone)

// ---- Timing ----------------------------------------------------------------------------------------

/**
 * Early, on time or late. Within ±15 minutes is on time. [CheckoutTiming.hours] is
 * round(|difference in minutes| / 60 × 10) / 10.
 */
internal fun checkoutTiming(actual: Instant, scheduled: Instant): CheckoutTiming {
    val diffMinutes = (actual.toEpochMilliseconds() - scheduled.toEpochMilliseconds()) / 60_000.0
    val hours = Math.round(abs(diffMinutes) / 60.0 * 10.0) / 10.0
    val kind = when {
        abs(diffMinutes) <= ON_TIME_TOLERANCE_MINUTES -> TimingKind.ON_TIME
        diffMinutes < 0 -> TimingKind.EARLY
        else -> TimingKind.LATE
    }
    return CheckoutTiming(kind, hours)
}

/** 3.0 -> "3", 2.5 -> "2.5". */
internal fun hoursText(hours: Double): String =
    if (hours % 1.0 == 0.0) hours.toLong().toString() else hours.toString()

/** The coloured line under the date and time pickers. */
internal fun timingLine(timing: CheckoutTiming): String = when (timing.kind) {
    TimingKind.ON_TIME -> "This will be recorded as an on-time check-out."
    TimingKind.EARLY -> "This will be recorded as an early check-out, ${hoursText(timing.hours)}h before the scheduled time."
    TimingKind.LATE -> "This will be recorded as a late check-out, ${hoursText(timing.hours)}h after the scheduled time."
}

/** The pill on the success screen: "Early by 2h", "Late by 0.5h", "On Time". */
internal fun timingPillText(timing: CheckoutTiming): String = when (timing.kind) {
    TimingKind.ON_TIME -> "On Time"
    TimingKind.EARLY -> "Early by ${hoursText(timing.hours)}h"
    TimingKind.LATE -> "Late by ${hoursText(timing.hours)}h"
}

// ---- Money -----------------------------------------------------------------------------------------

/** True when the room charge was already recorded at check-in. Bookings with no status are charged in full. */
internal fun isRoomPaid(roomPaymentStatus: String?): Boolean = roomPaymentStatus == "paid"

/** Room charge plus extras. */
internal fun finalAmount(totalAmount: Double, extras: Double): Double = totalAmount + extras

/** The room charge still due at check-out: nothing when already paid, otherwise the whole room charge. */
internal fun dueAtCheckout(totalAmount: Double, roomPaymentStatus: String?): Double =
    if (isRoomPaid(roomPaymentStatus)) 0.0 else totalAmount

/** What is charged now ("Due Now"): the room charge when unpaid, plus the extras. */
internal fun totalDue(totalAmount: Double, roomPaymentStatus: String?, extras: Double): Double =
    dueAtCheckout(totalAmount, roomPaymentStatus) + extras

/** The Extra Charges field: empty, unreadable or negative text counts as 0. */
internal fun parseExtras(text: String): Double {
    val value = text.trim().toDoubleOrNull() ?: return 0.0
    return if (value.isNaN() || value.isInfinite() || value < 0.0) 0.0 else value
}

/** Keeps digits and the first dot only (the field has no minus sign, so it can never go below 0). */
internal fun sanitizeDecimal(text: String): String {
    val out = StringBuilder()
    var dot = false
    for (c in text) {
        when {
            c in '0'..'9' -> out.append(c)
            c == '.' && !dot -> {
                dot = true
                out.append(c)
            }
        }
    }
    return out.toString()
}

/** "credit_card" -> "Credit Card", "pos" -> "Pos". */
internal fun methodLabel(key: String?): String =
    key.orEmpty().split('_', ' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

// ---- The list --------------------------------------------------------------------------------------

/** Only guests who are checked in right now; deleted bookings are left out. Filtered on the phone, not in the query. */
internal fun checkedInGuests(all: List<Booking>): List<Booking> =
    all.filter { !it.isDeleted && it.status == "checked_in" }

/** Case-insensitive search over guest name, room number and email. */
internal fun matchesSearch(booking: Booking, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return booking.guestName.lowercase().contains(q) ||
        booking.roomNumber.lowercase().contains(q) ||
        booking.guestEmail?.lowercase()?.contains(q) == true
}

/**
 * The due-date chips. Today / Tomorrow compare the day of the scheduled check-out; Specific Date compares the
 * booking's check-out date; Date Range does the same, with both ends included.
 */
internal fun matchesDue(
    booking: Booking,
    filters: CheckOutFilters,
    officialTime: String?,
    zone: TimeZone,
    today: LocalDate
): Boolean {
    if (filters.due == DueFilter.ALL) return true
    val checkOutDate = booking.checkOut.asInstant()?.localDate(zone)
    val scheduledDate = scheduledCheckOutAt(booking, officialTime, zone)?.localDate(zone)
    return when (filters.due) {
        DueFilter.ALL -> true
        DueFilter.TODAY -> scheduledDate == today
        DueFilter.TOMORROW -> scheduledDate == today.plus(DatePeriod(days = 1))
        DueFilter.SPECIFIC -> checkOutDate == filters.specificDate
        DueFilter.RANGE -> checkOutDate != null &&
            checkOutDate >= filters.rangeStart && checkOutDate <= filters.rangeEnd
    }
}

internal fun filterGuests(
    guests: List<Booking>,
    filters: CheckOutFilters,
    officialTime: String?,
    zone: TimeZone,
    today: LocalDate
): List<Booking> = guests.filter { matchesSearch(it, filters.query) && matchesDue(it, filters, officialTime, zone, today) }

/** "{n} guest(s) currently checked in". */
internal fun guestCountText(n: Int): String = "$n guest(s) currently checked in"

/** The first letter of the guest's name, in capitals ("?" for a blank name). */
internal fun guestInitial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

// ---- Texts that depend on the guest ----------------------------------------------------------------

internal fun completeMessage(name: String, charged: Boolean): String =
    if (charged) "$name has checked out. Payment sent to the Accountant for approval."
    else "$name has checked out. Room was already paid at check-in."

internal fun activityText(staffName: String, guestName: String, roomNumber: String): String =
    "$staffName checked out $guestName (Room $roomNumber)"

internal fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

// ---- Errors ----------------------------------------------------------------------------------------

/** What the transaction finds on the booking: null = fine, otherwise the message that stops the check-out. */
internal fun bookingCheckError(exists: Boolean, status: String?): String? = when {
    !exists -> MSG_BOOKING_MISSING
    status != "checked_in" -> MSG_NOT_CHECKED_IN
    else -> null
}

/** Firestore may wrap what a transaction threw; find our own exception inside it. */
internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is T) return current
        current = current.cause
        depth++
    }
    return null
}

// ---- Activity feed key -----------------------------------------------------------------------------

private const val PUSH_ALPHABET = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

/**
 * A key shaped like the ones Firebase's own `push()` makes (8 characters of time + 12 random ones), so the
 * activity feed keeps its time order. The shared realtime API has no `push()`, so this phase builds the key itself.
 */
internal fun newPushId(nowMillis: Long, random: Random = Random.Default): String {
    var remaining = nowMillis
    val time = CharArray(8)
    for (i in 7 downTo 0) {
        time[i] = PUSH_ALPHABET[(remaining % 64).toInt()]
        remaining /= 64
    }
    val tail = CharArray(12) { PUSH_ALPHABET[random.nextInt(64)] }
    return String(time) + String(tail)
}
