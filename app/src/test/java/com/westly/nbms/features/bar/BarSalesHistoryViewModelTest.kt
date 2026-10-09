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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BarSalesHistoryViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig(role: Role = Role.BAR_ATTENDANT, uid: String = "u1") {
        val store = BarHistoryFakeStore()
        val session = BarHistoryFakeSession(role, uid)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = BarSalesHistoryViewModel(BarSalesHistoryRepository(store, session), session, toast)
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    private fun TestScope.collectView(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
    }

    @Test fun markServedWritesTheUpdateAndShowsTheSaleUpdatedToast() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.markServed(barHistorySaleOf("s1"))
        assertEquals(1, rig.store.updates.size)
        assertEquals("s1", rig.store.updates[0].first)
        assertEquals(1, rig.events.size)
        assertEquals("Sale Updated", rig.events[0].title)
        assertEquals("Status → served", rig.events[0].message)
        assertEquals(ToastType.Success, rig.events[0].type)
        assertTrue(rig.vm.busy.value.isEmpty())
    }

    @Test fun aFailedUpdateShowsUpdateFailedWithTheMessageAndClearsTheGuard() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.store.failWith = IllegalStateException("offline")
        rig.vm.markServed(barHistorySaleOf("s1"))
        assertEquals(1, rig.events.size)
        assertEquals("Update Failed", rig.events[0].title)
        assertEquals("offline", rig.events[0].message)
        assertEquals(ToastType.Error, rig.events[0].type)
        assertTrue(rig.vm.busy.value.isEmpty())
        // the guard was cleared in finally: the person can try again
        rig.store.failWith = null
        rig.vm.markServed(barHistorySaleOf("s1"))
        assertEquals(1, rig.store.updates.size)
    }

    @Test fun aSecondTapWhileTheFirstIsInFlightDoesNothing() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.store.gate = gate
        val sale = barHistorySaleOf("s1")
        rig.vm.markServed(sale)
        assertEquals(setOf("s1"), rig.vm.busy.value) // set before the suspend call
        rig.vm.markServed(sale)
        gate.complete(Unit)
        assertEquals(1, rig.store.updates.size)
        assertTrue(rig.vm.busy.value.isEmpty())
    }

    @Test fun differentSalesCanBeInFlightAtTheSameTime() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.store.gate = gate
        rig.vm.markServed(barHistorySaleOf("s1"))
        rig.vm.markServed(barHistorySaleOf("s2"))
        assertEquals(setOf("s1", "s2"), rig.vm.busy.value)
        gate.complete(Unit)
        assertEquals(2, rig.store.updates.size)
    }

    @Test fun anAccountantCannotMarkServed() = runTest {
        val rig = Rig(Role.ACCOUNTANT)
        rig.vm.markServed(barHistorySaleOf("s1"))
        assertTrue(rig.store.updates.isEmpty())
        assertFalse(showMarkServed(rig.vm.role, barHistorySaleOf("s1")))
    }

    @Test fun aSaleThatIsAlreadyServedIsNotUpdatedAgain() = runTest {
        val rig = Rig()
        rig.vm.markServed(barHistorySaleOf("s1", status = BarSaleStatus.SERVED))
        assertTrue(rig.store.updates.isEmpty())
    }

    @Test fun theBarAttendantAsksOnlyForTheirOwnSales() = runTest {
        val rig = Rig(Role.BAR_ATTENDANT, uid = "attendant-7")
        collectView(rig)
        assertEquals(listOf<String?>("attendant-7"), rig.store.observedWith)
    }

    @Test fun otherRolesAskForEverySale() = runTest {
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER).forEach { role ->
            val rig = Rig(role, uid = "x")
            collectView(rig)
            assertEquals(listOf<String?>(null), rig.store.observedWith)
        }
    }

    @Test fun theMonthStartsOnTheCurrentMonthAndFiltersApplyToTheList() = runTest {
        val rig = Rig(Role.MANAGER)
        collectView(rig)
        assertEquals(barHistoryCurrentMonth(barHistoryZone("Africa/Lagos")), rig.vm.filters.value.month)
        val now = Instant.now()
        rig.store.sales.value = Resource.Success(
            listOf(
                barHistorySaleOf("pending", at = now, status = BarSaleStatus.PENDING),
                barHistorySaleOf("served", at = now.minusSeconds(60), status = BarSaleStatus.SERVED),
                barHistorySaleOf("old", at = Instant.parse("2020-01-01T10:00:00Z"))
            )
        )
        assertEquals(listOf("pending", "served"), (rig.vm.view.value as BarHistoryView.Ready).rows.map { it.id })
        rig.vm.setStatus("served")
        assertEquals(listOf("served"), (rig.vm.view.value as BarHistoryView.Ready).rows.map { it.id })
        rig.vm.setStatus("")
        rig.vm.setMonth("")
        assertEquals(3, (rig.vm.view.value as BarHistoryView.Ready).rows.size)
    }

    @Test fun aLoadErrorBecomesTheFriendlyMessage() = runTest {
        val rig = Rig()
        collectView(rig)
        rig.store.sales.value = Resource.Error("raw failure")
        assertEquals(BarHistoryView.Error("We couldn't load bar sales."), rig.vm.view.value)
    }
}
