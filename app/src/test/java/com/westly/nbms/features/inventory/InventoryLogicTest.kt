package com.westly.nbms.features.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InventoryLogicTest {

    // ── Westly's four unit tests for the stock guard ──

    @Test fun enoughStockPasses() {
        InventoryLogic.assertSufficientStock(10, 3, "Soap")
    }

    @Test fun exactlyEqualIsAllowed() {
        InventoryLogic.assertSufficientStock(5, 5, "Soap")
    }

    @Test fun lessThanRequestedThrowsWithTheWestlyMessage() {
        try {
            InventoryLogic.assertSufficientStock(2, 5, "Soap")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Insufficient stock for \"Soap\": only 2 left.", e.message)
        }
    }

    @Test fun zeroStockWithAnyRequestThrows() {
        try {
            InventoryLogic.assertSufficientStock(0, 1, "Towels")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Insufficient stock for \"Towels\": only 0 left.", e.message)
        }
    }

    // ── low stock ──

    @Test fun anItemIsLowWhenAtOrBelowItsMinimum() {
        assertTrue(InventoryLogic.isLow(invItem("a", quantity = 3, minStock = 5)))
        assertTrue(InventoryLogic.isLow(invItem("b", quantity = 5, minStock = 5)))
        assertFalse(InventoryLogic.isLow(invItem("c", quantity = 6, minStock = 5)))
        assertTrue(InventoryLogic.isLow(invItem("d", quantity = 0, minStock = 0)))
    }
}
