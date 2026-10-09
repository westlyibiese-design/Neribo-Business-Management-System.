package com.westly.nbms.features.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptDataTest {

    private fun summary(extras: Double = 0.0, method: String = "cash", final: Double = 50_000.0 + extras) = CheckOutSummary(
        finalAmount = final,
        extraCharges = extras,
        alreadyPaid = true,
        amountToCharge = extras,
        paymentMethodKey = method,
        scheduledAt = at("2026-10-12T10:00:00Z"),
        actualAt = at("2026-10-12T09:00:00Z"),
        timing = checkoutTiming(at("2026-10-12T09:00:00Z"), at("2026-10-12T10:00:00Z"))
    )

    private fun receipt(live: LiveBooking, s: CheckOutSummary) =
        buildReceipt(live, s, "Westly Hotel", "₦", LAGOS, at("2026-10-12T09:30:00Z"))

    @Test fun simpleStay() {
        val r = receipt(testBooking().toLive(), summary())
        assertEquals("Westly Hotel", r.businessName)
        assertEquals("WI-AB12CD", r.receiptNumber)
        assertEquals("Ada Obi", r.guestName)
        assertEquals("08031234567", r.guestPhone)
        assertEquals("101", r.roomNumber)
        assertEquals("Deluxe Room", r.roomType)
        assertEquals(at("2026-10-10T13:00:00Z"), r.checkIn)
        assertEquals(at("2026-10-12T09:00:00Z"), r.checkOut)
        assertEquals(2, r.nights)
        assertEquals(listOf("Room charges"), r.lines.map { it.label })
        assertEquals(50_000.0, r.lines.single().amount, 0.0)
        assertEquals("2 night(s)", r.lines.single().note)
        assertEquals(50_000.0, r.total, 0.0)
        assertEquals("Cash", r.paymentMethod)
        assertEquals("PAID", r.paymentStatus)
    }

    @Test fun extensionsAndExtrasAddUpToTheTotal() {
        // 2 nights at 25,000 plus a one-night extension of 25,000: the booking total already holds both.
        val live = testBooking(total = 75_000.0).toLive().copy(
            nights = 3,
            extensions = listOf(ExtensionRecord(1, 25_000.0, at("2026-10-13T10:00:00Z")))
        )
        val r = receipt(live, summary(extras = 500.0, final = 75_500.0, method = "bank_transfer"))
        assertEquals(listOf("Room charges", "Extension (+1 night(s))", "Extra charges"), r.lines.map { it.label })
        assertEquals(listOf(50_000.0, 25_000.0, 500.0), r.lines.map { it.amount })
        assertEquals(r.total, r.lines.sumOf { it.amount }, 0.0)
        assertEquals("2 night(s)", r.lines[0].note)
        assertEquals("Until 13 Oct 2026", r.lines[1].note)
        assertNull(r.lines[2].note)
        assertEquals("Bank Transfer", r.paymentMethod)
        assertEquals(3, r.nights)
    }

    @Test fun nightsAreWorkedOutWhenNotStored() {
        val live = testBooking().toLive().copy(nights = null)
        assertEquals(2, receipt(live, summary()).nights)
    }

    @Test fun receiptNumberFallsBackToTheDocumentId() {
        assertEquals("B1ABCDEF", receiptNumber(null, "b1abcdef99"))
        assertEquals("B1ABCDEF", receiptNumber("  ", "b1abcdef99"))
        assertEquals("WI-1", receiptNumber("wi-1", "x"))
    }

    @Test fun fileNameKeepsOnlySafeCharacters() {
        assertEquals("receipt-WI-AB12CD.pdf", receiptFileName("WI-AB12CD"))
        assertEquals("receipt-A-B-C.pdf", receiptFileName("A/B C"))
    }
}
