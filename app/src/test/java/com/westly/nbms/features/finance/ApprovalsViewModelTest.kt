package com.westly.nbms.features.finance

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    /** A Friday. Weeks start on Sunday, so this week is 4 to 10 October 2026. */
    private val today: LocalDate = LocalDate.of(2026, 10, 9)

    // a approved today · b pending today · c rejected yesterday · d approved 2 Oct · e approved last month
    // f pending in January · g approved with no date
    private val a = approvalsTxn("a", ApprovalStatus.APPROVED, 10_000.0, "2026-10-09T09:00:00Z", "Ada Obi", "Room Payment",
        approvedByName = "Ngozi", approvedAt = "2026-10-09T11:00:00Z")
    private val b = approvalsTxn("b", ApprovalStatus.PENDING, 2_000.0, "2026-10-09T10:00:00Z", "Bola Ade", "Bar Sale",
        RevenueCategory.BAR, SourceCollection.BAR_ORDERS)
    private val c = approvalsTxn("c", ApprovalStatus.REJECTED, 500.0, "2026-10-08T10:00:00Z", "Chidi Eze", "Sale",
        RevenueCategory.SALES, SourceCollection.SALES, reason = "Duplicate")
    private val d = approvalsTxn("d", ApprovalStatus.APPROVED, 3_000.0, "2026-10-02T10:00:00Z", "Dayo Bello", "Laundry",
        RevenueCategory.LAUNDRY, SourceCollection.LAUNDRY_REQUESTS)
    private val e = approvalsTxn("e", ApprovalStatus.APPROVED, 7_000.0, "2026-09-20T10:00:00Z", "Ada Obi", "Deposit")
    private val f = approvalsTxn("f", ApprovalStatus.PENDING, 1_500.0, "2026-01-15T10:00:00Z", "Femi Ojo", "Room Payment")
    private val g = approvalsTxn("g", ApprovalStatus.APPROVED, 900.0, null, "Gina Ude", "Room Payment")
    private val all = listOf(a, b, c, d, e, f, g)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RevenueMath.zone = lagos
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ready(filters: ApprovalsFilters = ApprovalsFilters(), rows: List<RevenueTransaction> = all): ApprovalsView.Ready =
        approvalsViewOf(Resource.Success(rows), filters, today, lagos) as ApprovalsView.Ready

    private fun ids(rows: List<RevenueTransaction>) = rows.map { it.id }

    // ── states ──

    @Test fun loadingAndErrorMapToTheirStates() {
        assertEquals(ApprovalsView.Loading, approvalsViewOf(Resource.Loading, ApprovalsFilters(), today, lagos))
        assertEquals(ApprovalsView.Error, approvalsViewOf(Resource.Error("boom"), ApprovalsFilters(), today, lagos))
    }

    // ── range presets ──

    @Test fun defaultRangeIsThisMonth() {
        assertEquals(DateRangePreset.MONTH, ApprovalsFilters().range)
        assertEquals(listOf("b", "a", "c", "d"), ids(ready().history))
    }

    @Test fun todayRange() {
        assertEquals(listOf("b", "a"), ids(ready(ApprovalsFilters(range = DateRangePreset.TODAY)).history))
    }

    @Test fun weekRangeStartsOnSunday() {
        val r = ready(ApprovalsFilters(range = DateRangePreset.WEEK))
        assertEquals(LocalDate.of(2026, 10, 4), r.rangeStart)
        assertEquals(LocalDate.of(2026, 10, 10), r.rangeEnd)
        assertEquals(listOf("b", "a", "c"), ids(r.history))
    }

    @Test fun yearRangeKeepsEveryDatedRowOfTheYear() {
        assertEquals(listOf("b", "a", "c", "d", "e", "f"), ids(ready(ApprovalsFilters(range = DateRangePreset.YEAR)).history))
    }

    @Test fun customRangeUsesTheTwoDates() {
        val r = ready(
            ApprovalsFilters(
                range = DateRangePreset.CUSTOM,
                customStart = LocalDate.of(2026, 9, 1),
                customEnd = LocalDate.of(2026, 9, 30)
            )
        )
        assertEquals(listOf("e"), ids(r.history))
        assertEquals(LocalDate.of(2026, 9, 1), r.rangeStart)
        assertEquals(LocalDate.of(2026, 9, 30), r.rangeEnd)
    }

    @Test fun customRangeWithoutDatesFallsBackToMonthStartAndToday() {
        val r = ready(ApprovalsFilters(range = DateRangePreset.CUSTOM))
        assertEquals(LocalDate.of(2026, 10, 1), r.rangeStart)
        assertEquals(today, r.rangeEnd)
    }

    @Test fun rangeBoundsMatchRevenueMathResolveRange() {
        val (start, end) = RevenueMath.resolveRange(DateRangePreset.MONTH, today)
        val r = ready()
        assertEquals(start.atZone(lagos).toLocalDate(), r.rangeStart)
        assertEquals(end.atZone(lagos).toLocalDate(), r.rangeEnd)
    }

    @Test fun rowsWithoutADateAreNeverInARange() {
        assertFalse("g" in ids(ready(ApprovalsFilters(range = DateRangePreset.YEAR)).history))
    }

    @Test fun historyIsNewestFirst() {
        val r = ready(ApprovalsFilters(range = DateRangePreset.YEAR))
        assertEquals(listOf("b", "a", "c", "d", "e", "f"), ids(r.history))
    }

    // ── status and search filters ──

    @Test fun statusFilterNarrowsHistory() {
        assertEquals(listOf("b"), ids(ready(ApprovalsFilters(status = ApprovalsStatusFilter.PENDING)).history))
        assertEquals(listOf("a", "d"), ids(ready(ApprovalsFilters(status = ApprovalsStatusFilter.APPROVED)).history))
        assertEquals(listOf("c"), ids(ready(ApprovalsFilters(status = ApprovalsStatusFilter.REJECTED)).history))
        assertEquals(4, ready(ApprovalsFilters(status = ApprovalsStatusFilter.ALL)).history.size)
    }

    @Test fun searchMatchesGuestNameAndTypeLabelIgnoringCase() {
        assertEquals(listOf("a"), ids(ready(ApprovalsFilters(search = "ada")).history))
        assertEquals(listOf("b"), ids(ready(ApprovalsFilters(search = "BAR sale")).history))
        assertEquals(listOf("d"), ids(ready(ApprovalsFilters(search = " laundry ")).history))
        assertTrue(ready(ApprovalsFilters(search = "nobody")).history.isEmpty())
    }

    @Test fun searchAndStatusWorkTogether() {
        assertEquals(listOf("c"), ids(ready(ApprovalsFilters(search = "sale", status = ApprovalsStatusFilter.REJECTED)).history))
        assertTrue(ready(ApprovalsFilters(search = "ada", status = ApprovalsStatusFilter.REJECTED)).history.isEmpty())
        assertEquals(listOf("a"), ids(ready(ApprovalsFilters(search = "ada", status = ApprovalsStatusFilter.APPROVED)).history))
    }

    // ── stat tiles ──

    @Test fun statTilesAreComputedOverTheRange() {
        val s = ready().stats
        assertEquals(13_000.0, s.approvedRevenue, 0.0)
        assertEquals(4, s.transactions)
        assertEquals(1, s.pendingCount)
        assertEquals(2_000.0, s.pendingAmount, 0.0)
        assertEquals(1, s.rejectedCount)
        assertEquals(500.0, s.rejectedAmount, 0.0)
    }

    @Test fun statTilesIgnoreTheStatusAndSearchFilters() {
        val plain = ready().stats
        val narrowed = ready(ApprovalsFilters(status = ApprovalsStatusFilter.REJECTED, search = "chidi")).stats
        assertEquals(plain, narrowed)
    }

    @Test fun statTilesFollowTheRange() {
        val s = ready(ApprovalsFilters(range = DateRangePreset.TODAY)).stats
        assertEquals(10_000.0, s.approvedRevenue, 0.0)
        assertEquals(2, s.transactions)
        assertEquals(0, s.rejectedCount)
    }

    // ── pending tab ──

    @Test fun pendingListHoldsEveryPendingRowRegardlessOfRange() {
        assertEquals(listOf("b", "f"), ids(ready().pending))
        assertEquals(listOf("b", "f"), ids(ready(ApprovalsFilters(range = DateRangePreset.TODAY, status = ApprovalsStatusFilter.REJECTED)).pending))
    }

    // ── daily records ──

    @Test fun dailyRecordsAreGroupedPerDayNewestFirstOverTheRange() {
        val days = ready().daily
        assertEquals(listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 2)), days.map { it.date })
        val first = days.first()
        assertEquals(10_000.0, first.total, 0.0)
        assertEquals(2, first.transactionCount)
        assertEquals(1, first.pending)
        assertEquals(1, first.approved)
    }

    @Test fun dailyRecordsFollowTheStatusFilter() {
        val days = ready(ApprovalsFilters(status = ApprovalsStatusFilter.REJECTED)).daily
        assertEquals(listOf(LocalDate.of(2026, 10, 8)), days.map { it.date })
        assertEquals(0.0, days.single().total, 0.0)
        assertEquals(1, days.single().rejected)
    }

    @Test fun dailyRecordsIgnoreSearch() {
        assertEquals(ready().daily, ready(ApprovalsFilters(search = "zzz")).daily)
    }

    // ── role gating ──

    @Test fun onlySuperAdminAndAccountantMayReview() {
        Role.entries.forEach { role ->
            val expected = role == Role.SUPER_ADMIN || role == Role.ACCOUNTANT
            assertEquals(role.key, expected, approvalsCanReview(role))
        }
    }

    // ── view model: fixtures ──

    private class Harness(val vm: ApprovalsViewModel, val ledger: ApprovalsFakeLedger, val toasts: MutableList<ToastEvent>)

    private fun TestScope.harness(role: Role?, initial: List<RevenueTransaction> = all): Harness {
        val ledger = ApprovalsFakeLedger(Resource.Success(initial))
        val toast = ToastController()
        val toasts = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { toasts += it } }
        val vm = ApprovalsViewModel(ledger, ApprovalsFakeSession(role), toast)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
        return Harness(vm, ledger, toasts)
    }


    // ── view model: reading ──

    @Test fun viewModelShowsLedgerRowsAndRespondsToFilters() = runTest {
        val now = Instant.now()
        val mine = approvalsTxn("now", ApprovalStatus.APPROVED, 4_000.0, now.toString())
        val h = harness(Role.ACCOUNTANT, listOf(mine, f))
        val v1 = h.vm.view.value as ApprovalsView.Ready
        assertEquals(listOf("now"), ids(v1.history))
        assertEquals(listOf("f"), ids(v1.pending))
        h.vm.setStatus(ApprovalsStatusFilter.REJECTED)
        assertTrue((h.vm.view.value as ApprovalsView.Ready).history.isEmpty())
        assertEquals(1, (h.vm.view.value as ApprovalsView.Ready).stats.transactions)
        h.vm.setStatus(ApprovalsStatusFilter.ALL)
        h.vm.setSearch("zzz")
        assertTrue((h.vm.view.value as ApprovalsView.Ready).history.isEmpty())
    }

    @Test fun viewModelShowsAnErrorWhenTheLedgerFails() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.ledger.flow.value = Resource.Error("down")
        assertEquals(ApprovalsView.Error, h.vm.view.value)
    }

    @Test fun retryReadsTheLedgerAgain() = runTest {
        val h = harness(Role.ACCOUNTANT)
        val before = h.ledger.observeCalls
        h.vm.retry()
        assertTrue(h.ledger.observeCalls > before)
    }

    @Test fun filterSettersUpdateTheFilters() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.vm.setRange(DateRangePreset.CUSTOM)
        h.vm.setCustomStart(LocalDate.of(2026, 1, 1))
        h.vm.setCustomEnd(LocalDate.of(2026, 1, 31))
        h.vm.setStatus(ApprovalsStatusFilter.PENDING)
        h.vm.setSearch("x")
        val fl = h.vm.filters.value
        assertEquals(DateRangePreset.CUSTOM, fl.range)
        assertEquals(LocalDate.of(2026, 1, 1), fl.customStart)
        assertEquals(LocalDate.of(2026, 1, 31), fl.customEnd)
        assertEquals(ApprovalsStatusFilter.PENDING, fl.status)
        assertEquals("x", fl.search)
    }

    // ── view model: approve ──

    @Test fun accountantApprovesThroughTheLedgerAndSeesTheToast() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.vm.approve(a.copy(approvalStatus = ApprovalStatus.PENDING))
        assertEquals(listOf("a"), ids(h.ledger.approved))
        val t = h.toasts.single()
        assertEquals("Payment Approved", t.title)
        assertEquals("₦10,000 from Ada Obi is now counted as revenue.", t.message)
        assertEquals(ToastType.Success, t.type)
        assertTrue(h.vm.busy.value.isEmpty())
    }

    @Test fun superAdminMayApprove() = runTest {
        val h = harness(Role.SUPER_ADMIN)
        h.vm.approve(b)
        assertEquals(listOf("b"), ids(h.ledger.approved))
    }

    @Test fun managerCannotApproveOrReject() = runTest {
        val h = harness(Role.MANAGER)
        h.vm.approve(b)
        var done = false
        h.vm.reject(b, "no") { done = true }
        assertTrue(h.ledger.approved.isEmpty())
        assertTrue(h.ledger.rejected.isEmpty())
        assertTrue(h.toasts.isEmpty())
        assertFalse(done)
    }

    @Test fun receptionistAndSignedOutCannotApprove() = runTest {
        val rec = harness(Role.RECEPTIONIST)
        rec.vm.approve(b)
        assertTrue(rec.ledger.approved.isEmpty())
        val out = harness(null)
        out.vm.approve(b)
        assertTrue(out.ledger.approved.isEmpty())
    }

    @Test fun failedApproveShowsTheErrorAndClearsBusy() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.ledger.failWith = IllegalStateException("Already approved")
        h.vm.approve(b)
        val t = h.toasts.single()
        assertEquals("Error", t.title)
        assertEquals("Already approved", t.message)
        assertEquals(ToastType.Error, t.type)
        assertTrue(h.vm.busy.value.isEmpty())
    }

    @Test fun failedApproveWithoutAMessageUsesTheFallback() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.ledger.failWith = RuntimeException()
        h.vm.approve(b)
        assertEquals(MSG_APPROVALS_FALLBACK_ERROR, h.toasts.single().message)
    }

    @Test fun secondTapWhileApprovingDoesNothing() = runTest {
        val h = harness(Role.ACCOUNTANT)
        val gate = CompletableDeferred<Unit>()
        h.ledger.gate = gate
        h.vm.approve(b)
        assertEquals(setOf(approvalsTxnKey(b)), h.vm.busy.value)
        h.vm.approve(b)
        h.vm.reject(b, "x") {}
        assertEquals(1, h.ledger.approved.size)
        assertTrue(h.ledger.rejected.isEmpty())
        gate.complete(Unit)
        assertTrue(h.vm.busy.value.isEmpty())
        assertEquals(1, h.toasts.size)
    }

    @Test fun twoDifferentPaymentsCanBeBusyAtOnce() = runTest {
        val h = harness(Role.ACCOUNTANT)
        val gate = CompletableDeferred<Unit>()
        h.ledger.gate = gate
        h.vm.approve(b)
        h.vm.approve(f)
        assertEquals(2, h.ledger.approved.size)
        gate.complete(Unit)
        assertTrue(h.vm.busy.value.isEmpty())
    }

    // ── view model: reject ──

    @Test fun rejectSendsTheTrimmedReasonAndClosesTheDialog() = runTest {
        val h = harness(Role.ACCOUNTANT)
        var done = false
        h.vm.reject(b, "  duplicate entry  ") { done = true }
        assertEquals(listOf(b to "duplicate entry"), h.ledger.rejected)
        assertTrue(done)
        val t = h.toasts.single()
        assertEquals("Payment Rejected", t.title)
        assertEquals("₦2,000 from Bola Ade was excluded from revenue.", t.message)
        assertTrue(h.vm.busy.value.isEmpty())
    }

    @Test fun aBlankReasonIsSentAsNull() = runTest {
        val h = harness(Role.SUPER_ADMIN)
        h.vm.reject(b, "   ") {}
        assertNull(h.ledger.rejected.single().second)
    }

    @Test fun failedRejectKeepsTheDialogOpenAndShowsTheError() = runTest {
        val h = harness(Role.ACCOUNTANT)
        h.ledger.failWith = IllegalStateException("Network error")
        var done = false
        h.vm.reject(b, "x") { done = true }
        assertFalse(done)
        assertEquals("Error", h.toasts.single().title)
        assertEquals("Network error", h.toasts.single().message)
        assertTrue(h.vm.busy.value.isEmpty())
    }

    @Test fun txnKeyIsUniqueAcrossSources() {
        val p = approvalsTxn("1", source = SourceCollection.PAYMENTS)
        val s = approvalsTxn("1", source = SourceCollection.SALES)
        assertFalse(approvalsTxnKey(p) == approvalsTxnKey(s))
    }

    @Test fun rangeLabelsMatchTheDropdown() {
        assertEquals(
            listOf("Today", "This Week", "This Month", "This Year", "Custom Range"),
            DateRangePreset.entries.map { approvalsRangeLabel(it) }
        )
        assertEquals(
            listOf("All Statuses", "Pending", "Approved", "Rejected"),
            ApprovalsStatusFilter.entries.map { it.label }
        )
    }
}
