package com.westly.nbms.features.checkout

import com.westly.nbms.core.util.Format
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

/** One row of the charges table on the receipt. [note] is a small grey line under the label. */
data class ReceiptLine(val label: String, val amount: Double, val note: String? = null)

/** Everything the guest receipt PDF shows. Times are shown in [zone]. */
data class ReceiptData(
    val businessName: String,
    val receiptNumber: String,
    val producedAt: Instant,
    val zone: TimeZone,
    val currencySymbol: String,
    val guestName: String,
    val guestPhone: String?,
    val roomNumber: String,
    val roomType: String?,
    val checkIn: Instant?,
    val checkOut: Instant,
    val nights: Int?,
    val lines: List<ReceiptLine>,
    val total: Double,
    val paymentMethod: String,
    val paymentStatus: String
)

/** The booking's own code, or the first 8 characters of its document id; always in capitals. */
internal fun receiptNumber(bookingCode: String?, docId: String): String =
    (bookingCode?.trim()?.takeIf { it.isNotEmpty() } ?: docId.take(8)).uppercase()

/** "receipt-WI-AB12CD.pdf" — only letters, digits, dash and underscore are kept from the number. */
internal fun receiptFileName(number: String): String {
    val safe = number.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
    return "receipt-$safe.pdf"
}

/** "3 night(s)" — the same wording the rest of the app uses. */
internal fun nightsText(n: Int): String = "$n night(s)"

/**
 * Builds the receipt from the booking as the transaction found it. The booking's total already contains every
 * extension, so "Room charges" is the total minus the extensions; the rows then add up to the final amount.
 */
internal fun buildReceipt(
    live: LiveBooking,
    summary: CheckOutSummary,
    businessName: String,
    currencySymbol: String,
    zone: TimeZone,
    producedAt: Instant
): ReceiptData {
    val extensionTotal = live.extensions.sumOf { it.amount }
    val extensionNights = live.extensions.sumOf { it.nightsAdded }
    val roomCharges = (live.totalAmount - extensionTotal).coerceAtLeast(0.0)

    val storedNights = live.nights
    val nights = storedNights ?: run {
        val inAt = live.checkInAt ?: live.checkIn
        val out = live.checkOut
        if (inAt != null && out != null) {
            inAt.localDate(zone).daysUntil(out.localDate(zone)).coerceAtLeast(1)
        } else null
    }
    val roomNights = nights?.let { (it - extensionNights).coerceAtLeast(0) }

    val lines = buildList {
        add(ReceiptLine("Room charges", roomCharges, roomNights?.takeIf { it > 0 }?.let { nightsText(it) }))
        for (e in live.extensions) {
            add(
                ReceiptLine(
                    label = "Extension (+${nightsText(e.nightsAdded)})",
                    amount = e.amount,
                    note = e.newCheckOut?.let { "Until ${Format.date(it, zone)}" }
                )
            )
        }
        if (summary.extraCharges > 0.0) add(ReceiptLine("Extra charges", summary.extraCharges))
    }

    return ReceiptData(
        businessName = businessName,
        receiptNumber = receiptNumber(live.bookingId, live.id),
        producedAt = producedAt,
        zone = zone,
        currencySymbol = currencySymbol,
        guestName = live.guestName,
        guestPhone = live.guestPhone.orNullIfBlank(),
        roomNumber = live.roomNumber,
        roomType = live.roomType.orNullIfBlank(),
        checkIn = live.checkInAt ?: live.checkIn,
        checkOut = summary.actualAt,
        nights = nights,
        lines = lines,
        total = summary.finalAmount,
        paymentMethod = methodLabel(summary.paymentMethodKey),
        paymentStatus = "PAID"
    )
}
