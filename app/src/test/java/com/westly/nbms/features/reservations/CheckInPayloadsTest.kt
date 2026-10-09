package com.westly.nbms.features.reservations

import com.google.firebase.Timestamp
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val ST = "SERVER_TIME"

private val CHECK_IN = Instant.parse("2026-10-09T09:00:00Z")
private val ENTITLED = Instant.parse("2026-10-10T10:00:00Z")

private fun booking(roomId: String = "room-7", paymentMethod: String? = null) = Booking(
    id = "bk1", bookingId = "WEB-1", guestName = "Ada Obi", guestEmail = "ada@x.com", guestPhone = "0803",
    roomId = roomId, roomNumber = "101", roomType = "Deluxe",
    checkIn = Timestamp(1_000, 0), checkOut = Timestamp(2_000, 0),
    nights = 2, totalAmount = 50_000.0, paymentMethod = paymentMethod, status = "confirmed", source = "website"
)

private fun form(
    option: PaymentOption = PaymentOption.PAY_AT_CHECK_IN,
    method: PaymentMethod = PaymentMethod.CASH,
    id: String? = "NIN-123",
    notes: String? = "Late arrival"
) = CheckInForm(CHECK_IN, id, option, method, notes)

class CheckInPayloadsTest {

    @Test fun storedKeys() {
        assertEquals("pay_at_check_in", PaymentOption.PAY_AT_CHECK_IN.key)
        assertEquals("pay_at_check_out", PaymentOption.PAY_AT_CHECK_OUT.key)
        assertEquals(listOf("cash", "credit_card", "debit_card", "bank_transfer"), PaymentMethod.entries.map { it.key })
    }

    @Test fun timestampComesFromEpochMilliseconds() {
        val t = Instant.fromEpochMilliseconds(1_700_000_123_456L).toFirebaseTimestamp()
        assertEquals(1_700_000_123L, t.seconds)
        assertEquals(456_000_000, t.nanoseconds)
    }

    // ---- Status change ------------------------------------------------------------------------------

    @Test fun statusChangeFieldSets() {
        val update = buildStatusChangeBookingUpdate(BookingStatus.CONFIRMED, "u1", "Rita", ST)
        assertEquals(setOf("status", "updatedAt", "updatedBy", "updatedByName"), update.keys)
        assertEquals("confirmed", update["status"])
        assertEquals(ST, update["updatedAt"])
        assertEquals("u1", update["updatedBy"])
        assertEquals("Rita", update["updatedByName"])

        val dates = buildStatusChangeBookingDates(booking(), BookingStatus.NO_SHOW)
        assertEquals(setOf("roomId", "checkIn", "checkOut", "status"), dates.keys)
        assertEquals("room-7", dates["roomId"])
        assertEquals(Timestamp(1_000, 0), dates["checkIn"])
        assertEquals(Timestamp(2_000, 0), dates["checkOut"])
        assertEquals("no_show", dates["status"])
    }

    @Test fun onlyFourStatusesAreChangeable() {
        assertEquals(
            setOf(BookingStatus.CONFIRMED, BookingStatus.REJECTED, BookingStatus.CANCELLED, BookingStatus.NO_SHOW),
            CHANGEABLE_STATUSES
        )
    }

    // ---- Guards -------------------------------------------------------------------------------------

    @Test fun guards() {
        assertEquals("Booking not found.", checkInGuardMessage(false, null))
        assertEquals("Booking not found.", checkInGuardMessage(false, "confirmed"))
        assertEquals("Guest is already checked in.", checkInGuardMessage(true, "checked_in"))
        assertEquals("Cannot check in a cancelled or rejected booking.", checkInGuardMessage(true, "cancelled"))
        assertEquals("Cannot check in a cancelled or rejected booking.", checkInGuardMessage(true, "rejected"))
        assertNull(checkInGuardMessage(true, "pending"))
        assertNull(checkInGuardMessage(true, "confirmed"))
    }

    // ---- Booking update -----------------------------------------------------------------------------

    @Test fun bookingUpdatePayNow() {
        val m = buildCheckInBookingUpdate(form(), ENTITLED, "u1", "Rita", "bank_transfer", ST)
        assertEquals(
            setOf(
                "status", "checkInAt", "checkOut", "checkedInBy", "checkedInByName", "idDocumentRef", "checkInNotes",
                "paymentOption", "paymentMethod", "roomPaymentStatus", "updatedAt"
            ),
            m.keys
        )
        assertEquals("checked_in", m["status"])
        assertEquals(CHECK_IN.toFirebaseTimestamp(), m["checkInAt"])
        assertEquals(ENTITLED.toFirebaseTimestamp(), m["checkOut"])
        assertEquals("u1", m["checkedInBy"])
        assertEquals("Rita", m["checkedInByName"])
        assertEquals("NIN-123", m["idDocumentRef"])
        assertEquals("Late arrival", m["checkInNotes"])
        assertEquals("pay_at_check_in", m["paymentOption"])
        assertEquals("cash", m["paymentMethod"])
        assertEquals("paid", m["roomPaymentStatus"])
        assertEquals(ST, m["updatedAt"])
    }

    @Test fun bookingUpdatePayAtCheckOutKeepsExistingMethod() {
        val keep = buildCheckInBookingUpdate(form(PaymentOption.PAY_AT_CHECK_OUT, PaymentMethod.CREDIT_CARD), ENTITLED, "u1", "Rita", "bank_transfer", ST)
        assertEquals("pay_at_check_out", keep["paymentOption"])
        assertEquals("bank_transfer", keep["paymentMethod"])
        assertEquals("pending", keep["roomPaymentStatus"])

        val none = buildCheckInBookingUpdate(form(PaymentOption.PAY_AT_CHECK_OUT), ENTITLED, "u1", "Rita", null, ST)
        assertTrue(none.containsKey("paymentMethod"))
        assertNull(none["paymentMethod"])
    }

    @Test fun bookingUpdateNeverTouchesNightsOrTotal() {
        val m = buildCheckInBookingUpdate(form(), ENTITLED, "u1", "Rita", null, ST)
        assertFalse(m.containsKey("nights"))
        assertFalse(m.containsKey("totalAmount"))
    }

    @Test fun blankIdAndNotesAreStoredAsNull() {
        val m = buildCheckInBookingUpdate(form(id = null, notes = null), ENTITLED, "u1", "Rita", null, ST)
        assertTrue(m.containsKey("idDocumentRef"))
        assertNull(m["idDocumentRef"])
        assertTrue(m.containsKey("checkInNotes"))
        assertNull(m["checkInNotes"])
    }

    // ---- booking_dates, rooms, checkins -------------------------------------------------------------

    @Test fun bookingDates() {
        val m = buildCheckInBookingDates(booking(), ENTITLED)
        assertEquals(setOf("roomId", "checkIn", "checkOut", "status"), m.keys)
        assertEquals("room-7", m["roomId"])
        assertEquals(Timestamp(1_000, 0), m["checkIn"])
        assertEquals(ENTITLED.toFirebaseTimestamp(), m["checkOut"])
        assertEquals("checked_in", m["status"])
    }

    @Test fun roomUpdate() {
        val m = buildCheckInRoomUpdate(booking(), ST)
        assertEquals(
            setOf("status", "currentGuest", "currentBookingId", "statusUpdatedAt", "cleanliness", "cleanlinessUpdatedAt", "checkoutOverdue"),
            m.keys
        )
        assertEquals("occupied", m["status"])
        assertEquals("Ada Obi", m["currentGuest"])
        assertEquals("bk1", m["currentBookingId"])
        assertEquals(ST, m["statusUpdatedAt"])
        assertEquals("clean", m["cleanliness"])
        assertEquals(ST, m["cleanlinessUpdatedAt"])
        assertEquals(false, m["checkoutOverdue"])
    }

    @Test fun roomIdFallsBackToBookingIdWhenEmpty() {
        assertEquals("room-7", checkInRoomId(booking(roomId = "room-7")))
        assertEquals("bk1", checkInRoomId(booking(roomId = "")))
        assertEquals("bk1", checkInRoomId(booking(roomId = "  ")))
    }

    @Test fun checkInDocument() {
        val m = buildCheckInDocument(booking(), form(), ENTITLED, "u1", "Rita", ST)
        assertEquals(
            setOf(
                "bookingId", "roomId", "roomNumber", "guestName", "guestEmail", "guestPhone", "idDocumentRef", "checkInTime",
                "checkInAt", "entitledCheckOutAt", "staffId", "staffName", "notes", "isDeleted"
            ),
            m.keys
        )
        assertEquals("bk1", m["bookingId"])
        assertEquals("room-7", m["roomId"])
        assertEquals("101", m["roomNumber"])
        assertEquals("Ada Obi", m["guestName"])
        assertEquals("ada@x.com", m["guestEmail"])
        assertEquals("0803", m["guestPhone"])
        assertEquals("NIN-123", m["idDocumentRef"])
        assertEquals(ST, m["checkInTime"])
        assertEquals(CHECK_IN.toFirebaseTimestamp(), m["checkInAt"])
        assertEquals(ENTITLED.toFirebaseTimestamp(), m["entitledCheckOutAt"])
        assertEquals("u1", m["staffId"])
        assertEquals("Rita", m["staffName"])
        assertEquals("Late arrival", m["notes"])
        assertEquals(false, m["isDeleted"])
    }

    // ---- payments -----------------------------------------------------------------------------------

    @Test fun paymentOnlyWhenPayingNow() {
        val m = buildCheckInPayment(booking(), form(method = PaymentMethod.BANK_TRANSFER), "u1", "Rita", ST)
        assertNotNull(m)
        m!!
        assertEquals(
            setOf(
                "bookingId", "guestName", "roomNumber", "amount", "paymentMethod", "type", "recordedBy", "recordedByName",
                "createdAt", "approvalStatus", "approvedBy", "approvedByName", "approvedAt", "rejectedReason", "isDeleted"
            ),
            m.keys
        )
        assertEquals(50_000.0, m["amount"])
        assertEquals("bank_transfer", m["paymentMethod"])
        assertEquals("room_payment", m["type"])
        assertEquals("u1", m["recordedBy"])
        assertEquals("Rita", m["recordedByName"])
        assertEquals(ST, m["createdAt"])
        assertEquals("pending", m["approvalStatus"])
        assertNull(m["approvedBy"]); assertNull(m["approvedByName"]); assertNull(m["approvedAt"]); assertNull(m["rejectedReason"])
        assertEquals(false, m["isDeleted"])

        assertNull(buildCheckInPayment(booking(), form(PaymentOption.PAY_AT_CHECK_OUT), "u1", "Rita", ST))
    }

    // ---- Best-effort step payloads ------------------------------------------------------------------

    @Test fun realtimeRoomStatusAndActivity() {
        val room = buildRoomStatusRealtime("Ada Obi", 1234L)
        assertEquals(mapOf<String, Any?>("status" to "occupied", "currentGuest" to "Ada Obi", "updatedAt" to 1234L), room)

        val feed = buildCheckInActivityItem("Rita", "Ada Obi", "101", 1234L)
        assertEquals(
            mapOf<String, Any?>("type" to "check_in", "text" to "Rita checked in Ada Obi (Room 101)", "at" to 1234L, "by" to "Rita"),
            feed
        )
    }

    @Test fun auditMaps() {
        val before = buildCheckInAuditBefore(Timestamp(2_000, 0))
        assertEquals(mapOf<String, Any?>("status" to "confirmed", "checkOut" to Timestamp(2_000, 0)), before)
        val after = buildCheckInAuditAfter(CHECK_IN, ENTITLED)
        assertEquals(
            mapOf<String, Any?>(
                "status" to "checked_in",
                "checkInAt" to CHECK_IN.toFirebaseTimestamp(),
                "checkOut" to ENTITLED.toFirebaseTimestamp()
            ),
            after
        )
    }

    @Test fun pushIdsAreTwentyCharsAndTimeOrdered() {
        val a = newActivityPushId(1_000_000L, Random(1))
        val b = newActivityPushId(2_000_000L, Random(1))
        assertEquals(20, a.length)
        assertTrue(a < b)
    }
}
