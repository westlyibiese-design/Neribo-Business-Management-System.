package com.westly.nbms.features.reservations

import com.google.firebase.Timestamp
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.datetime.Instant
import kotlin.random.Random

// Pure builders for everything the reservations repository writes. Times that must come from the server are
// passed in as [serverTime] (call sites pass FieldValue.serverTimestamp(); tests pass a marker).

internal const val MSG_BOOKING_NOT_FOUND = "Booking not found."
internal const val MSG_ALREADY_CHECKED_IN = "Guest is already checked in."
internal const val MSG_CANCELLED_OR_REJECTED = "Cannot check in a cancelled or rejected booking."
internal const val MSG_NO_ENTITLED = "Could not compute the entitled check-out date. Please try again."

/** Epoch milliseconds -> Firebase Timestamp. */
internal fun Instant.toFirebaseTimestamp(): Timestamp {
    val ms = toEpochMilliseconds()
    return Timestamp(Math.floorDiv(ms, 1000L), Math.floorMod(ms, 1000L).toInt() * 1_000_000)
}

// ---- Status change ---------------------------------------------------------------------------------

/** The only statuses the Room Reservations screen may set. */
internal val CHANGEABLE_STATUSES: Set<BookingStatus> =
    setOf(BookingStatus.CONFIRMED, BookingStatus.REJECTED, BookingStatus.CANCELLED, BookingStatus.NO_SHOW)

internal fun buildStatusChangeBookingUpdate(
    newStatus: BookingStatus, staffId: String, staffName: String, serverTime: Any
): Map<String, Any?> = mapOf(
    "status" to newStatus.key,
    "updatedAt" to serverTime,
    "updatedBy" to staffId,
    "updatedByName" to staffName
)

internal fun buildStatusChangeBookingDates(booking: Booking, newStatus: BookingStatus): Map<String, Any?> = mapOf(
    "roomId" to booking.roomId,
    "checkIn" to booking.checkIn,
    "checkOut" to booking.checkOut,
    "status" to newStatus.key
)

// ---- Check-in --------------------------------------------------------------------------------------

/** What the transaction finds on the booking: null = fine, otherwise the exact message that stops the check-in. */
internal fun checkInGuardMessage(exists: Boolean, status: String?): String? = when {
    !exists -> MSG_BOOKING_NOT_FOUND
    status == "checked_in" -> MSG_ALREADY_CHECKED_IN
    status == "cancelled" || status == "rejected" -> MSG_CANCELLED_OR_REJECTED
    else -> null
}

/** The room document id: the booking's roomId, or the booking id when it has none (as Westly did). */
internal fun checkInRoomId(booking: Booking): String = booking.roomId.ifBlank { booking.id }

internal fun isPayNow(form: CheckInForm): Boolean = form.paymentOption == PaymentOption.PAY_AT_CHECK_IN

/** `nights` and `totalAmount` are deliberately absent. */
internal fun buildCheckInBookingUpdate(
    form: CheckInForm,
    entitledCheckOut: Instant,
    staffId: String,
    staffName: String,
    existingPaymentMethod: String?,
    serverTime: Any
): Map<String, Any?> {
    val payNow = isPayNow(form)
    return mapOf(
        "status" to "checked_in",
        "checkInAt" to form.checkInAt.toFirebaseTimestamp(),
        "checkOut" to entitledCheckOut.toFirebaseTimestamp(),
        "checkedInBy" to staffId,
        "checkedInByName" to staffName,
        "idDocumentRef" to form.idDocumentRef,
        "checkInNotes" to form.notes,
        "paymentOption" to form.paymentOption.key,
        "paymentMethod" to (if (payNow) form.paymentMethod.key else existingPaymentMethod),
        "roomPaymentStatus" to (if (payNow) "paid" else "pending"),
        "updatedAt" to serverTime
    )
}

internal fun buildCheckInBookingDates(booking: Booking, entitledCheckOut: Instant): Map<String, Any?> = mapOf(
    "roomId" to booking.roomId,
    "checkIn" to booking.checkIn,
    "checkOut" to entitledCheckOut.toFirebaseTimestamp(),
    "status" to "checked_in"
)

internal fun buildCheckInRoomUpdate(booking: Booking, serverTime: Any): Map<String, Any?> = mapOf(
    "status" to "occupied",
    "currentGuest" to booking.guestName,
    "currentBookingId" to booking.id,
    "statusUpdatedAt" to serverTime,
    "cleanliness" to "clean",
    "cleanlinessUpdatedAt" to serverTime,
    "checkoutOverdue" to false
)

internal fun buildCheckInDocument(
    booking: Booking,
    form: CheckInForm,
    entitledCheckOut: Instant,
    staffId: String,
    staffName: String,
    serverTime: Any
): Map<String, Any?> = mapOf(
    "bookingId" to booking.id,
    "roomId" to booking.roomId,
    "roomNumber" to booking.roomNumber,
    "guestName" to booking.guestName,
    "guestEmail" to booking.guestEmail,
    "guestPhone" to booking.guestPhone,
    "idDocumentRef" to form.idDocumentRef,
    "checkInTime" to serverTime,
    "checkInAt" to form.checkInAt.toFirebaseTimestamp(),
    "entitledCheckOutAt" to entitledCheckOut.toFirebaseTimestamp(),
    "staffId" to staffId,
    "staffName" to staffName,
    "notes" to form.notes,
    "isDeleted" to false
)

/** The payment document, or null when the guest pays at check-out. */
internal fun buildCheckInPayment(
    booking: Booking,
    form: CheckInForm,
    staffId: String,
    staffName: String,
    serverTime: Any
): Map<String, Any?>? {
    if (!isPayNow(form)) return null
    return mapOf(
        "bookingId" to booking.id,
        "guestName" to booking.guestName,
        "roomNumber" to booking.roomNumber,
        "amount" to booking.totalAmount,
        "paymentMethod" to form.paymentMethod.key,
        "type" to "room_payment",
        "recordedBy" to staffId,
        "recordedByName" to staffName,
        "createdAt" to serverTime,
        "approvalStatus" to "pending",
        "approvedBy" to null,
        "approvedByName" to null,
        "approvedAt" to null,
        "rejectedReason" to null,
        "isDeleted" to false
    )
}

internal fun buildRoomStatusRealtime(guestName: String, nowMillis: Long): Map<String, Any?> = mapOf(
    "status" to "occupied",
    "currentGuest" to guestName,
    "updatedAt" to nowMillis
)

internal fun buildCheckInActivityItem(staffName: String, guestName: String, roomNumber: String, nowMillis: Long): Map<String, Any?> = mapOf(
    "type" to "check_in",
    "text" to "$staffName checked in $guestName (Room $roomNumber)",
    "at" to nowMillis,
    "by" to staffName
)

internal fun buildCheckInAuditBefore(oldCheckOut: Timestamp?): Map<String, Any?> = mapOf(
    "status" to "confirmed",
    "checkOut" to oldCheckOut
)

internal fun buildCheckInAuditAfter(checkInAt: Instant, entitledCheckOut: Instant): Map<String, Any?> = mapOf(
    "status" to "checked_in",
    "checkInAt" to checkInAt.toFirebaseTimestamp(),
    "checkOut" to entitledCheckOut.toFirebaseTimestamp()
)

private const val PUSH_ALPHABET = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

/** A 20-character, time-ordered id in the style of Realtime Database push ids. */
internal fun newActivityPushId(nowMillis: Long, random: Random = Random.Default): String {
    val sb = StringBuilder(20)
    var t = nowMillis
    val stamp = CharArray(8)
    for (i in 7 downTo 0) {
        stamp[i] = PUSH_ALPHABET[(t % 64).toInt()]
        t /= 64
    }
    sb.append(stamp)
    repeat(12) { sb.append(PUSH_ALPHABET[random.nextInt(64)]) }
    return sb.toString()
}
