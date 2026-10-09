package com.westly.nbms.features.sales

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SalesRepositoryTest {

    private class Rig(signedIn: Boolean = true) {
        val store = FakeSalesStore().apply {
            stock["water"] = StockRead(quantity = 23, minStock = 5, unit = "bottles")
            stock["soap"] = StockRead(quantity = 6, minStock = 5, unit = " ")
        }
        val audit = FakeSalesAudit()
        val notifier = FakeSalesNotifier()
        val repo = SalesRepository(store, FakeSalesSession(signedIn), audit, notifier)
    }

    private val water = CartItem("water", "Bottled Water", 260.0, 2, 23)
    private val soap = CartItem("soap", "Soap", 100.0, 1, 6)
    private val basket = CartItem("manual-1-abc123", "Gift basket", 5000.0, 1, UNLIMITED_STOCK, true)

    @Test fun catalogSaleReducesStockOnceAndCreatesPendingSale() = runTest {
        val r = Rig()
        val result = r.repo.sell(listOf(water), "  Mrs Ade ", PaymentMethod.CASH, null)
        assertEquals(21, r.store.stock.getValue("water").quantity)
        assertEquals(1, r.store.transactions)
        val doc = r.store.sales.getValue(result.saleId)
        assertEquals("u1", doc["staffId"]); assertEquals("Sam", doc["staffName"])
        assertEquals("Mrs Ade", doc["customerName"])
        assertEquals(520.0, doc["total"]); assertEquals("cash", doc["paymentMethod"])
        assertEquals("merchandise", doc["category"]); assertEquals("pending", doc["approvalStatus"])
        assertEquals(false, doc["hasManualItems"]); assertEquals(false, doc["isDeleted"])
        assertNull(doc["notes"]); assertNull(doc["approvedBy"]); assertNull(doc["rejectedReason"])
        assertTrue(doc["createdAt"] === SalesServerTime)
        @Suppress("UNCHECKED_CAST") val line = (doc["items"] as List<Map<String, Any?>>).single()
        assertEquals(mapOf("id" to "water", "name" to "Bottled Water", "price" to 260.0, "quantity" to 2, "subtotal" to 520.0, "isManual" to false), line)
    }

    @Test fun manualSaleNeverTouchesInventoryButStillCreatesSale() = runTest {
        val r = Rig()
        val result = r.repo.sell(listOf(basket), "", PaymentMethod.CARD, null)
        assertEquals(23, r.store.stock.getValue("water").quantity)
        val doc = r.store.sales.getValue(result.saleId)
        assertEquals(true, doc["hasManualItems"]); assertNull(doc["customerName"])
        assertEquals(5000.0, doc["total"])
        assertTrue(r.notifier.calls.none { it.type == "low_inventory" })
    }

    @Test fun insufficientStockAbortsEverything() = runTest {
        val r = Rig()
        val tooMany = water.copy(quantity = 24)
        try {
            r.repo.sell(listOf(soap, tooMany), null, PaymentMethod.CASH, null)
            fail("expected insufficient stock")
        } catch (e: IllegalStateException) {
            assertEquals("Insufficient stock for \"Bottled Water\": only 23 left.", e.message)
        }
        assertEquals(6, r.store.stock.getValue("soap").quantity)
        assertEquals(23, r.store.stock.getValue("water").quantity)
        assertTrue(r.store.sales.isEmpty())
        assertTrue(r.audit.entries.isEmpty()); assertTrue(r.notifier.calls.isEmpty())
    }

    @Test fun missingOrDeletedItemAborts() = runTest {
        val r = Rig()
        r.store.deleted += "soap"
        try {
            r.repo.sell(listOf(water, soap), null, PaymentMethod.CASH, null)
            fail("expected not found")
        } catch (e: SalesException) {
            assertEquals("Item \"Soap\" not found in inventory.", e.message)
        }
        assertEquals(23, r.store.stock.getValue("water").quantity)
        assertTrue(r.store.sales.isEmpty())
    }

    @Test fun usesLiveStockNotTheOnScreenNumber() = runTest {
        val r = Rig()
        r.store.stock["water"] = StockRead(quantity = 1, minStock = 5, unit = "bottles") // sold elsewhere meanwhile
        try {
            r.repo.sell(listOf(water), null, PaymentMethod.CASH, null)
            fail("expected insufficient stock")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.endsWith("only 1 left."))
        }
    }

    @Test fun auditAndSaleAlertAreSent() = runTest {
        val r = Rig()
        val result = r.repo.sell(listOf(water), null, PaymentMethod.POS, null)
        assertEquals(listOf("new_sale", "sales", result.saleId, mapOf("total" to 520.0)), r.audit.entries.single())
        val alert = r.notifier.calls.single { it.type == "new_sale" }
        assertTrue(alert.message.contains("Sam"))
    }

    @Test fun lowStockAlertOnlyForLinesAtOrBelowMinimum() = runTest {
        val r = Rig()
        // water 23-2=21 (> 5, no alert); soap 6-1=5 (== min, alert, blank unit -> "units")
        r.repo.sell(listOf(water, soap), null, PaymentMethod.CASH, null)
        val low = r.notifier.calls.filter { it.type == "low_inventory" }
        assertEquals(1, low.size)
        assertTrue(low.single().message.contains("Soap"))
        assertTrue(low.single().message.contains("units"))
    }

    @Test fun failingExtrasNeverHideTheSuccess() = runTest {
        val r = Rig()
        r.audit.fail = true; r.notifier.fail = true
        val result = r.repo.sell(listOf(water, soap), null, PaymentMethod.CASH, null)
        assertEquals(1, r.store.sales.size)
        assertEquals(result.total, 620.0, 0.0)
        assertFalse(r.store.stock.getValue("soap").quantity != 5)
    }

    @Test fun emptyCartAndSignedOutAreRejected() = runTest {
        try { Rig().repo.sell(emptyList(), null, PaymentMethod.CASH, null); fail() } catch (e: SalesException) { assertEquals("The cart is empty.", e.message) }
        val out = Rig(signedIn = false)
        try { out.repo.sell(listOf(water), null, PaymentMethod.CASH, null); fail() } catch (e: SalesException) { assertEquals("Not signed in", e.message) }
        assertEquals(0, out.store.transactions)
    }
}
