package com.westly.nbms.features.checkout

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// ---- Filters ---------------------------------------------------------------------------------------

/** The chips under "Filter by due checkout date". */
enum class DueFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    SPECIFIC("Specific Date"),
    RANGE("Date Range")
}

/** Search text, due-date chip and its dates, as typed on the Check Out page. */
data class CheckOutFilters(
    val query: String = "",
    val due: DueFilter = DueFilter.ALL,
    val specificDate: LocalDate,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate
) {
    /** True when the list is being narrowed (search text typed or a chip other than All chosen). */
    val active: Boolean get() = query.isNotBlank() || due != DueFilter.ALL

    companion object {
        /** Search empty, "All" chosen, and all three date pickers on [today]. */
        fun initial(today: LocalDate) = CheckOutFilters(specificDate = today, rangeStart = today, rangeEnd = today)
    }
}

// ---- Timing ----------------------------------------------------------------------------------------

enum class TimingKind { EARLY, ON_TIME, LATE }

/** How the actual check-out compares with the scheduled one. [hours] is rounded to one decimal. */
data class CheckoutTiming(val kind: TimingKind, val hours: Double)

// ---- The confirm dialog ----------------------------------------------------------------------------

/** How the guest pays at check-out. The stored key is the lower-case snake_case text. */
enum class CheckOutPaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    CREDIT_CARD("credit_card", "Credit Card"),
    DEBIT_CARD("debit_card", "Debit Card"),
    BANK_TRANSFER("bank_transfer", "Bank Transfer"),
    MOBILE_PAYMENT("mobile_payment", "Mobile Payment")
}

/** Everything typed in the Confirm Check-Out dialog. Date and time are in the business time zone. */
data class CheckOutDraft(
    val actualDate: LocalDate? = null,
    val actualTime: LocalTime? = null,
    val extrasText: String = "",
    val method: CheckOutPaymentMethod = CheckOutPaymentMethod.CASH,
    val notes: String = ""
) {
    /** The chosen moment, or null while a date or a time is missing. */
    fun actualAt(zone: TimeZone): Instant? {
        val date = actualDate ?: return null
        val time = actualTime ?: return null
        return LocalDateTime(date, time).toInstant(zone)
    }

    companion object {
        /** A fresh draft that starts at right now (not at the policy time). */
        fun startingAt(now: Instant, zone: TimeZone): CheckOutDraft {
            val local = now.toLocalDateTime(zone)
            return CheckOutDraft(actualDate = local.date, actualTime = LocalTime(local.hour, local.minute))
        }
    }
}

/** The signed-in staff member who processes the check-out. */
data class CheckOutStaff(val uid: String, val name: String)

/** What the repository needs besides the booking. */
data class CheckOutInput(
    /** Null when the date or time was left empty. */
    val actualAt: Instant?,
    val extraCharges: Double,
    val method: CheckOutPaymentMethod,
    val notes: String,
    val staff: CheckOutStaff,
    val zone: TimeZone,
    /** `settings/hotel.checkOutTime` ("11:00" when unknown). */
    val officialCheckOutTime: String,
    val businessName: String,
    val currencySymbol: String
)

// ---- What the transaction reads and writes ---------------------------------------------------------

/** One entry of `bookings/{id}.extensionHistory`, as read back for the receipt. */
data class ExtensionRecord(
    val nightsAdded: Int,
    val amount: Double,
    val newCheckOut: Instant?
)

/** The booking exactly as the transaction finds it (the live document, not the list on screen). */
data class LiveBooking(
    val id: String,
    val bookingId: String?,
    val status: String?,
    val guestName: String,
    val guestEmail: String?,
    val guestPhone: String?,
    val roomId: String,
    val roomNumber: String,
    val roomType: String?,
    val checkIn: Instant?,
    val checkInAt: Instant?,
    val checkOut: Instant?,
    val nights: Int?,
    val totalAmount: Double,
    val paymentMethod: String?,
    val roomPaymentStatus: String?,
    val extensions: List<ExtensionRecord> = emptyList()
)

/** The two new document ids of a check-out (the checkout record and, when money is due, the payment). */
data class CheckOutIds(val checkoutId: String, val paymentId: String)

/** Stands for "the server's clock" inside the maps below; the real store turns it into a Firestore server timestamp. */
internal object CheckOutServerTime

/** The money and times a check-out worked out; the success screen and the receipt show them. */
data class CheckOutSummary(
    val finalAmount: Double,
    val extraCharges: Double,
    val alreadyPaid: Boolean,
    val amountToCharge: Double,
    val paymentMethodKey: String,
    val scheduledAt: Instant,
    val actualAt: Instant,
    val timing: CheckoutTiming
)

/** Every document the one transaction writes. [room] and [payment] are null when they are not written. */
data class CheckOutWrites(
    val ids: CheckOutIds,
    val bookingDocId: String,
    val roomId: String?,
    val booking: Map<String, Any?>,
    val bookingDate: Map<String, Any?>,
    val room: Map<String, Any?>?,
    val checkout: Map<String, Any?>,
    val payment: Map<String, Any?>?,
    val summary: CheckOutSummary
)

/** What a committed transaction hands back. */
data class CheckOutCommit(val live: LiveBooking, val writes: CheckOutWrites)

/** A problem with a clear sentence for the person at the desk (shown in the "Check-Out Failed" toast). */
class CheckOutException(message: String) : Exception(message)

/** What the success screen shows. */
data class CheckOutSuccess(
    val guestName: String,
    val roomNumber: String,
    val summary: CheckOutSummary,
    val receipt: ReceiptData
)

/** What one check-out gives back to the screen. */
sealed interface CheckOutResult {
    data class Done(val success: CheckOutSuccess) : CheckOutResult

    /** Nothing was saved (or saving failed); show a toast with [title] and [message]. */
    data class Rejected(val title: String, val message: String) : CheckOutResult
}
