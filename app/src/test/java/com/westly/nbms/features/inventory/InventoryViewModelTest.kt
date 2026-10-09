package com.westly.nbms.features.inventory

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig {
        val store = FakeInventoryStore()
        val audit = FakeInventoryAudit()
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = InventoryViewModel(InventoryRepository(store, audit), toast)
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    private fun form() = AddItemForm("Bottled Water", "drinks", "bottles", "3", "5", "200", "")

    @Test fun addingShowsTheItemAddedToastAndClosesTheDialog() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        var saved = false
        rig.vm.addItem(form()) { saved = true }
        assertTrue(saved)
        assertEquals(1, rig.store.adds.size)
        assertEquals(1, rig.events.size)
        assertEquals("Item Added", rig.events[0].message)
        assertEquals(ToastType.Success, rig.events[0].type)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aFailedAddShowsAnErrorToastAndKeepsTheDialogOpen() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.store.failWith = IllegalStateException("offline")
        var saved = false
        rig.vm.addItem(form()) { saved = true }
        assertFalse(saved)
        assertEquals(1, rig.events.size)
        assertEquals("Error", rig.events[0].title)
        assertEquals("offline", rig.events[0].message)
        assertEquals(ToastType.Error, rig.events[0].type)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aSecondTapWhileSavingDoesNothing() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.store.gate = gate
        rig.vm.addItem(form()) {}
        assertTrue(rig.vm.saving.value)
        rig.vm.addItem(form()) {}
        gate.complete(Unit)
        assertEquals(1, rig.store.adds.size)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun restockShowsTheBeforeAndAfterInTheToast() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.store.live["w"] = 3
        var done = false
        rig.vm.restock(invItem("w", name = "Bottled Water", quantity = 3), 20) { done = true }
        assertTrue(done)
        assertEquals(1, rig.events.size)
        assertEquals("Restocked", rig.events[0].title)
        assertEquals("Bottled Water: 3 → 23", rig.events[0].message)
        assertEquals(ToastType.Success, rig.events[0].type)
    }

    @Test fun anAmountBelowOneDoesNothingAtAll() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.store.live["w"] = 3
        var done = false
        rig.vm.restock(invItem("w", quantity = 3), 0) { done = true }
        assertFalse(done)
        assertTrue(rig.events.isEmpty())
        assertTrue(rig.store.restocks.isEmpty())
    }

    @Test fun aFailedRestockShowsAnErrorToast() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        var done = false
        rig.vm.restock(invItem("ghost"), 5) { done = true }
        assertFalse(done)
        assertEquals("Error", rig.events[0].title)
        assertEquals("This item no longer exists.", rig.events[0].message)
    }

    @Test fun theFiltersFeedTheView() = runTest {
        val rig = Rig()
        rig.store.items.value = Resource.Success(
            listOf(
                invItem("water", name = "Bottled Water", category = "drinks", quantity = 3),
                invItem("soap", name = "Hand Soap", category = "toiletries")
            )
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
        var ready = rig.vm.view.value as InventoryView.Ready
        assertEquals(2, ready.rows.size)

        rig.vm.setSearch("water")
        ready = rig.vm.view.value as InventoryView.Ready
        assertEquals(listOf("water"), ready.rows.map { it.id })

        rig.vm.setCategory("food")
        ready = rig.vm.view.value as InventoryView.Ready
        assertTrue(ready.rows.isEmpty())
        assertEquals(2, ready.totalCount)
        assertEquals(1, ready.lowItems.size)
        assertEquals("food", rig.vm.filters.value.categoryKey)
    }
}
