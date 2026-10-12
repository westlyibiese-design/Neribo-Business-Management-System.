package com.westly.nbms.features.checkout

import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.util.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

/** One row of the charges table on the receipt. [note] is a small grey line under the label. */
data class ReceiptLine(
    val label: String,
    val amount: Double,
    val note: String? = null,
    /** Shown in the QTY and RATE columns when both are known (rate is the price of one unit). */
    val qty: Int? = null,
    val rate: Double? = null
)

/** The hotel details printed on both receipts. Every field is optional; a blank one is simply left off. */
data class ReceiptHotel(
    val tagline: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val website: String? = null
)

/** `settings/hotel` as the receipts read it: only the contact fields (a private copy, like the other pages do). */
data class ReceiptHotelDoc(
    val tagline: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = ""
) {
    fun toReceiptHotel(): ReceiptHotel = ReceiptHotel(
        tagline = tagline.orNullIfBlank(),
        phone = phone.orNullIfBlank(),
        email = email.orNullIfBlank(),
        address = address.orNullIfBlank(),
        website = website.orNullIfBlank()
    )
}

/** Live hotel details for the receipts; an empty [ReceiptHotel] until they load or if they cannot be read. */
internal fun BusinessFirestore.observeReceiptHotel(): Flow<ReceiptHotel> =
    observeDoc("settings", "hotel", ReceiptHotelDoc::class.java)
        .map { r -> if (r is Resource.Success) (r.data?.toReceiptHotel() ?: ReceiptHotel()) else ReceiptHotel() }
        .catch { emit(ReceiptHotel()) }

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
    val paymentStatus: String,
    val guestEmail: String? = null,
    val hotel: ReceiptHotel = ReceiptHotel()
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
    producedAt: Instant,
    hotel: ReceiptHotel = ReceiptHotel()
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
        val billedNights = roomNights?.takeIf { it > 0 }
        add(
            ReceiptLine(
                label = "Room charges",
                amount = roomCharges,
                note = billedNights?.let { nightsText(it) },
                qty = billedNights,
                rate = billedNights?.let { roomCharges / it }
            )
        )
        for (e in live.extensions) {
            add(
                ReceiptLine(
                    label = "Extension (+${nightsText(e.nightsAdded)})",
                    amount = e.amount,
                    note = e.newCheckOut?.let { "Until ${Format.date(it, zone)}" },
                    qty = e.nightsAdded.takeIf { it > 0 },
                    rate = e.nightsAdded.takeIf { it > 0 }?.let { e.amount / it }
                )
            )
        }
        if (summary.extraCharges > 0.0) add(ReceiptLine("Extra charges", summary.extraCharges, qty = 1, rate = summary.extraCharges))
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
        paymentStatus = "PAID",
        guestEmail = live.guestEmail.orNullIfBlank(),
        hotel = hotel
    )
}
