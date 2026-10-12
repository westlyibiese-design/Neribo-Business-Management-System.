package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
class BarAttendantDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun order(status: String?, total: Double, at: String?) =
        OrderRow(status = status, total = total, createdAt = at?.let { dashAt(it) })

    private fun state(orders: List<OrderRow>) = barStateOf(Resource.Success(orders), DASH_NOW, DASH_LAGOS)

    @Test fun todayPendingAndRevenueAreCounted() {
        val s = state(
            listOf(
                order("pending", 1_200.0, "2026-10-12T09:00:00"),
                order("served", 3_000.0, "2026-10-12T09:30:00"),
                order("completed", 800.0, "2026-10-12T10:00:00"),
                order("completed", 9_000.0, "2026-10-11T22:00:00"),
                order("pending", 500.0, "2026-10-02T12:00:00")
            )
        )
        assertEquals(3, s.todayCount)
        assertEquals(2, s.pendingCount)          // pending is all time
        assertEquals(5_000.0, s.todayAmount, 0.001)
    }

    @Test fun todayIsTheBusinessDayNotUtc() {
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")
        val s = barStateOf(Resource.Success(listOf(order("pending", 400.0, "2026-10-12T00:05:00"))), late, DASH_LAGOS)
        assertEquals(1, s.todayCount)
        assertEquals(400.0, s.todayAmount, 0.001)
    }

    @Test fun noSalesGiveZeros() {
        val s = state(emptyList())
        assertEquals(0, s.todayCount)
        assertEquals(0, s.pendingCount)
        assertEquals(0.0, s.todayAmount, 0.001)
        assertFalse(s.loading)
        assertFalse(s.failed)
    }

    @Test fun loadingAndFailureStates() {
        assertTrue(barStateOf(Resource.Loading, DASH_NOW, DASH_LAGOS).loading)
        val failed = barStateOf(Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    @Test fun errorTextIsTheBarMessage() {
        assertEquals("We couldn't load your bar sales.", MSG_BAR_LOAD_FAILED)
    }

    /** A colleague's bar sales live in the same collection; the view model only ever asks for its own uid. */
    @Test fun aColleaguesBarSalesNeverChangeTheNumbers() = runTest {
        val byAttendant = MutableStateFlow(
            mapOf(
                "b1" to listOf(order("pending", 1_000.0, "2026-10-12T09:00:00")),
                "b2" to listOf(order("pending", 50_000.0, "2026-10-12T09:10:00"), order("completed", 8_000.0, "2026-10-12T09:20:00"))
            )
        )
        val asked = mutableListOf<String>()
        val vm = BarAttendantDashboardViewModel(
            flows = { uid -> asked += uid; BarFlows(byAttendant.map { Resource.Success(it[uid].orEmpty()) }) },
            uid = "b1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(listOf("b1"), asked)
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1, vm.state.value.pendingCount)
        assertEquals(1_000.0, vm.state.value.todayAmount, 0.001)
        // the colleague rings up another sale: nothing changes here
        byAttendant.value = byAttendant.value + ("b2" to byAttendant.value.getValue("b2") + order("pending", 2_000.0, "2026-10-12T10:00:00"))
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1_000.0, vm.state.value.todayAmount, 0.001)
    }

    @Test fun myOwnNewSaleMovesTheCounters() = runTest {
        val mine = MutableStateFlow<Resource<List<OrderRow>>>(Resource.Success(emptyList()))
        val vm = BarAttendantDashboardViewModel(
            flows = { BarFlows(mine) },
            uid = "b1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0, vm.state.value.todayCount)
        mine.value = Resource.Success(listOf(order("pending", 2_500.0, "2026-10-12T10:00:00")))
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1, vm.state.value.pendingCount)
        assertEquals(2_500.0, vm.state.value.todayAmount, 0.001)
    }

    @Test fun tilesOpenTheBarPages() = runTest {
        val navigator = RecordingNavigator()
        val vm = BarAttendantDashboardViewModel(
            flows = { BarFlows(flowOf(Resource.Success(emptyList()))) },
            uid = "b1", zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.open(BAR_DASH_ROUTE_NEW_SALE)
        vm.open(BAR_DASH_ROUTE_SALES_HISTORY)
        assertEquals(listOf("bar/new-sale", "bar/sales-history"), navigator.opened)
    }

    @Test fun aFailingReadShowsTheErrorAndRetryCollectsAgain() = runTest {
        var collected = 0
        val vm = BarAttendantDashboardViewModel(
            flows = { collected++; BarFlows(flowOf(Resource.Error("x"))) },
            uid = "b1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.loading)
        vm.retry()
        assertEquals(2, collected)
    }
}
