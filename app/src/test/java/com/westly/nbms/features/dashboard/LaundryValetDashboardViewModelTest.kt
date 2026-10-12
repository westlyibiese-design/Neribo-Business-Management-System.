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
class LaundryValetDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun request(status: String?, deliveredAt: String? = null) =
        FrontlineLaundryRow(status = status, deliveredAt = deliveredAt?.let { dashAt(it) })

    private fun state(rows: List<FrontlineLaundryRow>) = laundryStateOf(Resource.Success(rows), DASH_NOW, DASH_LAGOS)

    @Test fun activeReadyAndDeliveredTodayAreCounted() {
        val s = state(
            listOf(
                request("received"),
                request("washing"),
                request("drying"),
                request("ironing"),
                request("ready"),
                request("ready"),
                request("delivered", "2026-10-12T08:00:00"),
                request("delivered", "2026-10-11T08:00:00"),
                request("cancelled")
            )
        )
        assertEquals(6, s.active)                 // received, washing, drying, ironing and the two ready
        assertEquals(2, s.ready)
        assertEquals(1, s.deliveredToday)         // yesterday's delivery does not count
    }

    @Test fun deliveredTodayIsTheBusinessDayNotUtc() {
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")   // 00:30 on the 12th in Lagos
        val s = laundryStateOf(Resource.Success(listOf(request("delivered", "2026-10-12T00:10:00"))), late, DASH_LAGOS)
        assertEquals(1, s.deliveredToday)
    }

    @Test fun aDeliveredRequestWithoutADateIsNotDeliveredToday() {
        assertEquals(0, state(listOf(request("delivered", null))).deliveredToday)
    }

    @Test fun noRequestsGiveZeros() {
        val s = state(emptyList())
        assertEquals(0, s.active)
        assertEquals(0, s.ready)
        assertEquals(0, s.deliveredToday)
        assertFalse(s.loading)
        assertFalse(s.failed)
    }

    @Test fun loadingAndFailureStates() {
        assertTrue(laundryStateOf(Resource.Loading, DASH_NOW, DASH_LAGOS).loading)
        val failed = laundryStateOf(Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    @Test fun errorTextIsTheLaundryMessage() {
        assertEquals("We couldn't load your laundry requests.", MSG_LAUNDRY_LOAD_FAILED)
    }

    /** A colleague's requests live in the same collection; the view model only ever asks for its own uid. */
    @Test fun aColleaguesRequestsNeverChangeTheNumbers() = runTest {
        val byValet = MutableStateFlow(
            mapOf(
                "v1" to listOf(request("washing"), request("ready")),
                "v2" to listOf(request("received"), request("ready"), request("ready"))
            )
        )
        val asked = mutableListOf<String>()
        val vm = LaundryValetDashboardViewModel(
            flows = { uid -> asked += uid; LaundryFlows(byValet.map { Resource.Success(it[uid].orEmpty()) }) },
            uid = "v1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(listOf("v1"), asked)
        assertEquals(2, vm.state.value.active)
        assertEquals(1, vm.state.value.ready)
        byValet.value = byValet.value + ("v2" to byValet.value.getValue("v2") + request("ready"))
        assertEquals(2, vm.state.value.active)
        assertEquals(1, vm.state.value.ready)
    }

    @Test fun myOwnNewRequestMovesTheCounters() = runTest {
        val mine = MutableStateFlow<Resource<List<FrontlineLaundryRow>>>(Resource.Success(emptyList()))
        val vm = LaundryValetDashboardViewModel(
            flows = { LaundryFlows(mine) },
            uid = "v1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0, vm.state.value.active)
        mine.value = Resource.Success(listOf(request("received")))
        assertEquals(1, vm.state.value.active)
        mine.value = Resource.Success(listOf(request("ready")))
        assertEquals(1, vm.state.value.ready)
        mine.value = Resource.Success(listOf(request("delivered", "2026-10-12T09:00:00")))
        assertEquals(0, vm.state.value.active)
        assertEquals(1, vm.state.value.deliveredToday)
    }

    @Test fun tilesOpenTheLaundryPages() = runTest {
        val navigator = RecordingNavigator()
        val vm = LaundryValetDashboardViewModel(
            flows = { LaundryFlows(flowOf(Resource.Success(emptyList()))) },
            uid = "v1", zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.open(LAUNDRY_DASH_ROUTE_MANAGE)
        vm.open(LAUNDRY_DASH_ROUTE_HISTORY)
        assertEquals(listOf("laundry", "laundry/history"), navigator.opened)
    }

    @Test fun aFailingReadShowsTheErrorAndRetryCollectsAgain() = runTest {
        var collected = 0
        val vm = LaundryValetDashboardViewModel(
            flows = { collected++; LaundryFlows(flowOf(Resource.Error("x"))) },
            uid = "v1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        vm.retry()
        assertEquals(2, collected)
    }
}
