package com.westly.nbms.features.laundry

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LaundryModelsTest {

    @Test fun anEmptyDocumentGetsTheDefaults() {
        val r = parseLaundryRequest("a", emptyMap())
        assertEquals("a", r.id)
        assertNull(r.guestName); assertNull(r.roomNumber); assertNull(r.itemsDescription); assertNull(r.notes)
        assertEquals(1, r.itemCount)
        assertEquals(0.0, r.charge, 0.0)
        assertEquals(PaymentStatus.UNPAID, r.paymentStatus)
        assertEquals(LaundryStatus.RECEIVED, r.status)
        assertEquals("—", r.laundryValetName)
        assertNull(r.laundryValetId); assertNull(r.createdAt); assertNull(r.receivedAt); assertNull(r.deliveredAt)
        assertFalse(r.isDeleted)
    }

    @Test fun aFullDocumentIsReadAsWritten() {
        val ts = Timestamp(1_700_000_000L, 5)
        val r = parseLaundryRequest(
            "b",
            mapOf(
                "guestName" to "Mr Okoro", "roomNumber" to "201", "itemsDescription" to "3 shirts", "itemCount" to 5L,
                "charge" to 4000, "paymentMethod" to "card", "paymentStatus" to "paid", "notes" to "Starch",
                "status" to "ironing", "laundryValetId" to "u9", "laundryValetName" to "Ada",
                "createdAt" to ts, "receivedAt" to ts, "deliveredAt" to ts, "isDeleted" to true
            )
        )
        assertEquals("Mr Okoro", r.guestName)
        assertEquals(5, r.itemCount)
        assertEquals(4000.0, r.charge, 0.0)
        assertEquals("card", r.paymentMethod)
        assertEquals(PaymentStatus.PAID, r.paymentStatus)
        assertEquals(LaundryStatus.IRONING, r.status)
        assertEquals("u9", r.laundryValetId)
        assertEquals(Instant.ofEpochSecond(1_700_000_000L, 5), r.createdAt)
        assertNotNull(r.deliveredAt)
        assertTrue(r.isDeleted)
    }

    @Test fun anUnknownStatusIsReceivedAndAnUnknownPaymentStatusIsUnpaid() {
        val r = parseLaundryRequest("c", mapOf("status" to "lost", "paymentStatus" to "maybe"))
        assertEquals(LaundryStatus.RECEIVED, r.status)
        assertEquals(PaymentStatus.UNPAID, r.paymentStatus)
    }

    @Test fun everyKnownStatusKeyIsRecognised() {
        LaundryStatus.entries.forEach { s ->
            assertEquals(s, parseLaundryRequest("x", mapOf("status" to s.key)).status)
        }
    }

    @Test fun wrongTypesNeverThrow() {
        val r = parseLaundryRequest(
            "d",
            mapOf("charge" to "abc", "itemCount" to "zero", "guestName" to 42, "createdAt" to "yesterday", "isDeleted" to "yes", "status" to 7)
        )
        assertEquals(0.0, r.charge, 0.0)
        assertEquals(1, r.itemCount)
        assertNull(r.guestName)
        assertNull(r.createdAt)
        assertFalse(r.isDeleted)
        assertEquals(LaundryStatus.RECEIVED, r.status)
    }

    @Test fun numericTextIsAccepted() {
        val r = parseLaundryRequest("e", mapOf("charge" to " 1500.5 ", "itemCount" to "4"))
        assertEquals(1500.5, r.charge, 0.0)
        assertEquals(4, r.itemCount)
    }

    @Test fun anItemCountBelowOneFallsBackToOne() {
        assertEquals(1, parseLaundryRequest("f", mapOf("itemCount" to 0)).itemCount)
        assertEquals(1, parseLaundryRequest("f", mapOf("itemCount" to -3)).itemCount)
    }

    @Test fun guestOrRoomPrefersTheGuestThenTheRoomThenGuest() {
        assertEquals("Mr Okoro", guestOrRoom(laundry22aRequestOf("1", guest = "Mr Okoro", room = "201")))
        assertEquals("Room 201", guestOrRoom(laundry22aRequestOf("2", guest = null, room = "201")))
        assertEquals("Guest", guestOrRoom(laundry22aRequestOf("3", guest = null, room = null)))
        assertEquals("Room 7", guestOrRoom(laundry22aRequestOf("4", guest = "  ", room = "7")))
    }

    @Test fun statusKeysAndLabelsMatchTheContract() {
        assertEquals(
            listOf("received", "washing", "drying", "ironing", "ready", "delivered", "cancelled"),
            LaundryStatus.entries.map { it.key }
        )
        assertEquals("Ready for Collection", LaundryStatus.READY.label)
        assertEquals("paid", PaymentStatus.PAID.key)
        assertEquals("Unpaid", PaymentStatus.UNPAID.label)
    }
}
