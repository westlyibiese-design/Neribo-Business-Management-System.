package com.westly.nbms.features.bar

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BarInventoryRepositoryTest {

    private class Rig {
        val store = BarHistoryFakeStockStore()
        val audit = BarHistoryFakeAudit()
        val repo = BarInventoryRepository(store, audit)
    }

    private suspend fun failureOf(block: suspend () -> Unit): String? =
        try {
            block()
            null
        } catch (e: BarStockException) {
            e.message
        }

    // ── observe ──

    @Test fun observeShowsOnlyDrinksThatAreNotDeleted() = runTest {
        val r = Rig()
        r.store.items.value = Resource.Success(
            listOf(
                barHistoryItemOf("1", "Wine"), barHistoryItemOf("2", "Soap", category = "toiletries"),
                barHistoryItemOf("3", "Old beer", deleted = true), barHistoryItemOf("4", "Beer")
            )
        )
        val result = r.repo.observe().first() as Resource.Success
        assertEquals(listOf("4", "1"), result.data.map { it.id })
    }

    @Test fun observePassesLoadingAndErrorsThrough() = runTest {
        val r = Rig()
        r.store.items.value = Resource.Error("boom")
        assertTrue(r.repo.observe().first() is Resource.Error)
    }

    // ── add ──

    @Test fun addWritesOneDrinksDocumentAndAnAuditEntry() = runTest {
        val r = Rig()
        val id = r.repo.add(BarStockForm("Heineken 60cl", "24", "bottles", "850", "6"))
        assertEquals("new1", id)
        assertEquals(1, r.store.adds.size)
        val doc = r.store.adds[0]
        assertEquals("drinks", doc["category"])
        assertEquals("bottles", doc["unit"])
        assertEquals(24, doc["quantity"])
        assertEquals(6, doc["minStock"])
        assertEquals(1, r.audit.entries.size)
        val entry = r.audit.entries[0]
        assertEquals("bar_inventory_added", entry.action)
        assertEquals("inventory", entry.collection)
        assertEquals("new1", entry.documentId)
        assertEquals("Heineken 60cl", entry.new?.get("name"))
    }

    @Test fun addWithAnInvalidFormWritesNothing() = runTest {
        val r = Rig()
        assertEquals("Item name is required.", failureOf { r.repo.add(BarStockForm("", "24", "bottles", "", "6")) })
        assertEquals("Enter a whole number of 0 or more.", failureOf { r.repo.add(BarStockForm("Wine", "x", "bottles", "", "6")) })
        assertTrue(r.store.adds.isEmpty())
        assertTrue(r.audit.entries.isEmpty())
    }

    @Test fun aFailedAddSurfacesTheServerMessageAndLogsNothing() = runTest {
        val r = Rig()
        r.store.failWith = IllegalStateException("Missing or insufficient permissions.")
        try {
            r.repo.add(BarStockForm("Wine", "1", "bottles", "", "0"))
            org.junit.Assert.fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertEquals("Missing or insufficient permissions.", e.message)
        }
        assertTrue(r.audit.entries.isEmpty())
    }

    @Test fun anAuditFailureNeverTurnsASavedAddIntoAnError() = runTest {
        val r = Rig()
        r.audit.fail = true
        assertEquals("new1", r.repo.add(BarStockForm("Wine", "1", "bottles", "", "0")))
        assertEquals(1, r.store.adds.size)
    }

    // ── restock ──

    @Test fun restockAddsToTheLiveQuantityNotTheNumberOnScreen() = runTest {
        val r = Rig()
        r.store.live["h1"] = 30 // someone else restocked since the screen loaded: the screen still shows 24
        val onScreen = barHistoryItemOf("h1", quantity = 24)
        val result = r.repo.restock(onScreen, 12)
        assertEquals(30, result.before)
        assertEquals(42, result.after)
        assertEquals(42, r.store.live["h1"])
        assertEquals(listOf("h1" to 12), r.store.restocks)
    }

    @Test fun restockTwentyFourPlusTwelveIsThirtySix() = runTest {
        val r = Rig()
        r.store.live["h1"] = 24
        assertEquals(36, r.repo.restock(barHistoryItemOf("h1", quantity = 24), 12).after)
    }

    @Test fun restockAuditsTheCommittedNumbers() = runTest {
        val r = Rig()
        r.store.live["h1"] = 30
        r.repo.restock(barHistoryItemOf("h1", quantity = 24), 12)
        val entry = r.audit.entries.single()
        assertEquals("bar_inventory_restocked", entry.action)
        assertEquals("inventory", entry.collection)
        assertEquals("h1", entry.documentId)
        assertEquals(30, entry.previous?.get("quantity"))
        assertEquals(42, entry.new?.get("quantity"))
    }

    @Test fun restockNeedsAQuantityOfAtLeastOne() = runTest {
        val r = Rig()
        r.store.live["h1"] = 24
        assertEquals("Enter a whole number of 1 or more.", failureOf { r.repo.restock(barHistoryItemOf("h1"), 0) })
        assertEquals("Enter a whole number of 1 or more.", failureOf { r.repo.restock(barHistoryItemOf("h1"), -2) })
        assertTrue(r.store.restocks.isEmpty())
        assertTrue(r.audit.entries.isEmpty())
    }

    @Test fun restockOfAnItemThatNoLongerExistsSaysSo() = runTest {
        val r = Rig()
        assertEquals("This item no longer exists.", failureOf { r.repo.restock(barHistoryItemOf("gone"), 5) })
        assertTrue(r.audit.entries.isEmpty())
    }

    @Test fun anAuditFailureNeverTurnsASavedRestockIntoAnError() = runTest {
        val r = Rig()
        r.audit.fail = true
        r.store.live["h1"] = 1
        assertEquals(6, r.repo.restock(barHistoryItemOf("h1", quantity = 1), 5).after)
    }

    @Test fun addDoesNotTouchStockOfAnyOtherItem() = runTest {
        val r = Rig()
        r.store.live["h1"] = 10
        r.repo.add(BarStockForm("Wine", "5", "bottles", "", "1"))
        assertEquals(10, r.store.live["h1"])
        assertNull(r.store.live["new1"])
        assertSame(r.store.adds[0]["lastRestocked"], BarStockServerTime)
    }
}
