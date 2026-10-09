package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class LaundryHistoryViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig(role: Role = Role.LAUNDRY_VALET, uid: String = "u1") {
        val store = LaundryHistoryFakeStore()
        val session = LaundryHistoryFakeSession(role, uid)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = LaundryHistoryViewModel(LaundryHistoryRepository(store), session, toast)
    }

    private fun TestScope.collectView(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    private fun rowsOf(rig: Rig): List<String> = (rig.vm.view.value as LaundryHistoryView.Ready).rows.map { it.id }

    @Test fun theLaundryValetAsksOnlyForTheirOwnRequests() = runTest {
        val rig = Rig(Role.LAUNDRY_VALET, uid = "valet-7")
        collectView(rig)
        assertEquals(listOf<String?>("valet-7"), rig.store.observedWith)
    }

    @Test fun everyOtherRoleAsksForEveryRequest() = runTest {
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER).forEach { role ->
            val rig = Rig(role, uid = "x")
            collectView(rig)
            assertEquals(listOf<String?>(null), rig.store.observedWith)
        }
    }

    @Test fun theMonthStartsOnTheCurrentMonth() = runTest {
        val rig = Rig(Role.MANAGER)
        assertEquals(laundryHistoryCurrentMonth(ZoneId.of("Africa/Lagos")), rig.vm.filters.value.month)
        assertEquals("", rig.vm.filters.value.search)
        assertEquals("", rig.vm.filters.value.status)
    }

    @Test fun filtersApplyToTheListAndClearingTheMonthShowsAll() = runTest {
        val rig = Rig(Role.MANAGER)
        collectView(rig)
        rig.store.requests.value = Resource.Success(
            listOf(
                laundryHistoryRequestOf("oct", guest = "Mr Okoro", status = LaundryStatus.WASHING),
                laundryHistoryRequestOf("octReady", guest = "Mrs Ade", status = LaundryStatus.READY, at = Instant.parse("2026-10-08T10:00:00Z")),
                laundryHistoryRequestOf("sep", guest = "Mr Okoro", at = Instant.parse("2026-09-10T10:00:00Z"))
            )
        )
        rig.vm.setMonth("2026-10")
        assertEquals(listOf("oct", "octReady"), rowsOf(rig))
        rig.vm.setSearch("okoro")
        assertEquals(listOf("oct"), rowsOf(rig))
        rig.vm.setSearch("")
        rig.vm.setStatus("ready")
        assertEquals(listOf("octReady"), rowsOf(rig))
        rig.vm.setStatus("")
        rig.vm.setMonth("")
        assertEquals(listOf("oct", "octReady", "sep"), rowsOf(rig))
    }

    @Test fun aLoadProblemShowsTheMessageAndRetryAsksAgain() = runTest {
        val rig = Rig(Role.MANAGER)
        collectView(rig)
        rig.store.requests.value = Resource.Error("boom")
        assertEquals(LaundryHistoryView.Error("We couldn't load laundry history."), rig.vm.view.value)
        val before = rig.store.observedWith.size
        rig.vm.retry()
        assertEquals(before + 1, rig.store.observedWith.size)
    }

    @Test fun exportIsNotAvailableUntilTheDataHasLoaded() = runTest {
        val rig = Rig(Role.MANAGER)
        collectView(rig)
        rig.store.requests.value = Resource.Loading
        assertNull(rig.vm.exportFile())
        rig.store.requests.value = Resource.Error("boom")
        assertNull(rig.vm.exportFile())
    }

    @Test fun exportCarriesTheFilteredRowsAndTheFileNameOfTheMonth() = runTest {
        val rig = Rig(Role.MANAGER)
        collectView(rig)
        rig.store.requests.value = Resource.Success(
            listOf(
                laundryHistoryRequestOf("hit", guest = "Mr Okoro"),
                laundryHistoryRequestOf("miss", guest = "Someone Else"),
                laundryHistoryRequestOf("sep", guest = "Mr Okoro", at = Instant.parse("2026-09-10T10:00:00Z"))
            )
        )
        rig.vm.setMonth("2026-10")
        rig.vm.setSearch("okoro")
        val export = rig.vm.exportFile()!!
        assertEquals("laundry-history-2026-10.csv", export.fileName)
        assertEquals(2, export.content.split("\n").size) // header + one row
        assertTrue(export.content.contains("Mr Okoro"))

        rig.vm.setMonth("")
        val all = rig.vm.exportFile()!!
        assertEquals("laundry-history-all.csv", all.fileName)
        assertEquals(3, all.content.split("\n").size) // header + the two Okoro rows (search still on)
    }

    @Test fun aShareProblemShowsAnErrorToastWithItsMessageOrADefault() = runTest {
        val rig = Rig(Role.MANAGER)
        listenToToasts(rig)
        rig.vm.shareFailed(IllegalStateException("No app can open this"))
        rig.vm.shareFailed(IllegalStateException())
        assertEquals(2, rig.events.size)
        assertEquals("Error", rig.events[0].title)
        assertEquals("No app can open this", rig.events[0].message)
        assertEquals(ToastType.Error, rig.events[0].type)
        assertEquals("The file could not be shared.", rig.events[1].message)
    }
}
