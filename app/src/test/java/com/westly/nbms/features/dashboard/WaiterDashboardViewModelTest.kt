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
class WaiterDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun order(status: String?, total: Double, at: String?) =
        OrderRow(status = status, total = total, createdAt = at?.let { dashAt(it) })

    private fun state(orders: List<OrderRow>) = waiterStateOf(Resource.Success(orders), DASH_NOW, DASH_LAGOS)

    @Test fun todayPendingAndRevenueAreCounted() {
        val s = state(
            listOf(
                order("pending", 3_000.0, "2026-10-12T09:00:00"),
                order("served", 5_000.0, "2026-10-12T09:30:00"),
                order("preparing", 1_500.0, "2026-10-12T10:00:00"),
                order("served", 9_000.0, "2026-10-11T19:00:00"),
                order("pending", 700.0, "2026-10-01T12:00:00")
            )
        )
        assertEquals(3, s.todayCount)
        assertEquals(2, s.pendingCount)          // pending is all time
        assertEquals(9_500.0, s.todayAmount, 0.001)
    }

    @Test fun todayIsTheBusinessDayNotUtc() {
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")
        val s = waiterStateOf(Resource.Success(listOf(order("pending", 400.0, "2026-10-12T00:05:00"))), late, DASH_LAGOS)
        assertEquals(1, s.todayCount)
        assertEquals(400.0, s.todayAmount, 0.001)
    }

    @Test fun noOrdersGiveZeros() {
        val s = state(emptyList())
        assertEquals(0, s.todayCount)
        assertEquals(0, s.pendingCount)
        assertEquals(0.0, s.todayAmount, 0.001)
    }

    @Test fun loadingAndFailureStates() {
        assertTrue(waiterStateOf(Resource.Loading, DASH_NOW, DASH_LAGOS).loading)
        val failed = waiterStateOf(Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    /** A second waiter's orders live in the same collection; the view model only ever asks for its own uid. */
    @Test fun aSecondWaitersOrdersNeverChangeTheNumbers() = runTest {
        val byWaiter = MutableStateFlow(
            mapOf(
                "w1" to listOf(order("pending", 1_000.0, "2026-10-12T09:00:00")),
                "w2" to listOf(order("pending", 50_000.0, "2026-10-12T09:10:00"), order("served", 8_000.0, "2026-10-12T09:20:00"))
            )
        )
        val asked = mutableListOf<String>()
        val vm = WaiterDashboardViewModel(
            flows = { uid -> asked += uid; WaiterFlows(byWaiter.map { Resource.Success(it[uid].orEmpty()) }) },
            uid = "w1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(listOf("w1"), asked)
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1, vm.state.value.pendingCount)
        assertEquals(1_000.0, vm.state.value.todayAmount, 0.001)
        // the other waiter takes another order: nothing changes here
        byWaiter.value = byWaiter.value + ("w2" to byWaiter.value.getValue("w2") + order("pending", 2_000.0, "2026-10-12T10:00:00"))
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1_000.0, vm.state.value.todayAmount, 0.001)
    }

    @Test fun myOwnNewOrderMovesTheCounters() = runTest {
        val mine = MutableStateFlow<Resource<List<OrderRow>>>(Resource.Success(emptyList()))
        val vm = WaiterDashboardViewModel(
            flows = { WaiterFlows(mine) },
            uid = "w1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        mine.value = Resource.Success(listOf(order("pending", 2_500.0, "2026-10-12T10:00:00")))
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(1, vm.state.value.pendingCount)
        assertEquals(2_500.0, vm.state.value.todayAmount, 0.001)
    }

    @Test fun tilesOpenTheOrderPages() = runTest {
        val navigator = RecordingNavigator()
        val vm = WaiterDashboardViewModel(
            flows = { WaiterFlows(flowOf(Resource.Success(emptyList()))) },
            uid = "w1", zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.open("orders/new")
        vm.open("orders/history")
        assertEquals(listOf("orders/new", "orders/history"), navigator.opened)
    }

    @Test fun retryCollectsTheOrdersAgain() = runTest {
        var collected = 0
        val vm = WaiterDashboardViewModel(
            flows = { collected++; WaiterFlows(flowOf(Resource.Error("x"))) },
            uid = "w1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        vm.retry()
        assertEquals(2, collected)
    }
}
