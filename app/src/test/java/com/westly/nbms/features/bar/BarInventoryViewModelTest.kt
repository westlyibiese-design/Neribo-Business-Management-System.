package com.westly.nbms.features.bar

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
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
class BarInventoryViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig(role: Role = Role.MANAGER) {
        val store = BarHistoryFakeStockStore()
        val audit = BarHistoryFakeAudit()
        val session = BarHistoryFakeSession(role)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = BarInventoryViewModel(BarInventoryRepository(store, audit), session, toast)
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    private fun form() = BarStockForm("Heineken 60cl", "24", "bottles", "850", "6")

    @Test fun onlySuperAdminAndManagerSeeAddItem() {
        assertTrue(Rig(Role.SUPER_ADMIN).vm.canAdd)
        assertTrue(Rig(Role.MANAGER).vm.canAdd)
        assertFalse(Rig(Role.ACCOUNTANT).vm.canAdd)
        assertFalse(Rig(Role.BAR_ATTENDANT).vm.canAdd)
    }

    @Test fun everyoneOnThePageSeesRestock() {
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.BAR_ATTENDANT).forEach { assertTrue(Rig(it).vm.canRestock) }
        assertFalse(Rig(Role.OPERATIONS_MANAGER).vm.canRestock)
    }

    @Test fun addingShowsItemAddedAndClosesTheDialog() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        var saved = false
        rig.vm.addItem(form()) { saved = true }
        assertTrue(saved)
        assertEquals(1, rig.store.adds.size)
        assertEquals("Item Added", rig.events.single().message)
        assertEquals(ToastType.Success, rig.events.single().type)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aFailedAddShowsFailedWithTheServerMessageAndKeepsTheDialogOpen() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.store.failWith = IllegalStateException("Missing or insufficient permissions.")
        var saved = false
        rig.vm.addItem(form()) { saved = true }
        assertFalse(saved)
        assertEquals("Failed", rig.events.single().title)
        assertEquals("Missing or insufficient permissions.", rig.events.single().message)
        assertEquals(ToastType.Error, rig.events.single().type)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aBarAttendantCannotAddEvenIfTheButtonWereReached() = runTest {
        val rig = Rig(Role.BAR_ATTENDANT)
        var saved = false
        rig.vm.addItem(form()) { saved = true }
        assertFalse(saved)
        assertTrue(rig.store.adds.isEmpty())
    }

    @Test fun restockingShowsTheBeforeAndAfterQuantities() = runTest {
        val rig = Rig(Role.BAR_ATTENDANT)
        listenToToasts(rig)
        rig.store.live["h1"] = 24
        var done = false
        rig.vm.restock(barHistoryItemOf("h1", quantity = 24), 12) { done = true }
        assertTrue(done)
        assertEquals("Restocked", rig.events.single().title)
        assertEquals("Heineken 60cl: 24 → 36", rig.events.single().message)
        assertEquals(36, rig.store.live["h1"])
        assertFalse(rig.vm.saving.value)
    }

    @Test fun anAmountBelowOneDoesNothing() = runTest {
        val rig = Rig()
        rig.store.live["h1"] = 24
        var done = false
        rig.vm.restock(barHistoryItemOf("h1"), 0) { done = true }
        assertFalse(done)
        assertTrue(rig.store.restocks.isEmpty())
    }

    @Test fun anOperationsManagerCannotRestock() = runTest {
        val rig = Rig(Role.OPERATIONS_MANAGER)
        rig.store.live["h1"] = 24
        rig.vm.restock(barHistoryItemOf("h1"), 5) { }
        assertTrue(rig.store.restocks.isEmpty())
    }

    @Test fun aSecondTapWhileSavingDoesNothing() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.store.gate = gate
        rig.store.live["h1"] = 10
        rig.vm.restock(barHistoryItemOf("h1", quantity = 10), 5) { }
        assertTrue(rig.vm.saving.value) // set before the suspend call
        rig.vm.restock(barHistoryItemOf("h1", quantity = 10), 5) { }
        rig.vm.addItem(form()) { }
        gate.complete(Unit)
        assertEquals(1, rig.store.restocks.size)
        assertTrue(rig.store.adds.isEmpty())
        assertFalse(rig.vm.saving.value)
    }

    @Test fun theSearchNarrowsTheListAndTheLowBannerStaysComplete() = runTest {
        val rig = Rig()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
        rig.store.items.value = Resource.Success(
            listOf(
                barHistoryItemOf("1", "Wine", quantity = 1, minStock = 5),
                barHistoryItemOf("2", "Beer", quantity = 40),
                barHistoryItemOf("3", "Soap", category = "toiletries")
            )
        )
        rig.vm.setSearch("beer")
        val ready = rig.vm.view.value as BarStockView.Ready
        assertEquals(listOf("2"), ready.rows.map { it.id })
        assertEquals(listOf("2", "1"), ready.all.map { it.id })
        assertEquals(listOf("1"), ready.lowItems.map { it.id })
    }

    @Test fun aLoadErrorBecomesTheFriendlyMessage() = runTest {
        val rig = Rig()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
        rig.store.items.value = Resource.Error("raw")
        assertEquals(BarStockView.Error("We couldn't load bar inventory."), rig.vm.view.value)
    }
}
