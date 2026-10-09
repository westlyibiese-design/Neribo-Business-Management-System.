package com.westly.nbms.features.laundry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LaundryRulesTest {

    @Test fun theChainWalksEveryStepAndEndsAtDelivered() {
        assertEquals(LaundryStatus.WASHING, nextStatus(LaundryStatus.RECEIVED))
        assertEquals(LaundryStatus.DRYING, nextStatus(LaundryStatus.WASHING))
        assertEquals(LaundryStatus.IRONING, nextStatus(LaundryStatus.DRYING))
        assertEquals(LaundryStatus.READY, nextStatus(LaundryStatus.IRONING))
        assertEquals(LaundryStatus.DELIVERED, nextStatus(LaundryStatus.READY))
        assertNull(nextStatus(LaundryStatus.DELIVERED))
        assertNull(nextStatus(LaundryStatus.CANCELLED))
    }

    @Test fun theButtonLabelNamesTheNextStep() {
        assertEquals("Mark Washing", laundryNextButtonLabel(LaundryStatus.RECEIVED))
        assertEquals("Mark Ready for Collection", laundryNextButtonLabel(LaundryStatus.IRONING))
        assertNull(laundryNextButtonLabel(LaundryStatus.DELIVERED))
    }

    @Test fun summaryCountsTheFiveActiveStatusesOverActiveAndDeliveredOverAll() {
        val all = listOf(
            laundry22aRequestOf("1", status = LaundryStatus.RECEIVED),
            laundry22aRequestOf("2", status = LaundryStatus.RECEIVED),
            laundry22aRequestOf("3", status = LaundryStatus.WASHING),
            laundry22aRequestOf("4", status = LaundryStatus.DRYING),
            laundry22aRequestOf("5", status = LaundryStatus.IRONING),
            laundry22aRequestOf("6", status = LaundryStatus.READY),
            laundry22aRequestOf("7", status = LaundryStatus.DELIVERED),
            laundry22aRequestOf("8", status = LaundryStatus.DELIVERED),
            laundry22aRequestOf("9", status = LaundryStatus.DELIVERED),
            laundry22aRequestOf("10", status = LaundryStatus.CANCELLED)
        )
        val counts = laundryCounts(all)
        assertEquals(2, counts[LaundryStatus.RECEIVED])
        assertEquals(1, counts[LaundryStatus.WASHING])
        assertEquals(1, counts[LaundryStatus.DRYING])
        assertEquals(1, counts[LaundryStatus.IRONING])
        assertEquals(1, counts[LaundryStatus.READY])
        assertEquals(3, counts[LaundryStatus.DELIVERED])
        assertFalse(counts.containsKey(LaundryStatus.CANCELLED))
    }

    @Test fun anEmptyListCountsZeroEverywhere() {
        val counts = laundryCounts(emptyList())
        assertEquals(6, counts.size)
        assertTrue(counts.values.all { it == 0 })
    }

    @Test fun activeMeansNotDeliveredAndNotCancelled() {
        assertTrue(laundryIsActive(laundry22aRequestOf("1", status = LaundryStatus.READY)))
        assertFalse(laundryIsActive(laundry22aRequestOf("2", status = LaundryStatus.DELIVERED)))
        assertFalse(laundryIsActive(laundry22aRequestOf("3", status = LaundryStatus.CANCELLED)))
    }

    @Test fun theActiveListIsOldestFirstAndDropsFinishedRequests() {
        val t = Instant.parse("2026-10-09T08:00:00Z")
        val all = listOf(
            laundry22aRequestOf("new", at = t.plusSeconds(3600)),
            laundry22aRequestOf("old", at = t),
            laundry22aRequestOf("mid", at = t.plusSeconds(60)),
            laundry22aRequestOf("done", at = t.minusSeconds(60), status = LaundryStatus.DELIVERED),
            laundry22aRequestOf("nodate", at = null)
        )
        assertEquals(listOf("old", "mid", "new", "nodate"), activeRequestsOldestFirst(all).map { it.id })
    }

    @Test fun theSubtitleUsesTheSingularForOne() {
        assertEquals("0 active requests", laundryActiveSubtitle(0))
        assertEquals("1 active request", laundryActiveSubtitle(1))
        assertEquals("4 active requests", laundryActiveSubtitle(4))
    }

    @Test fun theItemsLineJoinsTheDescriptionAndTheCount() {
        assertEquals("3 shirts · 5 items", laundryItemsLine(laundry22aRequestOf("1", items = "3 shirts", count = 5)))
        assertEquals("1 suit · 1 item", laundryItemsLine(laundry22aRequestOf("2", items = "1 suit", count = 1)))
        assertNull(laundryItemsLine(laundry22aRequestOf("3", items = null)))
    }

    @Test fun chargeParsingIgnoresInvalidAndNegativeNumbers() {
        assertEquals(2500.0, parseChargeUpdate("2500")!!, 0.0)
        assertEquals(2500.5, parseChargeUpdate(" 2,500.50 ")!!, 0.0)
        assertEquals(0.0, parseChargeUpdate("0")!!, 0.0)
        assertNull(parseChargeUpdate("abc"))
        assertNull(parseChargeUpdate(""))
        assertNull(parseChargeUpdate("-5"))
        assertNull(parseChargeUpdate("NaN"))
        assertNull(parseChargeUpdate("Infinity"))
    }

    @Test fun theChargeFieldShowsWholeNumbersWithoutDecimals() {
        assertEquals("4000", chargeFieldText(4000.0))
        assertEquals("4000.5", chargeFieldText(4000.5))
        assertEquals("0", chargeFieldText(0.0))
    }

    @Test fun thePaidToggleFlipsBothWays() {
        assertEquals(PaymentStatus.PAID, togglePayment(PaymentStatus.UNPAID))
        assertEquals(PaymentStatus.UNPAID, togglePayment(PaymentStatus.PAID))
        val fields = buildPaidPayload(PaymentStatus.UNPAID)
        assertEquals("paid", fields["paymentStatus"])
        assertTrue(fields["updatedAt"] === LaundryServerTime)
        assertEquals(setOf("paymentStatus", "updatedAt"), fields.keys)
        assertEquals("unpaid", buildPaidPayload(PaymentStatus.PAID)["paymentStatus"])
    }

    @Test fun theAdvancePayloadHasNoDeliveredAtBeforeTheLastStep() {
        val fields = buildAdvancePayload(LaundryStatus.WASHING, "u1")
        assertEquals("washing", fields["status"])
        assertEquals("u1", fields["updatedBy"])
        assertTrue(fields["updatedAt"] === LaundryServerTime)
        assertEquals(setOf("status", "updatedAt", "updatedBy"), fields.keys)
    }

    @Test fun theAdvancePayloadAddsDeliveredAtForDelivered() {
        val fields = buildAdvancePayload(LaundryStatus.DELIVERED, "u1")
        assertEquals("delivered", fields["status"])
        assertTrue(fields["deliveredAt"] === LaundryServerTime)
        assertEquals(setOf("status", "updatedAt", "updatedBy", "deliveredAt"), fields.keys)
    }

    @Test fun theChargePayloadHoldsTheChargeAndTheTime() {
        val fields = buildChargePayload(1250.0)
        assertEquals(1250.0, fields["charge"])
        assertTrue(fields["updatedAt"] === LaundryServerTime)
        assertEquals(setOf("charge", "updatedAt"), fields.keys)
    }
}
