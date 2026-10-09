package com.westly.nbms.features.checkin

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** When the guest pays the room charge. */
enum class PaymentOption(val key: String, val label: String, val help: String) {
    PAY_AT_CHECKIN(
        "pay_at_checkin",
        "Pay at Check-In",
        "Payment is recorded now and sent to the Accountant for approval."
    ),
    PAY_AT_CHECKOUT(
        "pay_at_checkout",
        "Pay at Check-Out",
        "No payment is recorded now — the booking is marked Payment Pending and charged at check-out."
    )
}

/** How the guest pays. The stored key is the lower-case snake_case text. */
enum class PaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    CREDIT_CARD("credit_card", "Credit Card"),
    DEBIT_CARD("debit_card", "Debit Card"),
    BANK_TRANSFER("bank_transfer", "Bank Transfer")
}

/** Everything typed on the Check-In page. Dates and times are in the business time zone. */
data class WalkInForm(
    val fullName: String = "",
    val phone: String = "",
    val email: String = "",
    val nationality: String = "",
    val idDocumentRef: String = "",
    val roomId: String? = null,
    val checkInDate: LocalDate? = null,
    val checkInTime: LocalTime? = null,
    val checkOutDate: LocalDate? = null,
    val adults: Int = 1,
    val children: Int = 0,
    val paymentOption: PaymentOption = PaymentOption.PAY_AT_CHECKIN,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val notes: String = ""
) {
    companion object {
        /** A fresh form: check-in = now, check-out = tomorrow, everything else at its default. */
        fun initial(now: Instant, zone: TimeZone): WalkInForm {
            val local = now.toLocalDateTime(zone)
            return WalkInForm(
                checkInDate = local.date,
                checkInTime = LocalTime(local.hour, local.minute),
                checkOutDate = local.date.plus(DatePeriod(days = 1))
            )
        }
    }
}

/** Only the part of `settings/hotel` this page needs (a private copy so no other phase's model is imported). */
data class WalkInHotelSettings(val checkOutTime: String = DEFAULT_CHECK_OUT_TIME)

/** The signed-in staff member who registers the guest. */
data class WalkInStaff(val uid: String, val name: String)

/** What the success screen shows. */
data class WalkInSuccess(
    val guestName: String,
    val roomNumber: String,
    val checkOut: Instant,
    val paidNow: Boolean,
    val total: Double
)

/** The four new document ids of one walk-in. The booking id is also the id of its `booking_dates` lock. */
data class WalkInIds(
    val guestId: String,
    val bookingId: String,
    val checkinId: String,
    val paymentId: String
)

/** Stands for "the server's clock" inside the fields below; the real store turns it into a Firestore server timestamp. */
internal object WalkInServerTime

/** Every document the one transaction writes. [payment] is null when the guest pays at check-out. */
data class WalkInWrites(
    val ids: WalkInIds,
    val roomId: String,
    val guest: Map<String, Any?>,
    val booking: Map<String, Any?>,
    val bookingDate: Map<String, Any?>,
    val checkin: Map<String, Any?>,
    val room: Map<String, Any?>,
    val payment: Map<String, Any?>?
)

/** A problem with a clear sentence for the person at the desk (shown in the "Walk-In Failed" toast). */
class WalkInException(message: String) : Exception(message)

/** What one submit gives back to the screen. */
sealed interface WalkInResult {
    data class Done(val success: WalkInSuccess) : WalkInResult

    /** Nothing was saved (or saving failed); show a toast with [title] and [message]. */
    data class Rejected(val title: String, val message: String) : WalkInResult
}
