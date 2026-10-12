package com.westly.nbms.features.checkin

import com.westly.nbms.features.checkout.ReceiptHotel
import com.westly.nbms.features.checkout.receiptFileName
import com.westly.nbms.features.checkout.receiptNumber
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** Everything the "Stay Receipt" PDF shows. Times are shown in [zone]. */
data class CheckInReceiptData(
    val businessName: String,
    val receiptNumber: String,
    val issuedAt: Instant,
    val zone: TimeZone,
    val currencySymbol: String,
    val guestName: String,
    val guestPhone: String?,
    val guestEmail: String?,
    val roomNumber: String,
    val roomType: String?,
    val checkIn: Instant,
    val checkOut: Instant,
    val nights: Int,
    val ratePerNight: Double,
    val total: Double,
    /** True when the room charge was recorded at check-in; false when it is still due at check-out. */
    val paid: Boolean,
    val paymentMethod: String?,
    val hotel: ReceiptHotel = ReceiptHotel()
)

private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Builds the stay receipt from a saved walk-in. The check-in time falls back to [issuedAt] and the night count to 1
 * for a success that carries neither, so a receipt can always be drawn.
 */
internal fun buildCheckInReceipt(
    success: WalkInSuccess,
    businessName: String,
    currencySymbol: String,
    zone: TimeZone,
    issuedAt: Instant,
    hotel: ReceiptHotel = ReceiptHotel()
): CheckInReceiptData {
    val nights = success.nights.coerceAtLeast(1)
    return CheckInReceiptData(
        businessName = businessName,
        receiptNumber = receiptNumber(success.bookingCode, success.guestName.filter { it.isLetterOrDigit() }.ifEmpty { "WALKIN" }),
        issuedAt = issuedAt,
        zone = zone,
        currencySymbol = currencySymbol,
        guestName = success.guestName,
        guestPhone = success.guestPhone.cleanOrNull(),
        guestEmail = success.guestEmail.cleanOrNull(),
        roomNumber = success.roomNumber,
        roomType = success.roomType.cleanOrNull(),
        checkIn = success.checkIn ?: issuedAt,
        checkOut = success.checkOut,
        nights = nights,
        ratePerNight = success.total / nights,
        total = success.total,
        paid = success.paidNow,
        paymentMethod = success.paymentMethodLabel.cleanOrNull(),
        hotel = hotel
    )
}

/** "checkin-receipt-WI-AB12CD.pdf": its own name, so it never replaces the check-out receipt of the same booking. */
internal fun checkInReceiptFileName(number: String): String = "checkin-" + receiptFileName(number)
