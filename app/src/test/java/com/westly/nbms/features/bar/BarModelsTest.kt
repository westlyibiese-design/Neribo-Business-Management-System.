package com.westly.nbms.features.bar

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BarModelsTest {

    @Test fun enumKeysAndLabelsAreExactlyThePartInterface() {
        assertEquals(
            listOf("beer" to "Beer", "wine" to "Wine", "spirits" to "Spirits", "cocktails" to "Cocktails", "soft_drinks" to "Soft Drinks", "other" to "Other"),
            DrinkCategory.entries.map { it.key to it.label }
        )
        assertEquals(
            listOf("pending" to "Pending", "served" to "Served", "cancelled" to "Cancelled"),
            BarSaleStatus.entries.map { it.key to it.label }
        )
        assertEquals(
            listOf("cash" to "Cash", "card" to "Card", "bank_transfer" to "Bank Transfer", "pos" to "POS Terminal", "room_charge" to "Charge to Room"),
            BarPaymentMethod.entries.map { it.key to it.label }
        )
    }

    @Test fun aFullDocumentIsReadFieldForField() {
        val doc = mapOf<String, Any?>(
            "barAttendantId" to "u1", "barAttendantName" to "Wale", "customerName" to "Ada", "roomNumber" to "201", "tableNumber" to "B-03",
            "items" to listOf(
                mapOf("id" to "a1", "name" to "Heineken 60cl", "price" to 1500.0, "quantity" to 2L, "subtotal" to 3000.0, "isManual" to false),
                mapOf("id" to "manual-1-abc123", "name" to "Special cocktail", "price" to 3000, "quantity" to 1, "subtotal" to 3000, "isManual" to true)
            ),
            "total" to 6000.0, "paymentMethod" to "room_charge", "notes" to "Ice", "hasManualItems" to true,
            "status" to "served", "createdAt" to Timestamp(1_700_000_000L, 5_000), "isDeleted" to false
        )
        val sale = parseBarSale("s1", doc)
        assertEquals("s1", sale.id)
        assertEquals("u1", sale.barAttendantId); assertEquals("Wale", sale.barAttendantName)
        assertEquals("Ada", sale.customerName); assertEquals("201", sale.roomNumber); assertEquals("B-03", sale.tableNumber)
        assertEquals(6000.0, sale.total, 0.0); assertEquals("room_charge", sale.paymentMethod); assertEquals("Ice", sale.notes)
        assertTrue(sale.hasManualItems); assertEquals(BarSaleStatus.SERVED, sale.status); assertFalse(sale.isDeleted)
        assertEquals(Instant.ofEpochSecond(1_700_000_000L, 5_000), sale.createdAt)
        assertEquals(
            listOf(
                BarSaleLine("a1", "Heineken 60cl", 1500.0, 2, 3000.0, false),
                BarSaleLine("manual-1-abc123", "Special cocktail", 3000.0, 1, 3000.0, true)
            ),
            sale.items
        )
    }

    @Test fun anEmptyDocumentGivesZerosEmptyTextAndNulls() {
        val sale = parseBarSale("s2", emptyMap())
        assertEquals("s2", sale.id)
        assertEquals("", sale.barAttendantId); assertEquals("", sale.barAttendantName); assertEquals("", sale.paymentMethod)
        assertNull(sale.customerName); assertNull(sale.roomNumber); assertNull(sale.tableNumber); assertNull(sale.notes)
        assertEquals(0.0, sale.total, 0.0)
        assertTrue(sale.items.isEmpty())
        assertFalse(sale.hasManualItems); assertFalse(sale.isDeleted)
        assertNull(sale.createdAt)
        assertEquals(BarSaleStatus.PENDING, sale.status)
    }

    @Test fun anUnknownOrMissingStatusIsPending() {
        assertEquals(BarSaleStatus.PENDING, parseBarSale("a", mapOf("status" to "preparing")).status)
        assertEquals(BarSaleStatus.PENDING, parseBarSale("a", mapOf("status" to 5)).status)
        assertEquals(BarSaleStatus.PENDING, parseBarSale("a", mapOf("status" to null)).status)
        assertEquals(BarSaleStatus.CANCELLED, parseBarSale("a", mapOf("status" to "cancelled")).status)
    }

    @Test fun aMissingOrNonListItemsFieldIsEmpty() {
        assertTrue(parseBarSale("a", mapOf("items" to null)).items.isEmpty())
        assertTrue(parseBarSale("a", mapOf("items" to "oops")).items.isEmpty())
        assertTrue(parseBarSale("a", mapOf("items" to mapOf("id" to "x"))).items.isEmpty())
    }

    @Test fun lineEntriesThatAreNotMapsAreSkippedAndMissingLineFieldsAreZero() {
        val sale = parseBarSale("a", mapOf("items" to listOf("junk", 7, null, mapOf("name" to "Water"))))
        assertEquals(listOf(BarSaleLine("", "Water", 0.0, 0, 0.0, false)), sale.items)
    }

    @Test fun numbersMayBeIntegersOrNumericTextAndNeverThrow() {
        val sale = parseBarSale("a", mapOf("total" to "2500.50", "items" to listOf(mapOf("price" to "abc", "quantity" to "3", "subtotal" to Double.NaN))))
        assertEquals(2500.5, sale.total, 0.0)
        val line = sale.items.single()
        assertEquals(0.0, line.price, 0.0); assertEquals(3, line.quantity); assertEquals(0.0, line.subtotal, 0.0)
    }

    @Test fun wrongTypesNeverThrow() {
        val sale = parseBarSale("a", mapOf(
            "barAttendantName" to 5, "customerName" to 7, "total" to listOf(1), "createdAt" to "yesterday",
            "hasManualItems" to "yes", "isDeleted" to "no"
        ))
        assertEquals("", sale.barAttendantName); assertNull(sale.customerName); assertEquals(0.0, sale.total, 0.0)
        assertNull(sale.createdAt); assertFalse(sale.hasManualItems); assertFalse(sale.isDeleted)
    }

    @Test fun aDeletedFlagIsKept() {
        assertTrue(parseBarSale("a", mapOf("isDeleted" to true)).isDeleted)
    }
}
