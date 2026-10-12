package com.westly.nbms.features.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptKitTest {

    // ── dates and times ──

    @Test fun dateAndTimeUseTheBusinessZone() {
        // 09:00 UTC is 10:00 in Lagos (UTC+1).
        val i = at("2026-10-12T09:00:00Z")
        assertEquals("Oct 12, 2026", receiptDate(i, LAGOS))
        assertEquals("10:00 AM", receiptTime(i, LAGOS))
        assertEquals("Oct 12, 2026 • 10:00 AM", receiptDateTime(i, LAGOS))
    }

    @Test fun twelveHourClockEdges() {
        assertEquals("12:05 AM", receiptTime(at("2026-10-11T23:05:00Z"), LAGOS))
        assertEquals("12:30 PM", receiptTime(at("2026-10-12T11:30:00Z"), LAGOS))
        assertEquals("1:00 PM", receiptTime(at("2026-10-12T12:00:00Z"), LAGOS))
        assertEquals("11:59 PM", receiptTime(at("2026-10-12T22:59:00Z"), LAGOS))
    }

    @Test fun theDateFollowsTheZoneAcrossMidnight() {
        // 23:30 UTC on 12 Oct is already 13 Oct in Lagos.
        assertEquals("Oct 13, 2026", receiptDate(at("2026-10-12T23:30:00Z"), LAGOS))
    }

    @Test fun noDateShowsADash() {
        assertEquals("—", receiptDate(null, LAGOS))
        assertEquals("—", receiptTime(null, LAGOS))
        assertEquals("—", receiptDateTime(null, LAGOS))
    }

    // ── hotel details ──

    @Test fun contactItemsKeepOnlyFilledFieldsInAFixedOrder() {
        val h = ReceiptHotel(address = " Plot 12, Lagos ", phone = "+234 912 345 6789", email = null, website = "www.hotel.com")
        assertEquals(listOf("Plot 12, Lagos", "+234 912 345 6789", "www.hotel.com"), contactItems(h))
        assertEquals(emptyList<String>(), contactItems(ReceiptHotel()))
    }

    @Test fun blankSettingsBecomeMissingDetails() {
        val h = ReceiptHotelDoc(tagline = "  ", phone = " 0803 ", email = "", address = "Lagos", website = "").toReceiptHotel()
        assertNull(h.tagline)
        assertEquals("0803", h.phone)
        assertNull(h.email)
        assertEquals("Lagos", h.address)
        assertNull(h.website)
    }

    // ── quantity and rate columns ──

    private fun summary(extras: Double, final: Double) = CheckOutSummary(
        finalAmount = final,
        extraCharges = extras,
        alreadyPaid = true,
        amountToCharge = extras,
        paymentMethodKey = "cash",
        scheduledAt = at("2026-10-12T10:00:00Z"),
        actualAt = at("2026-10-12T09:00:00Z"),
        timing = checkoutTiming(at("2026-10-12T09:00:00Z"), at("2026-10-12T10:00:00Z"))
    )

    @Test fun roomLineShowsNightsAndTheRatePerNight() {
        val r = buildReceipt(testBooking().toLive(), summary(0.0, 50_000.0), "Westly Hotel", "₦", LAGOS, at("2026-10-12T09:30:00Z"))
        val room = r.lines.single()
        assertEquals(2, room.qty)
        assertEquals(25_000.0, room.rate!!, 0.0)
        assertEquals("ada@x.com", r.guestEmail)
    }

    @Test fun extensionsAndExtrasHaveTheirOwnQtyAndRate() {
        val live = testBooking(total = 75_000.0).toLive().copy(
            nights = 3,
            extensions = listOf(ExtensionRecord(1, 25_000.0, at("2026-10-13T10:00:00Z")))
        )
        val r = buildReceipt(live, summary(500.0, 75_500.0), "Westly Hotel", "₦", LAGOS, at("2026-10-12T09:30:00Z"))
        assertEquals(listOf(2, 1, 1), r.lines.map { it.qty })
        assertEquals(listOf(25_000.0, 25_000.0, 500.0), r.lines.map { it.rate })
    }

    @Test fun hotelDetailsAreCarriedIntoTheReceipt() {
        val hotel = ReceiptHotel(tagline = "Comfort", phone = "0803")
        val r = buildReceipt(testBooking().toLive(), summary(0.0, 50_000.0), "Westly Hotel", "₦", LAGOS, at("2026-10-12T09:30:00Z"), hotel)
        assertEquals(hotel, r.hotel)
    }
}
