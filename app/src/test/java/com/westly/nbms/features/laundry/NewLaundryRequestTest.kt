package com.westly.nbms.features.laundry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewLaundryRequestTest {

    @Test fun aGuestNameOrARoomNumberIsRequired() {
        assertFalse(laundryHasGuestInfo(LaundryRequestForm()))
        assertFalse(laundryHasGuestInfo(LaundryRequestForm(guestName = "  ", roomNumber = " ")))
        assertTrue(laundryHasGuestInfo(LaundryRequestForm(guestName = "Mr Okoro")))
        assertTrue(laundryHasGuestInfo(LaundryRequestForm(roomNumber = "201")))
    }

    @Test fun theCountFallsBackToOneAndTheChargeToZero() {
        assertEquals(1, parseLaundryItemCount(""))
        assertEquals(1, parseLaundryItemCount("abc"))
        assertEquals(1, parseLaundryItemCount("0"))
        assertEquals(1, parseLaundryItemCount("-4"))
        assertEquals(5, parseLaundryItemCount(" 5 "))
        assertEquals(0.0, parseNewCharge(""), 0.0)
        assertEquals(0.0, parseNewCharge("abc"), 0.0)
        assertEquals(0.0, parseNewCharge("-100"), 0.0)
        assertEquals(4000.0, parseNewCharge("4,000"), 0.0)
        assertEquals(99.5, parseNewCharge("99.5"), 0.0)
    }

    @Test fun theDefaultFormUsesChargeToRoom() {
        val form = LaundryRequestForm()
        assertEquals(LaundryPaymentMethod.ROOM_CHARGE, form.paymentMethod)
        assertEquals("1", form.itemCount)
        assertEquals(listOf("cash", "card", "room_charge", "pay_on_delivery"), LaundryPaymentMethod.entries.map { it.key })
        assertEquals(listOf("Cash", "Card", "Charge to Room", "Pay on Delivery"), LaundryPaymentMethod.entries.map { it.label })
    }

    @Test fun theCreatePayloadHasEveryFieldOfTheSpec() {
        val form = LaundryRequestForm(
            guestName = " Mr Okoro ", roomNumber = "201", items = "3 shirts, 2 trousers", itemCount = "5",
            charge = "4000", paymentMethod = LaundryPaymentMethod.CARD, notes = "Starch"
        )
        val p = buildCreatePayload(form, "u1", "Ada")
        assertEquals("Mr Okoro", p["guestName"])
        assertEquals("201", p["roomNumber"])
        assertEquals("3 shirts, 2 trousers", p["itemsDescription"])
        assertEquals(5, p["itemCount"])
        assertEquals(4000.0, p["charge"])
        assertEquals("card", p["paymentMethod"])
        assertEquals("unpaid", p["paymentStatus"])
        assertEquals("Starch", p["notes"])
        assertEquals("received", p["status"])
        assertEquals("u1", p["laundryValetId"])
        assertEquals("Ada", p["laundryValetName"])
        assertEquals("pending", p["approvalStatus"])
        listOf("approvedBy", "approvedByName", "approvedAt", "rejectedReason", "collectedAt", "deliveredAt").forEach {
            assertTrue("$it must be present", p.containsKey(it))
            assertNull("$it must be null", p[it])
        }
        assertTrue(p["receivedAt"] === LaundryServerTime)
        assertTrue(p["createdAt"] === LaundryServerTime)
        assertEquals(false, p["isDeleted"])
        assertEquals(21, p.size)
    }

    @Test fun blankTextFieldsAreStoredAsNullAndNumbersFallBack() {
        val p = buildCreatePayload(LaundryRequestForm(roomNumber = "201", itemCount = "x", charge = "y"), "u1", "Ada")
        assertNull(p["guestName"])
        assertEquals("201", p["roomNumber"])
        assertNull(p["itemsDescription"])
        assertNull(p["notes"])
        assertEquals(1, p["itemCount"])
        assertEquals(0.0, p["charge"])
        assertEquals("room_charge", p["paymentMethod"])
    }

    @Test fun theLabelIsTheGuestElseTheRoomElseGuest() {
        assertEquals("Mr Okoro", guestOrRoomLabel("Mr Okoro", "201"))
        assertEquals("Room 201", guestOrRoomLabel(null, "201"))
        assertEquals("Room 201", guestOrRoomLabel("", " 201 "))
        assertEquals("Guest", guestOrRoomLabel(null, null))
    }
}
