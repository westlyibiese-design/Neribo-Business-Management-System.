package com.westly.nbms.features.checkin

import com.google.firebase.Timestamp
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.Validators
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import com.westly.nbms.features.rooms.Room
import kotlin.random.Random

// ---- Texts (copied from Westly) --------------------------------------------------------------------

const val DEFAULT_CHECK_OUT_TIME = "11:00"

internal const val TITLE_OFFLINE = "You're Offline"
internal const val MSG_OFFLINE = "No internet connection detected. Please reconnect and try again."
internal const val TITLE_ROOM_NOT_AVAILABLE = "Room Not Available"
internal const val TITLE_FAILED = "Walk-In Failed"
internal const val MSG_BAD_CHECK_IN = "Please enter a valid check-in date and time."
internal const val MSG_CHECK_OUT_AFTER = "Check-out must be after check-in."
internal const val TITLE_CONNECTION_LOST = "Connection Lost"
internal const val MSG_CONNECTION_LOST = "Still trying to save — this may take a moment while your connection recovers."
internal const val MSG_CONFLICT_TIMEOUT = "Couldn't verify room availability — please check your connection and try again."
internal const val MSG_ALREADY_BOOKED = "This room is already booked for the selected dates."
internal const val MSG_SAVE_TIMEOUT = "Saving took too long — please check your connection and try again."
internal const val MSG_ROOM_MISSING = "Room not found."
internal const val MSG_ROOM_TAKEN = "Room is no longer available."
internal const val MSG_GENERIC = "Something went wrong. Please try again."
internal const val TITLE_COMPLETE = "Walk-In Complete!"

internal const val MSG_NAME_REQUIRED = "Full name is required."
internal const val MSG_PHONE_REQUIRED = "Phone number is required."
internal const val MSG_EMAIL_INVALID = "Enter a valid email address."

internal const val MSG_ROOMS_LOAD_ERROR = "Couldn't load rooms. Check your connection and reload."
internal const val MSG_NO_ROOMS = "No rooms found."
internal const val MSG_NONE_AVAILABLE = "No rooms are currently available — all rooms are occupied, cleaning, or under maintenance."

/** Longest wait for the room-availability check. */
internal const val CONFLICT_TIMEOUT_MS = 15_000L

/** Longest wait for the one transaction. */
internal const val TRANSACTION_TIMEOUT_MS = 20_000L

/** Longest wait for each best-effort step after the commit. */
internal const val POST_STEP_TIMEOUT_MS = 8_000L

/** Pause before a shared-device (PIN) session is ended on the success screen. */
internal const val PIN_SIGN_OUT_DELAY_MS = 2_500L

val ADULT_OPTIONS: List<Int> = listOf(1, 2, 3, 4)
val CHILD_OPTIONS: List<Int> = listOf(0, 1, 2, 3)

// ---- Small rules -----------------------------------------------------------------------------------

/** "WI-" + the first 6 characters of the booking document id, in capitals. */
internal fun bookingCode(id: String): String = "WI-" + id.take(6).uppercase()

/** Nights between the check-in DATE and the check-out date; never fewer than 1. */
internal fun stayNights(checkInDate: LocalDate, checkOutDate: LocalDate): Int =
    Format.nights(checkInDate, checkOutDate).coerceAtLeast(1)

internal fun stayTotal(price: Double, nights: Int): Double = price * nights

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

/** "Check-out time will be recorded as 11:00, the hotel's official check-out time (set by the Super Admin in Settings)." */
internal fun checkOutCaption(officialTime: String): String =
    "Check-out time will be recorded as $officialTime, the hotel's official check-out time (set by the Super Admin in Settings)."

// ---- Validation ------------------------------------------------------------------------------------

sealed interface StayValidation {
    data class Valid(val checkIn: Instant, val checkOut: Instant, val nights: Int) : StayValidation
    data class Invalid(val message: String) : StayValidation
}

/**
 * Step 4 of the submit: a valid check-in date and time, and a check-out (date + the official check-out time)
 * that is strictly after it.
 */
internal fun validateStay(
    checkInDate: LocalDate?,
    checkInTime: LocalTime?,
    checkOutDate: LocalDate?,
    officialCheckOutTime: String?,
    zone: TimeZone
): StayValidation {
    if (checkInDate == null || checkInTime == null) return StayValidation.Invalid(MSG_BAD_CHECK_IN)
    if (checkOutDate == null) return StayValidation.Invalid(MSG_CHECK_OUT_AFTER)
    val checkIn = LocalDateTime(checkInDate, checkInTime).toInstant(zone)
    val checkOut = combineDateAndTime(checkOutDate, officialCheckOutTime, zone)
    if (checkOut <= checkIn) return StayValidation.Invalid(MSG_CHECK_OUT_AFTER)
    return StayValidation.Valid(checkIn, checkOut, stayNights(checkInDate, checkOutDate))
}

/** Messages under the fields (Westly's "required" fields). A null entry means the field is fine. */
data class WalkInFieldErrors(val name: String?, val phone: String?, val email: String?) {
    val any: Boolean get() = name != null || phone != null || email != null
}

internal fun fieldErrors(form: WalkInForm): WalkInFieldErrors = WalkInFieldErrors(
    name = if (form.fullName.isBlank()) MSG_NAME_REQUIRED else null,
    phone = if (form.phone.isBlank()) MSG_PHONE_REQUIRED else null,
    email = form.email.trim().takeIf { it.isNotEmpty() }?.let { if (Validators.email(it)) null else MSG_EMAIL_INVALID }
)

// ---- Rooms -----------------------------------------------------------------------------------------

/** Statuses are compared as plain text, exactly like Westly. */
internal fun isAvailable(room: Room?): Boolean = room != null && room.status == "available"

/** The submit button: not busy, a room chosen, and that room `available`. */
internal fun canSubmit(busy: Boolean, room: Room?): Boolean = !busy && isAvailable(room)

/** "Room 101 is currently occupied. Please choose a different room." */
internal fun roomNotAvailableMessage(roomNumber: String, status: String): String =
    "Room $roomNumber is currently ${status.replace('_', ' ')}. Please choose a different room."

/** Rooms without the deleted ones, in natural number order (2 before 10). */
internal fun listedRooms(all: List<Room>): List<Room> =
    all.filter { !it.isDeleted }.sortedWith(compareBy<Room> { it.number.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number.lowercase() })

/** The small note under the room picker; null when there is nothing to say. */
internal fun roomsNotice(loadFailed: Boolean, loaded: Boolean, rooms: List<Room>): String? = when {
    loadFailed -> MSG_ROOMS_LOAD_ERROR
    !loaded -> null
    rooms.isEmpty() -> MSG_NO_ROOMS
    rooms.none { it.status == "available" } -> MSG_NONE_AVAILABLE
    else -> null
}

/** What the transaction finds on the room: null = fine, otherwise the message that stops the check-in. */
internal fun roomCheckError(exists: Boolean, status: String?): String? = when {
    !exists -> MSG_ROOM_MISSING
    status != "available" -> MSG_ROOM_TAKEN
    else -> null
}

// ---- Texts that depend on the guest ----------------------------------------------------------------

internal fun completeMessage(name: String, paidNow: Boolean): String =
    if (paidNow) "$name is now checked in. Payment sent to the Accountant for approval."
    else "$name is now checked in. Payment will be collected at check-out."

internal fun activityText(guest: String, roomNumber: String): String = "Walk-in: $guest → Room $roomNumber"

/** "₦25,000 × 2 nights" / "₦25,000 × 1 night". */
internal fun priceLine(price: Double, nights: Int, symbol: String): String =
    "${Format.currency(price, symbol)} × $nights ${if (nights == 1) "night" else "nights"}"

internal fun String.orNullIfBlank(): String? = trim().takeIf { it.isNotEmpty() }

// ---- The documents of the transaction --------------------------------------------------------------

internal fun Instant.toFirebase(): Timestamp = Timestamp(epochSeconds, nanosecondsOfSecond)

/**
 * Builds every document the one transaction writes (six when the guest pays now, five otherwise).
 * Times that must come from the server are [WalkInServerTime].
 */
internal fun buildWalkInWrites(
    ids: WalkInIds,
    form: WalkInForm,
    room: Room,
    staff: WalkInStaff,
    checkIn: Instant,
    checkOut: Instant,
    nights: Int
): WalkInWrites {
    val guestName = form.fullName.trim()
    val email = form.email.orNullIfBlank()
    val phone = form.phone.orNullIfBlank()
    val idDocumentRef = form.idDocumentRef.orNullIfBlank()
    val notes = form.notes.orNullIfBlank()
    val payAtCheckIn = form.paymentOption == PaymentOption.PAY_AT_CHECKIN
    val total = stayTotal(room.price, nights)
    val checkInStamp = checkIn.toFirebase()
    val checkOutStamp = checkOut.toFirebase()

    val guest = mapOf(
        "name" to guestName,
        "email" to email,
        "phone" to phone,
        "nationality" to form.nationality.orNullIfBlank(),
        "idDocumentRef" to idDocumentRef,
        "firstVisit" to WalkInServerTime,
        "totalStays" to 1,
        "isDeleted" to false
    )
    val booking = mapOf(
        "bookingId" to bookingCode(ids.bookingId),
        "guestId" to ids.guestId,
        "guestName" to guestName,
        "guestEmail" to email,
        "guestPhone" to phone,
        "roomId" to room.id,
        "roomNumber" to room.number,
        "roomType" to room.type,
        "checkIn" to checkInStamp,
        "checkOut" to checkOutStamp,
        "checkInAt" to checkInStamp,
        "nights" to nights,
        "adults" to form.adults,
        "children" to form.children,
        "totalAmount" to total,
        "paymentMethod" to form.paymentMethod.key,
        "paymentOption" to form.paymentOption.key,
        "roomPaymentStatus" to if (payAtCheckIn) "paid" else "pending",
        "status" to "checked_in",
        "source" to "walk_in",
        "createdAt" to WalkInServerTime,
        "createdBy" to staff.uid,
        "createdByName" to staff.name,
        "notes" to notes,
        "isDeleted" to false
    )
    val bookingDate = mapOf(
        "roomId" to room.id,
        "checkIn" to checkInStamp,
        "checkOut" to checkOutStamp,
        "status" to "checked_in"
    )
    val checkin = mapOf(
        "bookingId" to ids.bookingId,
        "roomId" to room.id,
        "roomNumber" to room.number,
        "guestName" to guestName,
        "checkInTime" to WalkInServerTime,
        "checkInAt" to checkInStamp,
        "expectedCheckOutAt" to checkOutStamp,
        "staffId" to staff.uid,
        "staffName" to staff.name,
        "idDocumentRef" to idDocumentRef,
        "notes" to notes,
        "isDeleted" to false
    )
    val roomUpdate = mapOf(
        "status" to "occupied",
        "currentGuest" to guestName,
        "currentBookingId" to ids.bookingId,
        "statusUpdatedAt" to WalkInServerTime,
        "cleanliness" to "clean",
        "cleanlinessUpdatedAt" to WalkInServerTime,
        "checkoutOverdue" to false
    )
    val payment = if (!payAtCheckIn) null else mapOf(
        "bookingId" to ids.bookingId,
        "guestName" to guestName,
        "roomNumber" to room.number,
        "amount" to total,
        "paymentMethod" to form.paymentMethod.key,
        "type" to "walk_in_payment",
        "recordedBy" to staff.uid,
        "recordedByName" to staff.name,
        "createdAt" to WalkInServerTime,
        "approvalStatus" to "pending",
        "approvedBy" to null,
        "approvedByName" to null,
        "approvedAt" to null,
        "rejectedReason" to null,
        "isDeleted" to false
    )
    return WalkInWrites(ids, room.id, guest, booking, bookingDate, checkin, roomUpdate, payment)
}

// ---- Activity feed key -----------------------------------------------------------------------------

private const val PUSH_ALPHABET = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

/**
 * A key shaped like the ones Firebase's own `push()` makes (8 characters of time + 12 random ones), so the
 * activity feed keeps its time order. The shared realtime API has no `push()`, so this page builds the key itself.
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
