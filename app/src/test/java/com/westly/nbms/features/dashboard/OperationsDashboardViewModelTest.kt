package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.features.dashboard.data.DashAttendance
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashLaundry
import com.westly.nbms.features.dashboard.data.DashMaintenance
import com.westly.nbms.features.dashboard.data.DashOrder
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.dashboard.data.DashTask
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val allModules = setOf(ModuleKey.HOUSEKEEPING, ModuleKey.RESTAURANT, ModuleKey.BAR, ModuleKey.LAUNDRY)

    private fun <T> ok(rows: List<T> = emptyList()): Resource<List<T>> = Resource.Success(rows)

    private fun state(
        modules: Set<ModuleKey> = allModules,
        rooms: List<DashRoom> = emptyList(),
        bookings: List<DashBooking> = emptyList(),
        tasks: List<DashTask> = emptyList(),
        orders: List<DashOrder> = emptyList(),
        barOrders: List<DashOrder> = emptyList(),
        laundry: List<DashLaundry> = emptyList(),
        maintenance: List<DashMaintenance> = emptyList(),
        attendance: List<DashAttendance> = emptyList()
    ) = operationsStateOf(
        ok(rooms), ok(bookings), ok(tasks), ok(orders), ok(barOrders), ok(laundry), ok(maintenance), ok(attendance),
        modules, DASH_NOW, DASH_LAGOS
    )

    private fun attendance(id: String, key: String?, status: String = "present") =
        DashAttendance(id = id, staffId = "s$id", staffName = "Staff $id", dateKey = key, status = status)

    // ---- the six tiles ----

    @Test fun sixTilesAreCountedFromTheLiveLists() {
        val s = state(
            rooms = listOf(
                DashRoom("1", "101", "occupied"), DashRoom("2", "102", "available"), DashRoom("3", "103", "cleaning"),
                DashRoom("4", "104", "occupied", isDeleted = true)
            ),
            bookings = listOf(
                DashBooking(id = "a", status = "confirmed", checkIn = dashAt("2026-10-12T14:00:00")),
                DashBooking(id = "b", status = "checked_in", checkIn = dashAt("2026-10-12T08:00:00")),
                DashBooking(id = "c", status = "confirmed", checkIn = dashAt("2026-10-09T11:00:00"), checkOut = dashAt("2026-10-12T11:00:00"))
            ),
            tasks = listOf(
                DashTask("t1", "pending"), DashTask("t2", "in_progress"), DashTask("t3", "completed"), DashTask("t4", "cancelled"),
                DashTask("t5", "pending", isDeleted = true)
            ),
            maintenance = listOf(
                DashMaintenance("m1", "open"), DashMaintenance("m2", "in_progress"), DashMaintenance("m3", "resolved"),
                DashMaintenance("m4", "open", isDeleted = true)
            ),
            attendance = listOf(attendance("1", "2026-10-12"), attendance("2", "2026-10-12"))
        )
        assertEquals("1/3", s.roomsOccupied)              // the deleted room is not counted
        assertEquals(2, s.checkIns)
        assertEquals(1, s.checkOuts)
        assertEquals(2, s.staffOnDuty)
        assertEquals(2, s.activeTasks)
        assertEquals(2, s.openMaintenance)
    }

    @Test fun staffOnDutyUsesTodaysKeyInTheBusinessZoneAndOnlyPresentStaff() {
        // 23:30 UTC on the 11th is already Monday the 12th in Lagos
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")
        val rows = listOf(
            attendance("1", "2026-10-12"), attendance("2", "2026-10-11"), attendance("3", "2026-10-12", status = "absent"),
            attendance("4", null)
        )
        val s = operationsStateOf(
            ok(), ok(), ok(), ok(), ok(), ok(), ok(), ok(rows), allModules, late, DASH_LAGOS
        )
        assertEquals(listOf("Staff 1"), s.staff.names)
        assertEquals(1, s.staffOnDuty)
    }

    @Test fun staffChipsAreTheFirstTenAndACountOfTheRest() {
        val rows = (1..12).map { attendance("$it", "2026-10-12") }
        val s = state(attendance = rows)
        assertEquals(12, s.staffOnDuty)
        assertEquals(10, s.staff.names.size)
        assertEquals(2, s.staff.more)
    }

    // ---- alert pills ----

    @Test fun pillsCountOnlyWhatNeedsAttention() {
        val s = state(
            bookings = listOf(
                DashBooking(id = "a", status = "pending", source = "online"),
                DashBooking(id = "b", status = "pending", source = "website"),
                DashBooking(id = "c", status = "pending", source = "walk_in"),
                DashBooking(id = "d", status = "confirmed")
            ),
            tasks = listOf(
                DashTask("t1", "pending", dueAt = dashAt("2026-10-12T08:00:00")),
                DashTask("t2", "pending", dueAt = dashAt("2026-10-13T08:00:00")),
                DashTask("t3", "completed", dueAt = dashAt("2026-10-01T08:00:00"))
            ),
            maintenance = listOf(DashMaintenance("m1", "open"))
        )
        assertEquals(
            listOf("2 unconfirmed reservations", "1 overdue task", "1 open maintenance request"),
            s.pills.map { it.text }
        )
    }

    @Test fun noPillsWhenEverythingIsQuiet() {
        assertTrue(state().pills.isEmpty())
    }

    // ---- optional sections follow the enabled modules ----

    @Test fun aBusinessWithoutTheModulesSeesNoOptionalSections() {
        val s = state(
            modules = emptySet(),
            rooms = listOf(DashRoom("1", "101", "cleaning")),
            orders = listOf(DashOrder("o", "pending")),
            barOrders = listOf(DashOrder("b", "pending")),
            laundry = listOf(DashLaundry("l", "pending"))
        )
        assertNull(s.readiness)
        assertNull(s.restaurant)
        assertNull(s.bar)
        assertNull(s.laundry)
        assertFalse(s.showRoomAssignments)
        // always-on sections stay
        assertNotNull(s.reservations)
        assertNotNull(s.maintenance)
    }

    @Test fun aBusinessWithAllTheModulesSeesEverySection() {
        val s = state()
        assertNotNull(s.readiness)
        assertNotNull(s.restaurant)
        assertNotNull(s.bar)
        assertNotNull(s.laundry)
        assertTrue(s.showRoomAssignments)
    }

    @Test fun roomAssignmentsFollowTheHousekeepingModuleAlone() {
        assertTrue(state(modules = setOf(ModuleKey.HOUSEKEEPING)).showRoomAssignments)
        assertFalse(state(modules = setOf(ModuleKey.RESTAURANT, ModuleKey.BAR, ModuleKey.LAUNDRY)).showRoomAssignments)
    }

    // ---- section contents ----

    @Test fun roomReadinessHasTheFourNumbersAndTheCleaningRooms() {
        val s = state(
            rooms = listOf(
                DashRoom("1", "101", "available"), DashRoom("2", "102", null), DashRoom("3", "103", "occupied"),
                DashRoom("4", "104", "cleaning"), DashRoom("5", "105", "maintenance"), DashRoom("6", "106", "cleaning", isDeleted = true)
            )
        )
        val r = s.readiness!!
        assertEquals(2, r.available)
        assertEquals(1, r.occupied)
        assertEquals(1, r.cleaning)
        assertEquals(1, r.maintenance)
        assertEquals(listOf("Room 104"), r.rows.map { it.primary })
    }

    @Test fun restaurantCountsActiveAndUnassignedAndListsOnlyTheUnassigned() {
        val s = state(
            orders = listOf(
                DashOrder("o1", "pending", waiterId = null, roomNumber = "4"),
                DashOrder("o2", "pending", waiterId = "unassigned", tableNumber = "2"),
                DashOrder("o3", "pending", waiterId = "w1", tableNumber = "3"),
                DashOrder("o4", "served", waiterId = null),
                DashOrder("o5", "pending", waiterId = null, isDeleted = true)
            )
        )
        val section = s.restaurant!!
        assertEquals("3 active orders · 2 unassigned", section.sub)
        assertEquals(listOf("Room 4", "Table 2"), section.rows.map { it.primary })
    }

    @Test fun barAndLaundrySectionsCountTheirOwnLists() {
        val s = state(
            barOrders = listOf(DashOrder("b1", "pending"), DashOrder("b2", "completed")),
            laundry = listOf(
                DashLaundry("l1", "pending"), DashLaundry("l2", "washing"), DashLaundry("l3", "delivered"), DashLaundry("l4", "cancelled")
            )
        )
        assertEquals("1 pending sale", s.bar!!.sub)
        assertEquals(1, s.bar!!.rows.size)
        assertEquals("2 active requests", s.laundry!!.sub)
        assertEquals(2, s.laundry!!.rows.size)
    }

    @Test fun reservationsSectionListsTheUnconfirmedOnlineReservations() {
        val s = state(
            bookings = (1..8).map { DashBooking(id = "r$it", guestName = "G$it", roomType = "Std", status = "pending", source = "online") } +
                DashBooking(id = "w", status = "pending", source = "walk_in")
        )
        assertEquals("8 unconfirmed reservations awaiting front-desk action", s.reservations.sub)
        assertEquals(6, s.reservations.rows.size)
    }

    // ---- loading and failure ----

    @Test fun loadingAndFailureStates() {
        val loading = operationsStateOf(
            Resource.Loading, ok(), ok(), ok(), ok(), ok(), ok(), ok(), allModules, DASH_NOW, DASH_LAGOS
        )
        assertTrue(loading.loading)
        val failed = operationsStateOf(
            Resource.Loading, ok(), ok(), ok(), ok(), ok(), ok(), Resource.Error("x"), allModules, DASH_NOW, DASH_LAGOS
        )
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    // ---- the ViewModel ----

    private class Fakes {
        val rooms = MutableStateFlow<Resource<List<DashRoom>>>(Resource.Success(emptyList()))
        val bookings = MutableStateFlow<Resource<List<DashBooking>>>(Resource.Success(emptyList()))
        val tasks = MutableStateFlow<Resource<List<DashTask>>>(Resource.Success(emptyList()))
        val orders = MutableStateFlow<Resource<List<DashOrder>>>(Resource.Success(emptyList()))
        val bar = MutableStateFlow<Resource<List<DashOrder>>>(Resource.Success(emptyList()))
        val laundry = MutableStateFlow<Resource<List<DashLaundry>>>(Resource.Success(emptyList()))
        val maintenance = MutableStateFlow<Resource<List<DashMaintenance>>>(Resource.Success(emptyList()))
        val attendance = MutableStateFlow<Resource<List<DashAttendance>>>(Resource.Success(emptyList()))
        fun flows() = OperationsFlows(rooms, bookings, tasks, orders, bar, laundry, maintenance, attendance)
    }

    @Test fun anAssignedTaskShowsUpInActiveTasksWithoutARefresh() = runTest {
        val fakes = Fakes()
        val vm = OperationsDashboardViewModel(
            flows = { fakes.flows() }, modules = flowOf(allModules), zone = DASH_LAGOS,
            navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0, vm.state.value.activeTasks)
        fakes.tasks.value = Resource.Success(listOf(DashTask("t1", "pending")))
        assertEquals(1, vm.state.value.activeTasks)
    }

    @Test fun enablingAModuleShowsItsSectionWithoutARefresh() = runTest {
        val fakes = Fakes()
        val modules = MutableStateFlow<Set<ModuleKey>>(emptySet())
        val seen = mutableListOf<Set<ModuleKey>>()
        val vm = OperationsDashboardViewModel(
            flows = { m -> seen += m; fakes.flows() }, modules = modules, zone = DASH_LAGOS,
            navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertNull(vm.state.value.restaurant)
        modules.value = setOf(ModuleKey.RESTAURANT)
        assertNotNull(vm.state.value.restaurant)
        assertEquals(listOf(emptySet(), setOf(ModuleKey.RESTAURANT)), seen)
    }

    @Test fun retryCollectsTheListsAgain() = runTest {
        var collected = 0
        val vm = OperationsDashboardViewModel(
            flows = {
                collected++
                Fakes().also { it.rooms.value = Resource.Error("x") }.flows()
            },
            modules = flowOf(allModules), zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertEquals(1, collected)
        vm.retry()
        assertEquals(2, collected)
    }

    @Test fun pagesOpenThroughTheShellNavigator() = runTest {
        val navigator = RecordingNavigator()
        val vm = OperationsDashboardViewModel(
            flows = { Fakes().flows() }, modules = flowOf(allModules), zone = DASH_LAGOS,
            navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.open(ROUTE_SHIFTS)
        vm.open(ROUTE_TASKS)
        vm.open(ROUTE_ROOM_ASSIGNMENTS)
        vm.open(ROUTE_ATTENDANCE)
        assertEquals(listOf("shifts", "tasks", "housekeeping/assignments", "attendance"), navigator.opened)
    }

    @Test fun sectionLinkRoutesAreTheExactPagesOfTheSpec() {
        assertEquals("room-reservations", ROUTE_ROOM_RESERVATIONS)
        assertEquals("housekeeping", ROUTE_HOUSEKEEPING)
        assertEquals("maintenance", ROUTE_MAINTENANCE)
        assertEquals("orders/history", ROUTE_ORDER_HISTORY)
        assertEquals("bar/sales-history", ROUTE_BAR_SALES_HISTORY)
        assertEquals("laundry", ROUTE_LAUNDRY)
    }
}
