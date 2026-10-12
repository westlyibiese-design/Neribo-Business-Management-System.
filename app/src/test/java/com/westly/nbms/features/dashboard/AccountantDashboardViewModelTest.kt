package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.DashExpense
import com.westly.nbms.features.finance.ApprovalStatus
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
class AccountantDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun expense(id: String, amount: Double, local: String, deleted: Boolean = false) =
        DashExpense(id = id, amount = amount, date = dashAt(local), isDeleted = deleted)

    private fun revenue(id: String, amount: Double, local: String) = dashTxn(id, amount, dashAt(local))

    private fun pending(id: String) = dashTxn(id, 500.0, dashAt("2026-10-10T10:00:00"), ApprovalStatus.PENDING)

    private fun state(
        expenses: List<DashExpense> = emptyList(),
        approved: List<RevenueTransaction> = emptyList(),
        pending: List<RevenueTransaction> = emptyList()
    ) = accountantStateOf(Resource.Success(expenses), Resource.Success(approved), Resource.Success(pending), DASH_NOW, DASH_LAGOS)

    // ---- the four cards ----

    @Test fun netIsRevenueMinusExpensesAndMarginComesFromDashboardMath() {
        val s = state(
            expenses = listOf(expense("e1", 300.0, "2026-10-03T10:00:00"), expense("e2", 100.0, "2026-10-09T10:00:00")),
            approved = listOf(revenue("r1", 600.0, "2026-10-02T10:00:00"), revenue("r2", 400.0, "2026-10-11T10:00:00"))
        )
        assertEquals(1000.0, s.monthRevenue, 0.0)
        assertEquals(400.0, s.monthExpenses, 0.0)
        assertEquals(600.0, s.net, 0.0)
        assertEquals(DashboardMath.margin(1000.0, 600.0), s.margin)
        assertEquals(60, s.margin)
        assertFalse(s.netNegative)
    }

    @Test fun aLossTurnsTheNetCardRedAndTheMarginNegative() {
        val s = state(
            expenses = listOf(expense("e1", 300.0, "2026-10-03T10:00:00")),
            approved = listOf(revenue("r1", 100.0, "2026-10-02T10:00:00"))
        )
        assertEquals(-200.0, s.net, 0.0)
        assertTrue(s.netNegative)
        assertEquals(-200, s.margin)
    }

    @Test fun breakEvenIsNotALoss() {
        val s = state(
            expenses = listOf(expense("e1", 250.0, "2026-10-03T10:00:00")),
            approved = listOf(revenue("r1", 250.0, "2026-10-02T10:00:00"))
        )
        assertEquals(0.0, s.net, 0.0)
        assertFalse(s.netNegative)
        assertEquals(0, s.margin)
    }

    @Test fun noRevenueGivesAZeroMarginNotADivisionByZero() {
        val s = state(expenses = listOf(expense("e1", 300.0, "2026-10-03T10:00:00")))
        assertEquals(0.0, s.monthRevenue, 0.0)
        assertEquals(-300.0, s.net, 0.0)
        assertEquals(0, s.margin)
        assertTrue(s.netNegative)
    }

    @Test fun onlyThisMonthCountsAndDeletedExpensesAreLeftOut() {
        val s = state(
            expenses = listOf(
                expense("e1", 100.0, "2026-10-05T10:00:00"), expense("e2", 900.0, "2026-09-30T10:00:00"),
                expense("e3", 500.0, "2026-10-06T10:00:00", deleted = true)
            ),
            approved = listOf(revenue("r1", 700.0, "2026-10-05T10:00:00"), revenue("r2", 800.0, "2026-09-30T10:00:00"))
        )
        assertEquals(700.0, s.monthRevenue, 0.0)
        assertEquals(100.0, s.monthExpenses, 0.0)
    }

    @Test fun monthBoundariesUseTheBusinessZoneNotUtc() {
        // 23:30 UTC on 30 September is already 1 October in Lagos, so it counts this month
        val late = java.time.Instant.parse("2026-09-30T23:30:00Z")
        val s = accountantStateOf(
            Resource.Success(emptyList()),
            Resource.Success(listOf(dashTxn("r1", 250.0, late))),
            Resource.Success(emptyList()),
            DASH_NOW, DASH_LAGOS
        )
        assertEquals(250.0, s.monthRevenue, 0.0)
    }

    // ---- pending approvals ----

    @Test fun pendingCountIsTheNumberOfPendingPayments() {
        assertEquals(0, state().pendingCount)
        assertEquals(3, state(pending = listOf(pending("p1"), pending("p2"), pending("p3"))).pendingCount)
    }

    @Test fun pendingPaymentsNeverCountAsRevenue() {
        val s = state(
            approved = listOf(revenue("r1", 100.0, "2026-10-02T10:00:00")),
            pending = listOf(pending("p1"))
        )
        assertEquals(100.0, s.monthRevenue, 0.0)
        assertEquals(1, s.pendingCount)
    }

    @Test fun approvalsTextsFollowTheCount() {
        assertEquals("1 payment awaiting your approval", approvalsAwaitingText(1))
        assertEquals("4 payments awaiting your approval", approvalsAwaitingText(4))
        assertEquals("Approvals", approvalsButtonText(0))
        assertEquals("Approvals (4)", approvalsButtonText(4))
    }

    // ---- the six-month chart ----

    @Test fun sixMonthsRunOldestToCurrentWithRevenueAndExpenses() {
        val s = state(
            expenses = listOf(expense("e1", 400.0, "2026-10-03T10:00:00"), expense("e2", 70.0, "2026-07-03T10:00:00")),
            approved = listOf(revenue("r1", 1000.0, "2026-10-02T10:00:00"), revenue("r2", 200.0, "2026-05-02T10:00:00"))
        )
        assertEquals(listOf("May", "Jun", "Jul", "Aug", "Sep", "Oct"), s.months.map { it.label })
        assertEquals(200.0, s.months.first().revenue, 0.0)
        assertEquals(70.0, s.months[2].expenses, 0.0)
        assertEquals(1000.0, s.months.last().revenue, 0.0)
        assertEquals(400.0, s.months.last().expenses, 0.0)
    }

    // ---- header ----

    @Test fun subtitleIsTheMonthAndYearInTheBusinessZone() {
        assertEquals("Welcome, Ada · October 2026", accountantSubtitle("Ada", DASH_NOW, DASH_LAGOS))
        val late = java.time.Instant.parse("2026-09-30T23:30:00Z")
        assertEquals("Welcome, Ada · October 2026", accountantSubtitle("Ada", late, DASH_LAGOS))
        assertEquals("Welcome, Ada · September 2026", accountantSubtitle("Ada", late, java.time.ZoneId.of("UTC")))
    }

    // ---- loading and failure ----

    @Test fun loadingAndFailureStates() {
        val loading = accountantStateOf(Resource.Loading, Resource.Success(emptyList()), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        assertTrue(loading.loading)
        val failed = accountantStateOf(Resource.Success(emptyList()), Resource.Loading, Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    // ---- the ViewModel ----

    @Test fun approvingAPaymentUpdatesTheDashboardWithoutARefresh() = runTest {
        val approved = MutableStateFlow<Resource<List<RevenueTransaction>>>(Resource.Success(listOf(revenue("r1", 100.0, "2026-10-02T10:00:00"))))
        val pendingFlow = MutableStateFlow<Resource<List<RevenueTransaction>>>(Resource.Success(listOf(pending("p1"), pending("p2"))))
        val vm = AccountantDashboardViewModel(
            flows = { AccountantFlows(flowOf(Resource.Success(emptyList())), approved, pendingFlow) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(100.0, vm.state.value.monthRevenue, 0.0)
        assertEquals(2, vm.state.value.pendingCount)

        // p1 is approved: it leaves the pending list and joins the approved ledger
        val p1Approved = dashTxn("p1", 500.0, dashAt("2026-10-10T10:00:00"), ApprovalStatus.APPROVED)
        pendingFlow.value = Resource.Success(listOf(pending("p2")))
        approved.value = Resource.Success(listOf(revenue("r1", 100.0, "2026-10-02T10:00:00"), p1Approved))
        assertEquals(600.0, vm.state.value.monthRevenue, 0.0)
        assertEquals(1, vm.state.value.pendingCount)
    }

    @Test fun retryCollectsTheListsAgain() = runTest {
        var collected = 0
        val vm = AccountantDashboardViewModel(
            flows = {
                collected++
                AccountantFlows(flowOf(Resource.Error("x")), flowOf(Resource.Success(emptyList())), flowOf(Resource.Success(emptyList())))
            },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertEquals(1, collected)
        vm.retry()
        assertEquals(2, collected)
    }

    @Test fun buttonsOpenTheFinancePagesThroughTheShellNavigator() = runTest {
        val navigator = RecordingNavigator()
        val vm = AccountantDashboardViewModel(
            flows = { AccountantFlows(flowOf(Resource.Loading), flowOf(Resource.Loading), flowOf(Resource.Loading)) },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.openApprovals()
        vm.openExpenses()
        vm.openRevenue()
        vm.openFinancialReports()
        assertEquals(listOf("approvals", "expenses", "revenue", "financial-reports"), navigator.opened)
    }
}
