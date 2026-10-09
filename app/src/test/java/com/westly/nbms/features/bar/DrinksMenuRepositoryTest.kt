package com.westly.nbms.features.bar

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A store that behaves like the real one: it holds the LIVE array, and `save` applies the change to that array
 * (not to anything on a screen), exactly as the transaction does.
 */
internal class FakeDrinksStore(initial: List<Any?> = emptyList()) : DrinksStore {
    var live: List<Any?> = initial
    val shown = MutableStateFlow<Resource<List<DrinkItem>>>(Resource.Success(emptyList()))
    val payloads = mutableListOf<Map<String, Any?>>()
    var failWith: Exception? = null

    override fun observe(): Flow<Resource<List<DrinkItem>>> = shown

    override suspend fun save(change: DrinkChange) {
        failWith?.let { throw it }
        val next = applyDrinkChange(live, change)
        payloads += buildDrinksPayload(next)
        live = next
    }
}

internal class FakeDrinksAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val documentId: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)

    val entries = mutableListOf<Entry>()
    var fail = false

    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

class DrinksMenuRepositoryTest {

    private class Rig(initial: List<Any?> = emptyList()) {
        val store = FakeDrinksStore(initial)
        val audit = FakeDrinksAudit()
        val repo = DrinksMenuRepository(store, audit)
    }

    private fun drink(id: String, name: String = "Drink $id", price: Double = 1500.0, available: Boolean = true) =
        DrinkItem(id, name, "", "", price, DrinkCategory.BEER, available)

    private fun raw(id: String): Map<String, Any?> = drinkToMap(drink(id))

    @Test fun observeServesWhatTheStoreShows() = runTest {
        val rig = Rig()
        rig.store.shown.value = Resource.Success(listOf(drink("a")))
        assertEquals(Resource.Success(listOf(drink("a"))), rig.repo.observe().first())
    }

    @Test fun anEmptyMenuIsSuccessWithNoDrinks() = runTest {
        assertEquals(Resource.Success(emptyList<DrinkItem>()), Rig().repo.observe().first())
    }

    @Test fun aReadFailureIsAnError() = runTest {
        val rig = Rig()
        rig.store.shown.value = Resource.Error("You don't have access to this data.")
        assertTrue(rig.repo.observe().first() is Resource.Error)
    }

    @Test fun anAddWritesTheNewArrayThenAudits() = runTest {
        val rig = Rig()
        rig.repo.save(DrinkChange.Add(drink("a", name = "Heineken 60cl")))
        val written = rig.store.payloads.single()
        assertEquals(setOf("data", "updatedAt"), written.keys)
        assertEquals(listOf(drinkToMap(drink("a", name = "Heineken 60cl"))), written["data"])
        assertSame(BarServerTime, written["updatedAt"])
        assertEquals(
            listOf(FakeDrinksAudit.Entry("bar_menu_updated", "cms_content", "bar_menu", null, null)),
            rig.audit.entries
        )
    }

    @Test fun anUpdateReplacesOnlyThatDrink() = runTest {
        val rig = Rig(listOf(raw("a"), raw("b")))
        rig.repo.save(DrinkChange.Replace(drink("a", name = "Renamed", price = 2000.0)))
        val data = rig.store.live
        assertEquals("Renamed", (data[0] as Map<*, *>)["name"]); assertEquals(2000.0, (data[0] as Map<*, *>)["price"])
        assertEquals(raw("b"), data[1])
    }

    @Test fun aDeleteRemovesOnlyThatDrink() = runTest {
        val rig = Rig(listOf(raw("a"), raw("b")))
        rig.repo.save(DrinkChange.Remove("a"))
        assertEquals(listOf(raw("b")), rig.store.live)
    }

    @Test fun theAvailabilitySwitchChangesOnlyTheFlag() = runTest {
        val rig = Rig(listOf(raw("a")))
        rig.repo.save(DrinkChange.SetAvailable("a", false))
        assertEquals(raw("a") + ("available" to false), rig.store.live.single())
    }

    @Test fun aDocumentThatChangedBetweenReadAndWriteKeepsTheOtherPersonsDrink() = runTest {
        // The screen showed only "a"; before the save someone else added "theirs". The change applies to the LIVE array.
        val rig = Rig(listOf(raw("a")))
        rig.store.live = listOf(raw("a"), raw("theirs"))
        rig.repo.save(DrinkChange.Add(drink("mine")))
        assertEquals(listOf("a", "theirs", "mine"), rig.store.live.map { (it as Map<*, *>)["id"] })
    }

    @Test fun aDrinkDeletedByAnotherPersonCannotBeEditedAndNothingIsWritten() = runTest {
        val rig = Rig(listOf(raw("b")))
        try { rig.repo.save(DrinkChange.Replace(drink("a"))); fail("expected an error") } catch (e: DrinksMenuException) {
            assertEquals("This drink no longer exists. Someone may have deleted it.", e.message)
        }
        assertTrue(rig.store.payloads.isEmpty()); assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aDrinkWithoutANameOrWithANegativePriceWritesNothing() = runTest {
        val rig = Rig()
        for (bad in listOf(drink("a", name = "  "), drink("b", price = -5.0))) {
            try { rig.repo.save(DrinkChange.Add(bad)); fail("expected an error") } catch (e: DrinksMenuException) {
                assertEquals("Please check the drink: a name is needed and the price cannot be negative.", e.message)
            }
            try { rig.repo.save(DrinkChange.Replace(bad)); fail("expected an error") } catch (e: DrinksMenuException) { }
        }
        assertTrue(rig.store.payloads.isEmpty()); assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailedSaveGivesItsMessageAndSkipsTheAudit() = runTest {
        val rig = Rig()
        rig.store.failWith = DrinksMenuException("You don't have access to this data.")
        try { rig.repo.save(DrinkChange.Add(drink("a"))); fail("expected an error") } catch (e: DrinksMenuException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailingAuditNeverTurnsASavedChangeIntoAnError() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.repo.save(DrinkChange.Add(drink("a")))
        assertEquals(1, rig.store.payloads.size)
    }
}
