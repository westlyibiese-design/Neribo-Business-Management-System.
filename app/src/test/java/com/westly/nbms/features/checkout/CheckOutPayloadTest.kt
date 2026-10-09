package com.westly.nbms.features.checkout

import com.google.firebase.Timestamp
import com.westly.nbms.features.bookings.Booking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The documents of the one check-out transaction. */
class CheckOutPayloadTest {

    private val ids = CheckOutIds("co1", "pay1")
    private val actual = at("2026-10-12T09:00:00Z") // an hour before the scheduled 10:00 UTC

    private fun build(
        booking: Booking = testBooking(),
        input: CheckOutInput = testInput(actual = actual)
    ) = buildCheckOutWrites(ids, booking.toLive(), input, actual)

    @Test fun paidRoomWithNoExtrasMakesNoPayment() {
        val w = build()
        assertNull(w.payment)
        assertEquals(0.0, w.summary.amountToCharge, 0.0)
        assertEquals(50_000.0, w.summary.finalAmount, 0.0)
        assertTrue(w.summary.alreadyPaid)
    }

    @Test fun bookingUpdate() {
        val b = build(input = testInput(actual = actual, extras = 500.0, method = CheckOutPaymentMethod.MOBILE_PAYMENT, notes = " Late bag ")).booking
        assertEquals("checked_out", b["status"])
        assertEquals(Timestamp(actual.epochSeconds, 0), b["checkOutAt"])
        assertTrue(b["checkOutServerAt"] === CheckOutServerTime)
        assertEquals(Timestamp(at("2026-10-12T10:00:00Z").epochSeconds, 0), b["scheduledCheckOutAt"])
        assertEquals("u1", b["checkedOutBy"])
        assertEquals("Rita", b["checkedOutByName"])
        assertEquals(50_500.0, b["finalAmount"])
        assertEquals(500.0, b["extraCharges"])
        assertEquals("mobile_payment", b["paymentMethod"]) // extras were charged, so the chosen method is recorded
        assertEquals("paid", b["roomPaymentStatus"])
        assertEquals("Late bag", b["checkOutNotes"])
        assertTrue(b["updatedAt"] === CheckOutServerTime)
        assertTrue(b.containsKey("lastOverdueNotifiedAt"))
        assertNull(b["lastOverdueNotifiedAt"])
    }

    @Test fun nothingChargedKeepsTheExistingMethodAndNullNotes() {
        val b = build(input = testInput(actual = actual, method = CheckOutPaymentMethod.CASH), booking = testBooking(method = "bank_transfer")).booking
        assertEquals("bank_transfer", b["paymentMethod"])
        assertNull(b["checkOutNotes"])
    }

    @Test fun nothingChargedAndNoExistingMethodUsesTheChosenOne() {
        val b = build(booking = testBooking(method = null), input = testInput(actual = actual, method = CheckOutPaymentMethod.DEBIT_CARD)).booking
        assertEquals("debit_card", b["paymentMethod"])
    }

    @Test fun perNightLockIsCheckedOutAndKeepsTheDates() {
        val d = build().bookingDate
        assertEquals("r1", d["roomId"])
        assertEquals("checked_out", d["status"])
        assertEquals(Timestamp(at("2026-10-10T13:00:00Z").epochSeconds, 0), d["checkIn"])
        assertEquals(Timestamp(at("2026-10-12T10:00:00Z").epochSeconds, 0), d["checkOut"])
    }

    @Test fun roomGoesToCleaningAndDirty() {
        val w = build()
        assertEquals("r1", w.roomId)
        val r = w.room!!
        assertEquals("cleaning", r["status"])
        assertNull(r["currentGuest"])
        assertTrue(r.containsKey("currentGuest"))
        assertNull(r["currentBookingId"])
        assertTrue(r.containsKey("currentBookingId"))
        assertTrue(r["statusUpdatedAt"] === CheckOutServerTime)
        assertEquals("dirty", r["cleanliness"])
        assertTrue(r["cleanlinessUpdatedAt"] === CheckOutServerTime)
        assertEquals(false, r["checkoutOverdue"])
        assertTrue(r.containsKey("cleaningReminderLastSentAt"))
    }

    @Test fun noRoomIdMeansNoRoomUpdate() {
        val w = build(booking = testBooking(roomId = ""))
        assertNull(w.room)
        assertNull(w.roomId)
        assertNull(w.checkout["roomId"])
    }

    @Test fun checkoutRecordForAPaidRoom() {
        val c = build(input = testInput(actual = actual, extras = 500.0, notes = "")).checkout
        assertEquals("b1abcdef99", c["bookingId"])
        assertEquals("r1", c["roomId"])
        assertEquals("101", c["roomNumber"])
        assertEquals("Ada Obi", c["guestName"])
        assertEquals("ada@x.com", c["guestEmail"])
        assertTrue(c["checkOutTime"] === CheckOutServerTime)
        assertEquals(Timestamp(actual.epochSeconds, 0), c["checkOutAt"])
        assertEquals(50_000.0, c["baseAmount"])
        assertEquals(500.0, c["extraCharges"])
        assertEquals(50_500.0, c["finalAmount"])
        assertEquals(false, c["roomChargedAtCheckout"])
        assertEquals(500.0, c["amountChargedNow"])
        assertEquals("u1", c["staffId"])
        assertEquals("Rita", c["staffName"])
        assertNull(c["notes"])
        assertEquals(false, c["isDeleted"])
    }

    @Test fun paidRoomWithExtrasChargesOnlyTheExtras() {
        val w = build(input = testInput(actual = actual, extras = 500.0))
        assertNotNull(w.payment)
        val p = w.payment!!
        assertEquals(500.0, p["amount"])
        assertEquals("room_payment", p["type"])
        assertEquals("pending", p["approvalStatus"])
        assertEquals("cash", p["paymentMethod"])
        assertEquals("b1abcdef99", p["bookingId"])
        assertEquals("Ada Obi", p["guestName"])
        assertEquals("101", p["roomNumber"])
        assertEquals("u1", p["recordedBy"])
        assertEquals("Rita", p["recordedByName"])
        assertTrue(p["createdAt"] === CheckOutServerTime)
        for (k in listOf("approvedBy", "approvedByName", "approvedAt", "rejectedReason")) {
            assertTrue(k, p.containsKey(k))
            assertNull(p[k])
        }
        assertEquals(false, p["isDeleted"])
    }

    @Test fun unpaidRoomIsChargedInFullAndChecksOutAsCharged() {
        val w = build(booking = testBooking(paid = false), input = testInput(actual = actual, extras = 500.0, method = CheckOutPaymentMethod.CREDIT_CARD))
        assertEquals(50_500.0, w.payment!!["amount"])
        assertEquals("credit_card", w.payment!!["paymentMethod"])
        assertEquals(true, w.checkout["roomChargedAtCheckout"])
        assertEquals(50_500.0, w.checkout["amountChargedNow"])
        assertFalse(w.summary.alreadyPaid)
    }

    @Test fun summaryTimingUsesTheActualAndScheduledTimes() {
        val s = build().summary
        assertEquals(TimingKind.EARLY, s.timing.kind)
        assertEquals(1.0, s.timing.hours, 0.0)
        assertEquals(at("2026-10-12T10:00:00Z"), s.scheduledAt)
        assertEquals(actual, s.actualAt)
    }

    @Test fun negativeExtrasAreNeverCharged() {
        val w = build(input = testInput(actual = actual, extras = -300.0))
        assertNull(w.payment)
        assertEquals(50_000.0, w.summary.finalAmount, 0.0)
    }
}
