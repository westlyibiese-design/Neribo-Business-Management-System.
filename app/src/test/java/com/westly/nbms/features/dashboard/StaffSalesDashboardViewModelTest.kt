package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
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
class StaffSalesDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sale(total: Double, at: String?, approval: String? = "pending") =
        AmountRow(total = total, createdAt = at?.let { dashAt(it) }, approvalStatus = approval)

    private fun item(name: String, qty: Int, min: Int, deleted: Boolean = false) =
        InventoryItem(id = name, name = name, quantity = qty, minStock = min, isDeleted = deleted)

    private fun state(sales: List<AmountRow>, inventory: List<InventoryItem> = emptyList()) =
        staffStateOf(Resource.Success(sales), Resource.Success(inventory), DASH_NOW, DASH_LAGOS)

    @Test fun todayAndTotalSalesAreCountedRegardlessOfApproval() {
        val s = state(
            listOf(
                sale(1_000.0, "2026-10-12T09:00:00", "approved"),
                sale(2_500.0, "2026-10-12T09:30:00", "pending"),
                sale(4_000.0, "2026-10-11T18:00:00", "rejected"),
                sale(500.0, "2026-09-30T12:00:00", "approved")
            )
        )
        assertEquals(2, s.todayCount)
        assertEquals(3_500.0, s.todayAmount, 0.001)
        assertEquals(4, s.totalCount)
        assertEquals(8_000.0, s.totalAmount, 0.001)
    }

    @Test fun todayIsTheBusinessDayNotUtc() {
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z") // already the 12th in Lagos
        val s = staffStateOf(
            Resource.Success(listOf(sale(900.0, "2026-10-12T00:10:00"))), Resource.Success(emptyList()), late, DASH_LAGOS
        )
        assertEquals(1, s.todayCount)
    }

    @Test fun lowStockShowsTheTopFiveAndSkipsDeletedAndHealthyItems() {
        val s = state(
            emptyList(),
            listOf(
                item("A", 1, 5), item("B", 2, 5), item("C", 3, 5), item("D", 4, 5), item("E", 5, 5), item("F", 0, 5),
                item("Water", 40, 10), item("Gone", 0, 5, deleted = true)
            )
        )
        assertEquals(5, s.lowStock.size)
        assertTrue(s.lowStock.none { it.name == "Water" || it.name == "Gone" })
        assertEquals("3 left", leftText(item("C", 3, 5)))
    }

    @Test fun noLowStockGivesNoCard() {
        assertTrue(state(emptyList(), listOf(item("Water", 40, 10))).lowStock.isEmpty())
    }

    @Test fun aFailingSalesListIsAnError() {
        val s = staffStateOf(Resource.Error("x"), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        assertTrue(s.failed)
        assertFalse(s.loading)
    }

    @Test fun aFailingInventoryListOnlyHidesTheLowStockCard() {
        val s = staffStateOf(Resource.Success(listOf(sale(100.0, "2026-10-12T08:00:00"))), Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertFalse(s.failed)
        assertFalse(s.loading)
        assertEquals(1, s.todayCount)
        assertTrue(s.lowStock.isEmpty())
    }

    @Test fun loadingState() {
        assertTrue(staffStateOf(Resource.Loading, Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS).loading)
    }

    @Test fun viewModelAsksOnlyForItsOwnSalesAndOpensTheRoutes() = runTest {
        val asked = mutableListOf<String>()
        val navigator = RecordingNavigator()
        val vm = StaffSalesDashboardViewModel(
            flows = { uid ->
                asked += uid
                StaffFlows(flowOf(Resource.Success(listOf(sale(10.0, "2026-10-12T08:00:00")))), flowOf(Resource.Success(emptyList())))
            },
            uid = "staff-1", zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(listOf("staff-1"), asked)
        assertEquals(1, vm.state.value.todayCount)
        vm.open("sales/new")
        vm.open("sales/history")
        assertEquals(listOf("sales/new", "sales/history"), navigator.opened)
    }

    @Test fun aNewSaleShowsWithoutARefresh() = runTest {
        val sales = MutableStateFlow<Resource<List<AmountRow>>>(Resource.Success(emptyList()))
        val vm = StaffSalesDashboardViewModel(
            flows = { StaffFlows(sales, flowOf(Resource.Success(emptyList()))) },
            uid = "staff-1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0, vm.state.value.todayCount)
        sales.value = Resource.Success(listOf(sale(750.0, "2026-10-12T09:45:00")))
        assertEquals(1, vm.state.value.todayCount)
        assertEquals(750.0, vm.state.value.todayAmount, 0.001)
    }

    @Test fun retryCollectsTheListsAgain() = runTest {
        var collected = 0
        val vm = StaffSalesDashboardViewModel(
            flows = { collected++; StaffFlows(flowOf(Resource.Error("x")), flowOf(Resource.Success(emptyList()))) },
            uid = "staff-1", zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        vm.retry()
        assertEquals(2, collected)
    }
}
