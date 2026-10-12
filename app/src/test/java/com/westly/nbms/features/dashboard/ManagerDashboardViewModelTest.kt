package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.inventory.InventoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class ManagerDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val rooms = listOf(
        DashRoom("1", "101", "available"), DashRoom("2", "102", null), DashRoom("3", "103", "occupied"),
        DashRoom("4", "104", "available", isDeleted = true)
    )

    private fun item(name: String, qty: Int, min: Int, deleted: Boolean = false) =
        InventoryItem(id = name, name = name, quantity = qty, minStock = min, isDeleted = deleted)

    private fun state(
        bookings: List<DashBooking> = emptyList(),
        inventory: List<InventoryItem> = emptyList()
    ) = managerStateOf(Resource.Success(rooms), Resource.Success(bookings), Resource.Success(inventory), DASH_NOW, DASH_LAGOS)

    @Test fun fourCardsAreCountedFromRoomsAndBookings() {
        val bookings = listOf(
            dashBooking("a", status = "pending", checkIn = dashAt("2026-10-12T14:00:00")),
            dashBooking("b", status = "checked_in", checkIn = dashAt("2026-10-12T08:00:00")),
            dashBooking("c", status = "confirmed", checkOut = dashAt("2026-10-12T11:00:00"), checkIn = dashAt("2026-10-09T11:00:00")),
            dashBooking("d", status = "pending", checkIn = dashAt("2026-10-30T14:00:00")),
            dashBooking("e", status = "pending", deleted = true, checkIn = dashAt("2026-10-12T14:00:00"))
        )
        val s = state(bookings)
        assertEquals(2, s.availableRooms)          // blank status counts as available; deleted room does not
        assertEquals(2, s.checkInCount)
        assertEquals(1, s.checkOutCount)
        assertEquals(2, s.pendingApprovals)
    }

    @Test fun lowStockListsEveryLowItemAndSkipsDeletedOnes() {
        val s = state(inventory = listOf(
            item("Soap", 3, 5), item("Towels", 5, 5), item("Water", 40, 10), item("Old", 0, 5, deleted = true)
        ))
        assertEquals(listOf("Soap", "Towels"), s.lowStock.map { it.name })
        assertEquals("Soap: 3 left", lowStockChipText(s.lowStock.first()))
    }

    @Test fun noLowStockGivesAnEmptyList() {
        assertTrue(state(inventory = listOf(item("Water", 40, 10))).lowStock.isEmpty())
    }

    @Test fun todaysCheckInsShowTheFirstFiveButTheCardCountsAll() {
        val bookings = (1..7).map { dashBooking("b$it", status = "confirmed", checkIn = dashAt("2026-10-12T0$it:00:00")) }
        val s = state(bookings)
        assertEquals(7, s.checkInCount)
        assertEquals(listOf("b1", "b2", "b3", "b4", "b5"), s.todayCheckIns.map { it.id })
    }

    @Test fun pendingBookingsShowTheFirstFive() {
        val bookings = (1..6).map { dashBooking("p$it", status = "pending", checkIn = dashAt("2026-10-2$it" + "T14:00:00")) } +
            dashBooking("x", status = "confirmed")
        val s = state(bookings)
        assertEquals(6, s.pendingApprovals)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), s.pendingBookings.map { it.id })
    }

    @Test fun emptyListsGiveEmptyCards() {
        val s = state()
        assertTrue(s.todayCheckIns.isEmpty())
        assertTrue(s.pendingBookings.isEmpty())
        assertEquals(0, s.pendingApprovals)
    }

    @Test fun loadingAndFailureStates() {
        val loading = managerStateOf(Resource.Loading, Resource.Success(emptyList()), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        assertTrue(loading.loading)
        val failed = managerStateOf(Resource.Loading, Resource.Error("x"), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    @Test fun subtitleUsesTheBusinessZone() {
        assertEquals("Welcome, Ada · Monday, October 12", managerSubtitle("Ada", DASH_NOW, DASH_LAGOS))
        // 23:30 UTC on Sunday the 11th is already Monday in Lagos
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")
        assertEquals("Welcome, Ada · Monday, October 12", managerSubtitle("Ada", late, DASH_LAGOS))
        assertEquals("Welcome, Ada · Sunday, October 11", managerSubtitle("Ada", late, java.time.ZoneId.of("UTC")))
    }

    @Test fun addingALowItemShowsItWithoutARefresh() = runTest {
        val inventory = MutableStateFlow<Resource<List<InventoryItem>>>(Resource.Success(listOf(item("Water", 40, 10))))
        val navigator = RecordingNavigator()
        val vm = ManagerDashboardViewModel(
            flows = {
                ManagerFlows(MutableStateFlow(Resource.Success(rooms)), MutableStateFlow(Resource.Success(emptyList())), inventory)
            },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.lowStock.isEmpty())
        inventory.value = Resource.Success(listOf(item("Water", 40, 10), item("Soap", 2, 5)))
        assertEquals(listOf("Soap"), vm.state.value.lowStock.map { it.name })
        vm.openBookings()
        assertEquals(listOf("bookings"), navigator.opened)
    }

    @Test fun retryCollectsTheListsAgain() = runTest {
        var collected = 0
        val vm = ManagerDashboardViewModel(
            flows = {
                collected++
                ManagerFlows(flowOf(Resource.Error("x")), flowOf(Resource.Success(emptyList())), flowOf(Resource.Success(emptyList())))
            },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertEquals(1, collected)
        vm.retry()
        assertEquals(2, collected)
    }
}
