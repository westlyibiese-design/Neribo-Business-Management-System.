package com.westly.nbms.features.restaurant

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A store that behaves like the real one: it holds the LIVE array, and `save` applies the change to that array
 * (not to anything on a screen), exactly as the transaction does.
 */
internal class FakeMenuStore(initial: List<Any?> = emptyList()) : MenuStore {
    var live: List<Any?> = initial
    val shown = MutableStateFlow<Resource<List<MenuItem>>>(Resource.Success(emptyList()))
    val payloads = mutableListOf<Map<String, Any?>>()
    var failWith: Exception? = null

    override fun observe(): Flow<Resource<List<MenuItem>>> = shown

    override suspend fun save(change: MenuChange) {
        failWith?.let { throw it }
        val next = applyMenuChange(live, change)
        payloads += buildMenuPayload(next)
        live = next
    }
}

internal class FakeMenuAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val documentId: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)

    val entries = mutableListOf<Entry>()
    var fail = false

    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

class MenuRepositoryTest {

    private class Rig(initial: List<Any?> = emptyList()) {
        val store = FakeMenuStore(initial)
        val audit = FakeMenuAudit()
        val repo = MenuRepository(store, audit)
    }

    private fun item(id: String, name: String = "Item $id", price: Double = 1000.0, available: Boolean = true) =
        MenuItem(id, name, "", "", price, "lunch", available)

    @Test fun observeServesWhatTheStoreShows() = runTest {
        val rig = Rig()
        rig.store.shown.value = Resource.Success(listOf(item("a")))
        val first = rig.repo.observe().first()
        assertEquals(Resource.Success(listOf(item("a"))), first)
    }

    @Test fun anEmptyMenuIsSuccessWithNoItems() = runTest {
        val first = Rig().repo.observe().first()
        assertEquals(Resource.Success(emptyList<MenuItem>()), first)
    }

    @Test fun aReadFailureIsAnError() = runTest {
        val rig = Rig()
        rig.store.shown.value = Resource.Error("You don't have access to this data.")
        assertTrue(rig.repo.observe().first() is Resource.Error)
    }

    @Test fun anAddWritesTheNewArrayAndThenAudits() = runTest {
        val rig = Rig()
        rig.repo.save(MenuChange.Add(item("a", name = "Jollof Rice", price = 3500.0)))
        val written = rig.store.payloads.single()
        assertEquals(setOf("data", "updatedAt"), written.keys)
        assertEquals(listOf(menuItemToMap(item("a", name = "Jollof Rice", price = 3500.0))), written["data"])
        assertEquals(
            listOf(FakeMenuAudit.Entry("restaurant_menu_updated", "cms_content", "restaurant_menu", null, null)),
            rig.audit.entries
        )
    }

    @Test fun everyKindOfChangeIsAuditedWithTheSameEntry() = runTest {
        val rig = Rig(listOf(menuItemToMap(item("a")), menuItemToMap(item("b"))))
        rig.repo.save(MenuChange.Replace(item("a", name = "Edited")))
        rig.repo.save(MenuChange.SetAvailable("b", false))
        rig.repo.save(MenuChange.Remove("a"))
        assertEquals(3, rig.audit.entries.size)
        assertTrue(rig.audit.entries.all { it.action == "restaurant_menu_updated" && it.collection == "cms_content" && it.documentId == "restaurant_menu" })
    }

    @Test fun twoPeopleEditingDifferentItemsKeepBothChanges() = runTest {
        val rig = Rig(listOf(menuItemToMap(item("a", price = 100.0)), menuItemToMap(item("b", price = 200.0))))
        // Both phones show the same menu; the second save happens after the first one reached the database.
        rig.repo.save(MenuChange.Replace(item("a", price = 111.0)))
        rig.repo.save(MenuChange.Replace(item("b", price = 222.0)))
        val prices = rig.store.live.associate { (it as Map<*, *>)["id"] to (it["price"]) }
        assertEquals(mapOf<Any?, Any?>("a" to 111.0, "b" to 222.0), prices)
    }

    @Test fun aStoreFailureReachesTheCallerAndWritesNoAudit() = runTest {
        val rig = Rig()
        rig.store.failWith = MenuException("You don't have access to this data.")
        try {
            rig.repo.save(MenuChange.Add(item("a")))
            fail("expected MenuException")
        } catch (e: MenuException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aBrokenAuditNeverTurnsASavedChangeIntoAnError() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.repo.save(MenuChange.Add(item("a")))
        assertEquals(1, rig.store.payloads.size)
    }

    @Test fun anItemWithoutANameOrWithANegativePriceWritesNothing() = runTest {
        val rig = Rig(listOf(menuItemToMap(item("a"))))
        for (change in listOf(
            MenuChange.Add(item("n", name = "  ")),
            MenuChange.Add(item("n", price = -5.0)),
            MenuChange.Replace(item("a", name = "")),
            MenuChange.Replace(item("a", price = -1.0))
        )) {
            try {
                rig.repo.save(change)
                fail("expected MenuException for $change")
            } catch (e: MenuException) {
                assertEquals(MSG_MENU_CHECK_ITEM, e.message)
            }
        }
        assertTrue(rig.store.payloads.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun editingAnItemDeletedElsewhereReportsItAndWritesNothing() = runTest {
        val rig = Rig(listOf(menuItemToMap(item("a"))))
        try {
            rig.repo.save(MenuChange.Replace(item("gone")))
            fail("expected MenuException")
        } catch (e: MenuException) {
            assertEquals(MSG_MENU_ITEM_MISSING, e.message)
        }
        assertTrue(rig.store.payloads.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun theViewOfAResourceShowsLoadingErrorOrItems() {
        assertEquals(MenuView.Loading, menuViewOf(Resource.Loading))
        assertEquals(MenuView.Error(MSG_MENU_LOAD_FAILED), menuViewOf(Resource.Error("boom")))
        assertEquals(MenuView.Ready(listOf(item("a"))), menuViewOf(Resource.Success(listOf(item("a")))))
    }
}
