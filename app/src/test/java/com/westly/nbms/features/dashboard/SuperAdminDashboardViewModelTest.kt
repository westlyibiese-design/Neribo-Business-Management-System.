package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.ActivityItem
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashExpense
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.finance.ApprovalStatus
import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
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
class SuperAdminDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val rooms = listOf(
        DashRoom("1", "101", "available"), DashRoom("2", "102", "occupied"), DashRoom("3", "103", "occupied"),
        DashRoom("4", "104", "cleaning"), DashRoom("5", "105", "maintenance"), DashRoom("6", "106", "available", isDeleted = true)
    )

    private val bookings = listOf(
        dashBooking("a", status = "pending", checkIn = dashAt("2026-10-12T14:00:00"), createdAt = dashAt("2026-10-10T09:00:00")),
        dashBooking("b", status = "checked_in", checkIn = dashAt("2026-10-12T08:00:00"), createdAt = dashAt("2026-10-11T09:00:00")),
        dashBooking("c", status = "confirmed", checkIn = dashAt("2026-10-20T14:00:00"), checkOut = dashAt("2026-10-12T11:00:00"), createdAt = dashAt("2026-10-12T07:00:00")),
        dashBooking("d", status = "pending", checkIn = dashAt("2026-10-30T14:00:00"), createdAt = dashAt("2026-10-01T09:00:00")),
        dashBooking("e", status = "confirmed", createdAt = dashAt("2026-10-02T09:00:00")),
        dashBooking("f", status = "confirmed", createdAt = dashAt("2026-10-03T09:00:00")),
        dashBooking("g", status = "pending", createdAt = dashAt("2026-10-09T09:00:00"), deleted = true)
    )

    private fun state(
        bookingList: List<DashBooking> = bookings,
        transactions: List<RevenueTransaction> = emptyList(),
        expenses: List<DashExpense> = emptyList(),
        activity: List<ActivityItem> = emptyList()
    ) = superAdminStateOf(
        Resource.Success(rooms), Resource.Success(bookingList), Resource.Success(expenses),
        Resource.Success(RevenueMath.approvedOnly(transactions)), Resource.Success(activity), DASH_NOW, DASH_LAGOS
    )

    @Test fun revenueMonthCountsApprovedPaymentsOnly() {
        val txns = listOf(
            dashTxn("1", 10_000.0, dashAt("2026-10-03T10:00:00")),
            dashTxn("2", 5_000.0, dashAt("2026-10-04T10:00:00"), ApprovalStatus.PENDING),
            dashTxn("3", 7_000.0, dashAt("2026-10-05T10:00:00"), ApprovalStatus.REJECTED),
            dashTxn("4", 99_000.0, dashAt("2026-09-30T10:00:00"))
        )
        assertEquals(10_000.0, state(transactions = txns).monthRevenue, 0.0)
    }

    @Test fun netIsRevenueMinusThisMonthsExpenses() {
        val txns = listOf(dashTxn("1", 10_000.0, dashAt("2026-10-03T10:00:00")))
        val expenses = listOf(
            DashExpense("e1", 3_000.0, dashAt("2026-10-02T10:00:00")),
            DashExpense("e2", 500.0, dashAt("2026-09-28T10:00:00")),
            DashExpense("e3", 9_000.0, dashAt("2026-10-02T10:00:00"), isDeleted = true)
        )
        val s = state(transactions = txns, expenses = expenses)
        assertEquals(7_000.0, s.net, 0.0)
        val deficit = state(expenses = listOf(DashExpense("e", 2_000.0, dashAt("2026-10-02T10:00:00"))))
        assertEquals(-2_000.0, deficit.net, 0.0)
    }

    @Test fun occupancyAndRoomCountsIgnoreDeletedRooms() {
        val s = state()
        assertEquals(5, s.rooms.total)
        assertEquals(2, s.rooms.occupied)
        assertEquals(1, s.rooms.available)
        assertEquals(1, s.rooms.cleaning)
        assertEquals(1, s.rooms.maintenance)
        assertEquals(40, s.occupancyPercent)
    }

    @Test fun todaysActivityIsCheckInsPlusCheckOuts() {
        val s = state()
        assertEquals(2, s.checkIns)       // a (14:00) and b (08:00)
        assertEquals(1, s.checkOuts)      // c (11:00)
        assertEquals("↑2 in · ↓1 out", activitySub(s.checkIns, s.checkOuts))
    }

    @Test fun pendingApprovalsAreBookingsWithStatusPending() {
        assertEquals(2, state().pendingApprovals)   // a and d; g is deleted
    }

    @Test fun lastSevenDaysHasSevenPointsEndingToday() {
        val txns = listOf(
            dashTxn("1", 1_000.0, dashAt("2026-10-12T09:00:00")),
            dashTxn("2", 2_000.0, dashAt("2026-10-11T09:00:00")),
            dashTxn("3", 4_000.0, dashAt("2026-10-12T09:30:00"), ApprovalStatus.PENDING)
        )
        val points = state(transactions = txns).last7Days
        assertEquals(7, points.size)
        assertEquals("Oct 12", points.last().label)
        assertEquals(1_000.0, points.last().value, 0.0)
        assertEquals(2_000.0, points[5].value, 0.0)
    }

    @Test fun recentBookingsAreTheFiveNewestByCreatedAt() {
        val ids = state().recentBookings.map { it.id }
        assertEquals(listOf("c", "b", "a", "f", "e"), ids)
        assertFalse("g" in ids)
    }

    @Test fun activityFeedShowsTheEightNewestItems() {
        val items = (1..10).map { ActivityItem("walk_in", "Item $it", it * 1_000L, "x") }
        val shown = state(activity = items).activity
        assertEquals(8, shown.size)
        assertEquals("Item 10", shown.first().text)
        assertEquals("Item 3", shown.last().text)
    }

    @Test fun loadingUntilEveryListArrivesAndFailureWins() {
        val loading = superAdminStateOf(Resource.Loading, Resource.Success(bookings), Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Loading, DASH_NOW, DASH_LAGOS)
        assertTrue(loading.loading)
        assertFalse(loading.failed)
        val failed = superAdminStateOf(Resource.Loading, Resource.Error("x"), Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    @Test fun anActivityFeedStillLoadingDoesNotHoldTheDashboardBack() {
        val s = superAdminStateOf(Resource.Success(rooms), Resource.Success(bookings), Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Loading, DASH_NOW, DASH_LAGOS)
        assertFalse(s.loading)
        assertTrue(s.activity.isEmpty())
    }

    @Test fun businessZoneDecidesWhatToday() = runTest {
        // 23:30 UTC on the 11th is already the 12th in Lagos
        val late = dashAt("2026-10-12T00:30:00")
        val s = superAdminStateOf(
            Resource.Success(rooms), Resource.Success(listOf(dashBooking("z", status = "confirmed", checkIn = late))),
            Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Success(emptyList()),
            java.time.Instant.parse("2026-10-11T23:30:00Z"), DASH_LAGOS
        )
        assertEquals(1, s.checkIns)
    }

    @Test fun approvingAPaymentRaisesRevenueWithoutARefresh() = runTest {
        val pending = dashTxn("p", 5_000.0, dashAt("2026-10-04T10:00:00"), ApprovalStatus.PENDING)
        val approvedFlow = MutableStateFlow<Resource<List<RevenueTransaction>>>(Resource.Success(RevenueMath.approvedOnly(listOf(pending))))
        val navigator = RecordingNavigator()
        val vm = SuperAdminDashboardViewModel(
            flows = {
                SuperAdminFlows(
                    rooms = MutableStateFlow(Resource.Success(rooms)), bookings = MutableStateFlow(Resource.Success(bookings)),
                    expenses = MutableStateFlow(Resource.Success(emptyList())), approvedRevenue = approvedFlow,
                    activity = MutableStateFlow(Resource.Success(emptyList()))
                )
            },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0.0, vm.state.value.monthRevenue, 0.0)
        approvedFlow.value = Resource.Success(RevenueMath.approvedOnly(listOf(pending.copy(approvalStatus = ApprovalStatus.APPROVED))))
        assertEquals(5_000.0, vm.state.value.monthRevenue, 0.0)
        assertFalse(vm.state.value.loading)
    }

    @Test fun buttonsOpenBookingsAndRooms() {
        val navigator = RecordingNavigator()
        val vm = SuperAdminDashboardViewModel(
            flows = {
                SuperAdminFlows(flowOf(Resource.Loading), flowOf(Resource.Loading), flowOf(Resource.Loading), flowOf(Resource.Loading), flowOf(Resource.Loading))
            },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.openBookings()
        vm.openRooms()
        assertEquals(listOf("bookings", "rooms"), navigator.opened)
    }
}
