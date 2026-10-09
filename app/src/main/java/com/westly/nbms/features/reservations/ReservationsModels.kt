package com.westly.nbms.features.reservations

import kotlinx.datetime.Instant

/** The chips on the Room Reservations screen, in this order. The label is shown as-is. */
enum class ReservationFilter(val key: String, val label: String) {
    ALL("all", "All"), PENDING("pending", "pending"), CONFIRMED("confirmed", "confirmed"),
    CHECKED_IN("checked_in", "checked in"), CHECKED_OUT("checked_out", "checked out"), CANCELLED("cancelled", "cancelled")
}

enum class ReservationAction { CONFIRM_ARRIVAL, REJECT, CHECK_IN, CANCEL, NO_SHOW }

enum class PaymentOption(val key: String, val label: String) {
    PAY_AT_CHECK_IN("pay_at_check_in", "Pay at Check-In"),
    PAY_AT_CHECK_OUT("pay_at_check_out", "Pay at Check-Out")
}

enum class PaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"), CREDIT_CARD("credit_card", "Credit Card"),
    DEBIT_CARD("debit_card", "Debit Card"), BANK_TRANSFER("bank_transfer", "Bank Transfer")
}

data class CheckInForm(
    val checkInAt: Instant,
    val idDocumentRef: String?,          // null when blank
    val paymentOption: PaymentOption,
    val paymentMethod: PaymentMethod,    // only used when paymentOption == PAY_AT_CHECK_IN
    val notes: String?                   // null when blank
)

data class CheckInOutcome(
    val guestName: String, val roomNumber: String, val roomType: String?,
    val checkInAt: Instant, val entitledCheckOut: Instant,
    val totalAmount: Double, val paidNow: Boolean
)

/** Failure whose message is already the exact user-facing text (e.g. "Booking not found."). */
class ReservationException(message: String) : Exception(message)
