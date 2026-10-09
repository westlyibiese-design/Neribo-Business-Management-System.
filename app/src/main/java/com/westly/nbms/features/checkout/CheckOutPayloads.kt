package com.westly.nbms.features.checkout

import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.Instant

/**
 * Builds every document the one check-out transaction writes (five when money is due now, four when not; the
 * room update is left out only when the booking has no room id). Times that must come from the server are
 * [CheckOutServerTime].
 *
 * [live] is the booking as the transaction found it, so the money below is always worked out from the live
 * document. [actual] is the moment the guest left (never the policy time).
 */
internal fun buildCheckOutWrites(
    ids: CheckOutIds,
    live: LiveBooking,
    input: CheckOutInput,
    actual: Instant
): CheckOutWrites {
    val scheduled = scheduledCheckOutAt(live.checkOut, input.officialCheckOutTime, input.zone) ?: actual
    val extras = input.extraCharges.coerceAtLeast(0.0)
    val finalAmt = finalAmount(live.totalAmount, extras)
    val alreadyPaid = isRoomPaid(live.roomPaymentStatus)
    val amountToCharge = if (alreadyPaid) extras else finalAmt
    val chosen = input.method.key
    val recordedMethod = if (amountToCharge > 0.0) chosen else (live.paymentMethod.orNullIfBlank() ?: chosen)
    val notes = input.notes.orNullIfBlank()
    val roomId = live.roomId.takeIf { it.isNotBlank() }
    val actualStamp = actual.toFirebase()
    val scheduledStamp = scheduled.toFirebase()
    val staff = input.staff

    val booking = mapOf(
        "status" to "checked_out",
        "checkOutAt" to actualStamp,
        "checkOutServerAt" to CheckOutServerTime,
        "scheduledCheckOutAt" to scheduledStamp,
        "checkedOutBy" to staff.uid,
        "checkedOutByName" to staff.name,
        "finalAmount" to finalAmt,
        "extraCharges" to extras,
        "paymentMethod" to recordedMethod,
        "roomPaymentStatus" to "paid",
        "checkOutNotes" to notes,
        "updatedAt" to CheckOutServerTime,
        "lastOverdueNotifiedAt" to null
    )

    val bookingDate = buildMap<String, Any?> {
        put("roomId", live.roomId)
        live.checkIn?.let { put("checkIn", it.toFirebase()) }
        live.checkOut?.let { put("checkOut", it.toFirebase()) }
        put("status", "checked_out")
    }

    val room = if (roomId == null) null else mapOf(
        "status" to "cleaning",
        "currentGuest" to null,
        "currentBookingId" to null,
        "statusUpdatedAt" to CheckOutServerTime,
        "cleanliness" to "dirty",
        "cleanlinessUpdatedAt" to CheckOutServerTime,
        "checkoutOverdue" to false,
        "cleaningReminderLastSentAt" to null
    )

    val checkout = mapOf(
        "bookingId" to live.id,
        "roomId" to roomId,
        "roomNumber" to live.roomNumber,
        "guestName" to live.guestName,
        "guestEmail" to live.guestEmail.orNullIfBlank(),
        "checkOutTime" to CheckOutServerTime,
        "checkOutAt" to actualStamp,
        "scheduledCheckOutAt" to scheduledStamp,
        "baseAmount" to live.totalAmount,
        "extraCharges" to extras,
        "finalAmount" to finalAmt,
        "roomChargedAtCheckout" to !alreadyPaid,
        "amountChargedNow" to amountToCharge,
        "paymentMethod" to recordedMethod,
        "staffId" to staff.uid,
        "staffName" to staff.name,
        "notes" to notes,
        "isDeleted" to false
    )

    val payment = if (amountToCharge > 0.0) {
        mapOf(
            "bookingId" to live.id,
            "guestName" to live.guestName,
            "roomNumber" to live.roomNumber,
            "amount" to amountToCharge,
            "paymentMethod" to chosen,
            "type" to "room_payment",
            "recordedBy" to staff.uid,
            "recordedByName" to staff.name,
            "createdAt" to CheckOutServerTime,
            "approvalStatus" to "pending",
            "approvedBy" to null,
            "approvedByName" to null,
            "approvedAt" to null,
            "rejectedReason" to null,
            "isDeleted" to false
        )
    } else null

    val summary = CheckOutSummary(
        finalAmount = finalAmt,
        extraCharges = extras,
        alreadyPaid = alreadyPaid,
        amountToCharge = amountToCharge,
        paymentMethodKey = recordedMethod,
        scheduledAt = scheduled,
        actualAt = actual,
        timing = checkoutTiming(actual, scheduled)
    )

    return CheckOutWrites(ids, live.id, roomId, booking, bookingDate, room, checkout, payment, summary)
}

/** The booking as the list shows it, as a [LiveBooking] (used by the unit tests' fake store). */
internal fun Booking.toLive(): LiveBooking = LiveBooking(
    id = id,
    bookingId = bookingId,
    status = status,
    guestName = guestName,
    guestEmail = guestEmail,
    guestPhone = guestPhone,
    roomId = roomId,
    roomNumber = roomNumber,
    roomType = roomType,
    checkIn = checkIn.asInstant(),
    checkInAt = checkInAt.asInstant(),
    checkOut = checkOut.asInstant(),
    nights = nights,
    totalAmount = totalAmount,
    paymentMethod = paymentMethod,
    roomPaymentStatus = roomPaymentStatus
)
