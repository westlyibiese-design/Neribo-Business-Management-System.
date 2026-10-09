package com.westly.nbms.features.finance

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RevenueNormalizersTest {

    private val ts = Timestamp(1_700_000_000, 0)
    private val instant = Instant.ofEpochSecond(1_700_000_000)

    // ── payments ──

    @Test fun paymentMapsEveryField() {
        val t = normalizePayment(
            RawPayment(
                id = "p1", type = "room_payment", guestName = "Ada Obi", amount = 45000.0, paymentMethod = "transfer",
                createdAt = ts, recordedBy = "u1", recordedByName = "Rita", approvalStatus = "approved",
                approvedBy = "u2", approvedByName = "Sam", approvedAt = ts, rejectedReason = null
            )
        )
        assertEquals("p1", t.id)
        assertEquals(SourceCollection.PAYMENTS, t.source)
        assertEquals(RevenueCategory.ROOM, t.category)
        assertEquals("Room Payment", t.typeLabel)
        assertEquals("Ada Obi", t.guestName)
        assertEquals(45000.0, t.amount, 0.0)
        assertEquals("transfer", t.paymentMethod)
        assertEquals(instant, t.date)
        assertEquals("u1", t.recordedBy)
        assertEquals("Rita", t.recordedByName)
        assertEquals(ApprovalStatus.APPROVED, t.approvalStatus)
        assertEquals("u2", t.approvedBy)
        assertEquals("Sam", t.approvedByName)
        assertEquals(instant, t.approvedAt)
    }

    @Test fun paymentTypesGetTheRightCategoryAndLabel() {
        fun of(type: String?) = normalizePayment(RawPayment(id = "x", type = type))
        assertEquals(RevenueCategory.ROOM, of("room_payment").category)
        assertEquals(RevenueCategory.ROOM, of("walk_in_payment").category)
        assertEquals(RevenueCategory.ROOM, of("deposit").category)
        assertEquals(RevenueCategory.ROOM, of("stay_extension").category)
        assertEquals(RevenueCategory.OTHER, of("refund").category)
        assertEquals(RevenueCategory.OTHER, of("other").category)
        assertEquals(RevenueCategory.OTHER, of("mystery").category)
        assertEquals("Walk-In Payment", of("walk_in_payment").typeLabel)
        assertEquals("Deposit", of("deposit").typeLabel)
        assertEquals("Refund", of("refund").typeLabel)
        assertEquals("Stay Extension", of("stay_extension").typeLabel)
        assertEquals("Other", of("other").typeLabel)
        assertEquals("mystery", of("mystery").typeLabel)
        assertEquals("Payment", of(null).typeLabel)
    }

    @Test fun paymentDefaultsForMissingFields() {
        val t = normalizePayment(RawPayment(id = "p2"))
        assertEquals("—", t.guestName)
        assertEquals(0.0, t.amount, 0.0)
        assertEquals("—", t.paymentMethod)
        assertEquals("—", t.recordedByName)
        assertEquals(ApprovalStatus.PENDING, t.approvalStatus)
        assertNull(t.date)
        assertNull(t.recordedBy)
        assertNull(t.approvedAt)
    }

    @Test fun unknownApprovalStatusIsPending() {
        assertEquals(ApprovalStatus.PENDING, normalizePayment(RawPayment(id = "x", approvalStatus = "weird")).approvalStatus)
        assertEquals(ApprovalStatus.REJECTED, normalizePayment(RawPayment(id = "x", approvalStatus = "rejected")).approvalStatus)
    }

    // ── sales ──

    @Test fun saleMapsFields() {
        val t = normalizeSale(
            RawSale(id = "s1", customerName = "Bola", total = 2500.0, paymentMethod = "cash", createdAt = ts, staffId = "u3", staffName = "Tunde")
        )
        assertEquals(SourceCollection.SALES, t.source)
        assertEquals(RevenueCategory.SALES, t.category)
        assertEquals("Retail Sale", t.typeLabel)
        assertEquals("Bola", t.guestName)
        assertEquals(2500.0, t.amount, 0.0)
        assertEquals("u3", t.recordedBy)
        assertEquals("Tunde", t.recordedByName)
        assertEquals(instant, t.date)
    }

    @Test fun saleDefaults() {
        val t = normalizeSale(RawSale(id = "s2"))
        assertEquals("Walk-in customer", t.guestName)
        assertEquals(0.0, t.amount, 0.0)
        assertEquals("—", t.paymentMethod)
        assertEquals("—", t.recordedByName)
        assertEquals(ApprovalStatus.PENDING, t.approvalStatus)
    }

    // ── restaurant orders ──

    @Test fun orderMapsFields() {
        val t = normalizeOrder(
            RawOrder(id = "o1", customerName = "Chidi", total = 8000.0, paymentMethod = "card", createdAt = ts, waiterId = "u4", waiterName = "Wale")
        )
        assertEquals(SourceCollection.ORDERS, t.source)
        assertEquals(RevenueCategory.RESTAURANT, t.category)
        assertEquals("Restaurant Order", t.typeLabel)
        assertEquals("Chidi", t.guestName)
        assertEquals("u4", t.recordedBy)
        assertEquals("Wale", t.recordedByName)
    }

    @Test fun orderGuestNameFallbacks() {
        assertEquals("Room 204", normalizeOrder(RawOrder(id = "a", roomNumber = "204", tableNumber = "5")).guestName)
        assertEquals("Table 5", normalizeOrder(RawOrder(id = "b", tableNumber = "5")).guestName)
        assertEquals("Guest", normalizeOrder(RawOrder(id = "c")).guestName)
        assertEquals("Guest", normalizeOrder(RawOrder(id = "d", customerName = "  ")).guestName)
        assertEquals("Zed", normalizeOrder(RawOrder(id = "e", customerName = "Zed", roomNumber = "1")).guestName)
    }

    // ── bar orders ──

    @Test fun barOrderMapsFields() {
        val t = normalizeBarOrder(
            RawBarOrder(id = "b1", roomNumber = "310", total = 6000.0, paymentMethod = "cash", createdAt = ts, barAttendantId = "u5", barAttendantName = "Bisi")
        )
        assertEquals(SourceCollection.BAR_ORDERS, t.source)
        assertEquals(RevenueCategory.BAR, t.category)
        assertEquals("Bar Sale", t.typeLabel)
        assertEquals("Room 310", t.guestName)
        assertEquals("u5", t.recordedBy)
        assertEquals("Bisi", t.recordedByName)
    }

    @Test fun barOrderDefaults() {
        val t = normalizeBarOrder(RawBarOrder(id = "b2"))
        assertEquals("Guest", t.guestName)
        assertEquals("—", t.recordedByName)
        assertEquals("Table 2", normalizeBarOrder(RawBarOrder(id = "b3", tableNumber = "2")).guestName)
    }

    // ── laundry ──

    @Test fun laundryMapsFields() {
        val t = normalizeLaundry(
            RawLaundry(id = "l1", guestName = "Ngozi", charge = 3500.0, paymentMethod = "cash", createdAt = ts, laundryValetId = "u6", laundryValetName = "Lola")
        )
        assertEquals(SourceCollection.LAUNDRY_REQUESTS, t.source)
        assertEquals(RevenueCategory.LAUNDRY, t.category)
        assertEquals("Laundry Service", t.typeLabel)
        assertEquals("Ngozi", t.guestName)
        assertEquals(3500.0, t.amount, 0.0)
        assertEquals("u6", t.recordedBy)
        assertEquals("Lola", t.recordedByName)
    }

    @Test fun laundryGuestNameFallbacks() {
        assertEquals("Room 12", normalizeLaundry(RawLaundry(id = "a", roomNumber = "12")).guestName)
        assertEquals("Guest", normalizeLaundry(RawLaundry(id = "b")).guestName)
    }

    @Test fun approvalFieldsCarryThroughForEverySource() {
        val rejected = normalizeSale(RawSale(id = "s", approvalStatus = "rejected", rejectedReason = "Duplicate"))
        assertEquals(ApprovalStatus.REJECTED, rejected.approvalStatus)
        assertEquals("Duplicate", rejected.rejectedReason)
        assertEquals("Reason", normalizeLaundry(RawLaundry(id = "l", rejectedReason = "Reason")).rejectedReason)
    }
}
