package com.westly.nbms.features.checkin

import com.westly.nbms.features.checkout.ReceiptHotel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInReceiptDataTest {

    private val lagos = TimeZone.of("Africa/Lagos")
    private val now = Instant.parse("2026-10-12T09:30:00Z")

    private fun success(
        paid: Boolean = true,
        nights: Int = 2,
        total: Double = 50_000.0,
        code: String? = "WI-AB12CD"
    ) = WalkInSuccess(
        guestName = "Ada Obi",
        roomNumber = "101",
        checkOut = Instant.parse("2026-10-14T10:00:00Z"),
        paidNow = paid,
        total = total,
        guestPhone = " 08031234567 ",
        guestEmail = "  ",
        roomType = "Deluxe Room",
        checkIn = Instant.parse("2026-10-12T09:00:00Z"),
        nights = nights,
        bookingCode = code,
        paymentMethodLabel = "Cash"
    )

    @Test fun receiptCarriesTheStayAndTheGuest() {
        val r = buildCheckInReceipt(success(), "Westly Hotel", "₦", lagos, now)
        assertEquals("Westly Hotel", r.businessName)
        assertEquals("WI-AB12CD", r.receiptNumber)
        assertEquals("Ada Obi", r.guestName)
        assertEquals("08031234567", r.guestPhone)
        assertNull(r.guestEmail)
        assertEquals("101", r.roomNumber)
        assertEquals("Deluxe Room", r.roomType)
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), r.checkIn)
        assertEquals(Instant.parse("2026-10-14T10:00:00Z"), r.checkOut)
        assertEquals(2, r.nights)
        assertEquals(25_000.0, r.ratePerNight, 0.0)
        assertEquals(50_000.0, r.total, 0.0)
        assertTrue(r.paid)
        assertEquals("Cash", r.paymentMethod)
    }

    @Test fun payAtCheckOutIsNotMarkedPaid() {
        val r = buildCheckInReceipt(success(paid = false), "Westly Hotel", "₦", lagos, now)
        assertFalse(r.paid)
    }

    @Test fun aSuccessWithoutExtrasStillMakesAReceipt() {
        val bare = WalkInSuccess("Ada Obi", "101", Instant.parse("2026-10-13T10:00:00Z"), paidNow = true, total = 30_000.0, nights = 0)
        val r = buildCheckInReceipt(bare, "Westly Hotel", "₦", lagos, now)
        assertEquals(1, r.nights)
        assertEquals(30_000.0, r.ratePerNight, 0.0)
        assertEquals(now, r.checkIn)
        assertEquals("ADAOBI", r.receiptNumber)
    }

    @Test fun hotelDetailsAreCarriedIn() {
        val hotel = ReceiptHotel(phone = "0803", website = "www.hotel.com")
        assertEquals(hotel, buildCheckInReceipt(success(), "Westly Hotel", "₦", lagos, now, hotel).hotel)
    }

    @Test fun fileNameHasItsOwnPrefix() {
        assertEquals("checkin-receipt-WI-AB12CD.pdf", checkInReceiptFileName("WI-AB12CD"))
    }
}
