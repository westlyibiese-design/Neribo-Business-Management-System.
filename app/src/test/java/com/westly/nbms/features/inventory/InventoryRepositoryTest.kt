package com.westly.nbms.features.inventory

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InventoryRepositoryTest {

    private class Rig {
        val store = FakeInventoryStore()
        val audit = FakeInventoryAudit()
        val repo = InventoryRepository(store, audit)
    }

    private fun form(
        name: String = "  Bottled Water ",
        category: String = "drinks",
        unit: String = "bottles",
        quantity: String = "3",
        minStock: String = "5",
        cost: String = "200",
        supplier: String = ""
    ) = AddItemForm(name, category, unit, quantity, minStock, cost, supplier)

    // ── the new document ──

    @Test fun theNewDocumentHasEveryWestlyField() {
        val payload = buildAddItemPayload(form(supplier = " Coca Dist "))
        assertEquals(
            mapOf<String, Any?>(
                "name" to "Bottled Water",
                "category" to "drinks",
                "quantity" to 3,
                "minStock" to 5,
                "unit" to "bottles",
                "costPerUnit" to 200.0,
                "supplier" to "Coca Dist",
                "isDeleted" to false,
                "lastRestocked" to InventoryServerTime,
                "createdAt" to InventoryServerTime
            ),
            payload
        )
    }

    @Test fun blankCostIsZeroAndBlankSupplierIsNull() {
        val payload = buildAddItemPayload(form(cost = "  ", supplier = "   "))
        assertEquals(0.0, payload["costPerUnit"] as Double, 0.0)
        assertNull(payload["supplier"])
        assertTrue(payload.containsKey("supplier"))
    }

    @Test fun aBlankUnitFallsBackToPcsAndAnUnknownCategoryToHotelSupplies() {
        val payload = buildAddItemPayload(form(unit = "  ", category = "weird"))
        assertEquals("pcs", payload["unit"])
        assertEquals("hotel_supplies", payload["category"])
    }

    @Test fun theFormStartsAtWestlysDefaults() {
        val blank = AddItemForm()
        assertEquals("hotel_supplies", blank.categoryKey)
        assertEquals("pcs", blank.unit)
        assertEquals("", blank.name)
        assertEquals("", blank.quantityText)
        assertEquals("", blank.minStockText)
        assertEquals("", blank.costText)
        assertEquals("", blank.supplier)
    }

    // ── the form checks ──

    @Test fun nameQuantityAndMinStockAreRequired() {
        assertTrue(validateAddItemForm(form(name = "   ")).name != null)
        assertTrue(validateAddItemForm(form(quantity = "")).quantity != null)
        assertTrue(validateAddItemForm(form(minStock = "")).minStock != null)
        assertFalse(validateAddItemForm(form()).any)
    }

    @Test fun quantitiesAreWholeNumbersOfZeroOrMore() {
        assertEquals(0, parseWholeNumber("0"))
        assertEquals(42, parseWholeNumber(" 42 "))
        assertNull(parseWholeNumber(""))
        assertNull(parseWholeNumber("1.5"))
        assertNull(parseWholeNumber("-3"))
        assertNull(parseWholeNumber("abc"))
        assertNull(parseWholeNumber("99999999999"))
        assertTrue(validateAddItemForm(form(quantity = "2.5")).quantity != null)
        assertTrue(validateAddItemForm(form(minStock = "-1")).minStock != null)
    }

    @Test fun costIsADecimalOfZeroOrMoreAndOptional() {
        assertEquals(0.0, parseCost("")!!, 0.0)
        assertEquals(0.0, parseCost("0")!!, 0.0)
        assertEquals(199.5, parseCost("199.5")!!, 0.0)
        assertNull(parseCost("-1"))
        assertNull(parseCost("abc"))
        assertTrue(validateAddItemForm(form(cost = "1.2.3")).cost != null)
        assertNull(validateAddItemForm(form(cost = "")).cost)
    }

    @Test fun typingKeepsOnlyDigitsAndOneDot() {
        assertEquals("123", filterWholeNumberInput("1a2.3"))
        assertEquals("123456789", filterWholeNumberInput("1234567890123"))
        assertEquals("12.5", filterCostInput("1a2.5"))
        assertEquals("1.25", filterCostInput("1.2.5"))
        assertEquals("", filterCostInput("abc"))
    }

    // ── adding ──

    @Test fun addWritesTheDocumentAndLogsInventoryAdded() = runTest {
        val rig = Rig()
        val id = rig.repo.add(form())
        assertEquals("new1", id)
        assertEquals(1, rig.store.adds.size)
        assertEquals("Bottled Water", rig.store.adds[0]["name"])
        assertEquals(
            listOf(FakeInventoryAudit.Entry("inventory_added", "inventory", "new1", null, mapOf("name" to "Bottled Water"))),
            rig.audit.entries
        )
    }

    @Test fun anInvalidFormWritesNothing() = runTest {
        val rig = Rig()
        try {
            rig.repo.add(form(name = ""))
            fail("expected InventoryException")
        } catch (e: InventoryException) {
            assertEquals("Item name is required.", e.message)
        }
        assertTrue(rig.store.adds.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailedAddWritesNoAuditEntry() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("offline")
        try {
            rig.repo.add(form())
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("offline", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailingAuditNeverTurnsASavedItemIntoAnError() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        assertEquals("new1", rig.repo.add(form()))
        assertEquals(1, rig.store.adds.size)
    }

    // ── restocking ──

    @Test fun restockAddsToTheLiveQuantityAndAuditsBeforeAndAfter() = runTest {
        val rig = Rig()
        rig.store.live["w"] = 3
        val result = rig.repo.restock(invItem("w", name = "Bottled Water", quantity = 3), 20)
        assertEquals(RestockResult(before = 3, after = 23), result)
        assertEquals(23, rig.store.live["w"])
        assertEquals(
            listOf(FakeInventoryAudit.Entry("inventory_restocked", "inventory", "w", mapOf("quantity" to 3), mapOf("quantity" to 23))),
            rig.audit.entries
        )
    }

    @Test fun restockUsesTheLiveQuantityNotTheStaleNumberOnScreen() = runTest {
        val rig = Rig()
        // The screen still shows 3, but someone else already restocked: the database has 10.
        rig.store.live["w"] = 10
        val result = rig.repo.restock(invItem("w", quantity = 3), 5)
        assertEquals(RestockResult(before = 10, after = 15), result)
        assertEquals(15, rig.store.live["w"])
    }

    @Test fun twoQuickRestocksAddUp() = runTest {
        val rig = Rig()
        rig.store.live["w"] = 3
        val stale = invItem("w", quantity = 3)   // both people are looking at the same stale screen
        val first = rig.repo.restock(stale, 20)
        val second = rig.repo.restock(stale, 5)
        assertEquals(RestockResult(3, 23), first)
        assertEquals(RestockResult(23, 28), second)
        assertEquals(28, rig.store.live["w"])
        assertEquals(
            listOf(mapOf("quantity" to 3), mapOf("quantity" to 23)),
            rig.audit.entries.map { it.previous }
        )
        assertEquals(
            listOf(mapOf("quantity" to 23), mapOf("quantity" to 28)),
            rig.audit.entries.map { it.new }
        )
    }

    @Test fun anAmountBelowOneDoesNothing() = runTest {
        val rig = Rig()
        rig.store.live["w"] = 3
        for (bad in listOf(0, -4)) {
            try {
                rig.repo.restock(invItem("w", quantity = 3), bad)
                fail("expected InventoryException")
            } catch (e: InventoryException) {
                assertEquals(MSG_RESTOCK_AMOUNT, e.message)
            }
        }
        assertTrue(rig.store.restocks.isEmpty())
        assertEquals(3, rig.store.live["w"])
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun restockingAMissingItemFailsWithAClearMessageAndNoAudit() = runTest {
        val rig = Rig()
        try {
            rig.repo.restock(invItem("ghost"), 5)
            fail("expected InventoryException")
        } catch (e: InventoryException) {
            assertEquals("This item no longer exists.", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailingAuditNeverUndoesARestock() = runTest {
        val rig = Rig()
        rig.store.live["w"] = 1
        rig.audit.fail = true
        assertEquals(RestockResult(1, 6), rig.repo.restock(invItem("w", quantity = 1), 5))
        assertEquals(6, rig.store.live["w"])
    }

    @Test fun theRestockMathAndFields() {
        assertEquals(23, restockedQuantity(3, 20))
        assertEquals(1, restockedQuantity(0, 1))
        try {
            restockedQuantity(Int.MAX_VALUE, 1)
            fail("expected InventoryException")
        } catch (e: InventoryException) {
            assertEquals(MSG_QUANTITY_TOO_LARGE, e.message)
        }
        val fields = buildRestockFields(23)
        assertEquals(setOf("quantity", "lastRestocked", "updatedAt"), fields.keys)
        assertEquals(23, fields["quantity"])
        assertSame(InventoryServerTime, fields["lastRestocked"])
        assertSame(InventoryServerTime, fields["updatedAt"])
    }

    @Test fun theRestockDialogReadsTheAmountLikeWestly() {
        assertEquals(20, parseRestockAmount("20"))
        assertEquals(1, parseRestockAmount(" 1 "))
        assertNull(parseRestockAmount(""))
        assertNull(parseRestockAmount("0"))
        assertNull(parseRestockAmount("abc"))
        assertNull(parseRestockAmount("-5"))
        assertNull(parseRestockAmount("2.5"))
        assertTrue(restockEnabled("5", saving = false))
        assertFalse(restockEnabled("", saving = false))
        assertFalse(restockEnabled("  ", saving = false))
        assertFalse(restockEnabled("5", saving = true))
    }

    // ── observing ──

    @Test fun observeLeavesDeletedItemsOutAndKeepsTheStoreOrder() = runTest {
        val rig = Rig()
        rig.store.items.value = Resource.Success(
            listOf(invItem("b"), invItem("gone", deleted = true), invItem("a"))
        )
        @Suppress("UNCHECKED_CAST")
        val shown = rig.repo.observe().first() as Resource.Success<List<InventoryItem>>
        assertEquals(listOf("b", "a"), shown.data.map { it.id })
    }

    @Test fun observePassesLoadingAndErrorsThrough() = runTest {
        val rig = Rig()
        rig.store.items.value = Resource.Loading
        assertEquals(Resource.Loading, rig.repo.observe().first())
        rig.store.items.value = Resource.Error("nope")
        assertEquals(Resource.Error("nope"), rig.repo.observe().first())
    }
}
