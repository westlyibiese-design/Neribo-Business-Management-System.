package com.westly.nbms.features.checkin

import com.google.firebase.Timestamp
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The documents of the one transaction: every one present, and every one pointing at the same booking. */
class WalkInPayloadTest {

    private val ids = WalkInIds(guestId = "g1", bookingId = "ab12cdef99", checkinId = "c1", paymentId = "p1")
    private val staff = WalkInStaff("u1", "Rita")
    private val checkIn = Instant.parse("2026-10-10T13:00:00Z")
    private val checkOut = Instant.parse("2026-10-12T10:00:00Z")

    private fun build(form: WalkInForm = testForm(), room: com.westly.nbms.features.rooms.Room = testRoom()) =
        buildWalkInWrites(ids, form, room, staff, checkIn, checkOut, nights = 2)

    @Test fun payingNowGivesSixDocuments() {
        val w = build()
        assertNotNull(w.payment)
        assertEquals("r1", w.roomId)
        assertEquals(ids, w.ids)
    }

    @Test fun payingAtCheckOutGivesFiveDocumentsAndNoPayment() {
        val w = build(testForm(option = PaymentOption.PAY_AT_CHECKOUT))
        assertNull(w.payment)
        assertEquals("pending", w.booking["roomPaymentStatus"])
        assertEquals("pay_at_checkout", w.booking["paymentOption"])
    }

    @Test fun guestDocument() {
        val g = build().guest
        assertEquals("Ada Obi", g["name"])
        assertNull(g["email"]) // blank email is stored as null
        assertEquals("08031234567", g["phone"])
        assertEquals("Nigerian", g["nationality"])
        assertEquals("A1234567", g["idDocumentRef"])
        assertTrue(g["firstVisit"] === WalkInServerTime)
        assertEquals(1, g["totalStays"])
        assertEquals(false, g["isDeleted"])
    }

    @Test fun bookingDocument() {
        val b = build().booking
        assertEquals("WI-AB12CD", b["bookingId"])
        assertEquals("g1", b["guestId"])
        assertEquals("Ada Obi", b["guestName"])
        assertNull(b["guestEmail"])
        assertEquals("08031234567", b["guestPhone"])
        assertEquals("r1", b["roomId"])
        assertEquals("101", b["roomNumber"])
        assertEquals("Deluxe Room", b["roomType"])
        assertEquals(Timestamp(checkIn.epochSeconds, 0), b["checkIn"])
        assertEquals(Timestamp(checkOut.epochSeconds, 0), b["checkOut"])
        assertEquals(b["checkIn"], b["checkInAt"])
        assertEquals(2, b["nights"])
        assertEquals(2, b["adults"])
        assertEquals(1, b["children"])
        assertEquals(50_000.0, b["totalAmount"])
        assertEquals("cash", b["paymentMethod"])
        assertEquals("pay_at_checkin", b["paymentOption"])
        assertEquals("paid", b["roomPaymentStatus"])
        assertEquals("checked_in", b["status"])
        assertEquals("walk_in", b["source"])
        assertTrue(b["createdAt"] === WalkInServerTime)
        assertEquals("u1", b["createdBy"])
        assertEquals("Rita", b["createdByName"])
        assertEquals("Late arrival", b["notes"])
        assertEquals(false, b["isDeleted"])
    }

    @Test fun perNightLockMatchesTheBookingAndIsCheckedIn() {
        val w = build()
        assertEquals("checked_in", w.bookingDate["status"])
        assertEquals(w.booking["roomId"], w.bookingDate["roomId"])
        assertEquals(w.booking["checkIn"], w.bookingDate["checkIn"])
        assertEquals(w.booking["checkOut"], w.bookingDate["checkOut"])
        // The lock holds no personal data.
        assertEquals(setOf("roomId", "checkIn", "checkOut", "status"), w.bookingDate.keys)
    }

    @Test fun checkInRecordPointsAtTheBookingDocument() {
        val c = build().checkin
        assertEquals("ab12cdef99", c["bookingId"]) // the document id, not the WI- code
        assertEquals("r1", c["roomId"])
        assertEquals("101", c["roomNumber"])
        assertEquals("Ada Obi", c["guestName"])
        assertTrue(c["checkInTime"] === WalkInServerTime)
        assertEquals(Timestamp(checkIn.epochSeconds, 0), c["checkInAt"])
        assertEquals(Timestamp(checkOut.epochSeconds, 0), c["expectedCheckOutAt"])
        assertEquals("u1", c["staffId"])
        assertEquals("Rita", c["staffName"])
        assertEquals("A1234567", c["idDocumentRef"])
        assertEquals("Late arrival", c["notes"])
        assertEquals(false, c["isDeleted"])
    }

    @Test fun roomUpdateMarksItOccupiedAndClean() {
        val r = build().room
        assertEquals("occupied", r["status"])
        assertEquals("Ada Obi", r["currentGuest"])
        assertEquals("ab12cdef99", r["currentBookingId"])
        assertTrue(r["statusUpdatedAt"] === WalkInServerTime)
        assertEquals("clean", r["cleanliness"])
        assertTrue(r["cleanlinessUpdatedAt"] === WalkInServerTime)
        assertEquals(false, r["checkoutOverdue"])
    }

    @Test fun paymentIsPendingApproval() {
        val p = build(testForm(method = PaymentMethod.BANK_TRANSFER)).payment!!
        assertEquals("ab12cdef99", p["bookingId"])
        assertEquals("Ada Obi", p["guestName"])
        assertEquals("101", p["roomNumber"])
        assertEquals(50_000.0, p["amount"])
        assertEquals("bank_transfer", p["paymentMethod"])
        assertEquals("walk_in_payment", p["type"])
        assertEquals("u1", p["recordedBy"])
        assertEquals("Rita", p["recordedByName"])
        assertTrue(p["createdAt"] === WalkInServerTime)
        assertEquals("pending", p["approvalStatus"])
        for (key in listOf("approvedBy", "approvedByName", "approvedAt", "rejectedReason")) {
            assertTrue("$key should be present and null", p.containsKey(key) && p[key] == null)
        }
        assertEquals(false, p["isDeleted"])
    }

    @Test fun blankOptionalFieldsBecomeNull() {
        val w = build(testForm().copy(nationality = " ", idDocumentRef = "", notes = "  ", phone = " 0803 "))
        assertNull(w.guest["nationality"])
        assertNull(w.guest["idDocumentRef"])
        assertNull(w.booking["notes"])
        assertNull(w.checkin["idDocumentRef"])
        assertNull(w.checkin["notes"])
        assertEquals("0803", w.guest["phone"])
    }

    @Test fun totalFollowsRoomPriceAndNights() {
        val w = buildWalkInWrites(ids, testForm(), testRoom(price = 12_500.5), staff, checkIn, checkOut, nights = 3)
        assertEquals(37_501.5, w.booking["totalAmount"])
        assertEquals(37_501.5, w.payment!!["amount"])
        assertFalse(w.booking["nights"] != 3)
    }

    // ---- the fake store stands for the transaction ----

    @Test fun fakeStoreRecordsTheCommitOnce() = kotlinx.coroutines.test.runTest {
        val store = FakeWalkInStore()
        val w = build()
        store.commit(w)
        assertEquals(listOf(w), store.commits)
    }
}
