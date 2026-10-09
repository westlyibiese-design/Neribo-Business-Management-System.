package com.westly.nbms.features.finance

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RevenueSummaryTest {

    private val lagos = ZoneId.of("Africa/Lagos")
    private val today = LocalDate.of(2026, 10, 9)

    @Before fun useLagos() { RevenueMath.zone = lagos }
    @After fun restoreZone() { RevenueMath.zone = lagos }

    /** Noon in Lagos on [day]. */
    private fun at(day: LocalDate): Instant = day.atTime(12, 0).atZone(lagos).toInstant()

    private fun oct(day: Int) = at(LocalDate.of(2026, 10, day))

    private fun txn(
        id: String,
        category: RevenueCategory,
        amount: Double,
        date: Instant?,
        status: ApprovalStatus = ApprovalStatus.APPROVED
    ) = revenueScreenTxn(id, category, amount, status, date)

    // ── month KPIs ──

    @Test fun kpisCountApprovedTransactionsOfThisMonthOnly() {
        val summary = buildRevenueSummary(
            listOf(
                txn("r", RevenueCategory.ROOM, 10_000.0, oct(2)),
                txn("s", RevenueCategory.SALES, 5_000.0, oct(3)),
                txn("o", RevenueCategory.RESTAURANT, 2_000.0, oct(4)),
                txn("b", RevenueCategory.BAR, 1_000.0, oct(5)),
                txn("l", RevenueCategory.LAUNDRY, 500.0, oct(6)),
                // Never counted: pending, rejected, last month, undated.
                txn("p", RevenueCategory.ROOM, 99_999.0, oct(2), ApprovalStatus.PENDING),
                txn("x", RevenueCategory.SALES, 7_777.0, oct(2), ApprovalStatus.REJECTED),
                txn("old", RevenueCategory.ROOM, 123_456.0, at(LocalDate.of(2026, 9, 30))),
                txn("nodate", RevenueCategory.BAR, 4_321.0, null)
            ),
            today, lagos
        )
        val k = summary.kpis
        assertEquals(10_000.0, k.room, 0.0)
        assertEquals(5_000.0, k.sales, 0.0)
        assertEquals(2_000.0, k.restaurant, 0.0)
        assertEquals(1_000.0, k.bar, 0.0)
        assertEquals(500.0, k.laundry, 0.0)
        assertEquals(18_500.0, k.total, 0.0)
    }

    @Test fun totalAlsoCountsApprovedOtherIncome() {
        val summary = buildRevenueSummary(
            listOf(
                txn("r", RevenueCategory.ROOM, 1_000.0, oct(2)),
                txn("other", RevenueCategory.OTHER, 300.0, oct(2))
            ),
            today, lagos
        )
        assertEquals(1_300.0, summary.kpis.total, 0.0)
        assertEquals(1_000.0, summary.kpis.room, 0.0)
        assertEquals(listOf(RevenueCategory.ROOM), summary.mixSlices.map { it.category })
    }

    @Test fun anEmptyLedgerShowsZerosAndNoCrash() {
        val summary = buildRevenueSummary(emptyList(), today, lagos)
        assertEquals(MonthKpis(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), summary.kpis)
        assertTrue(summary.mixSlices.isEmpty())
        assertEquals(0, summary.pendingCount)
        assertEquals(9, summary.dailyPoints.size)
        assertTrue(summary.dailyPoints.all { it.total == 0.0 })
        assertEquals(6, summary.sixMonths.size)
        assertTrue(summary.sixMonths.all { g -> g.values.all { it == 0.0 } })
    }

    // ── daily points ──

    @Test fun dailyPointsRunFromTheFirstToToday() {
        val summary = buildRevenueSummary(
            listOf(
                txn("a", RevenueCategory.ROOM, 100.0, oct(1)),
                txn("b", RevenueCategory.BAR, 50.0, oct(1)),
                txn("c", RevenueCategory.SALES, 70.0, oct(9)),
                txn("pending", RevenueCategory.ROOM, 5_000.0, oct(9), ApprovalStatus.PENDING)
            ),
            today, lagos
        )
        val points = summary.dailyPoints
        assertEquals((1..9).map { LocalDate.of(2026, 10, it) }, points.map { it.date })
        assertEquals(150.0, points[0].total, 0.0)
        assertEquals(0.0, points[4].total, 0.0)
        assertEquals(70.0, points[8].total, 0.0)
    }

    @Test fun dayBoundariesFollowTheBusinessTimeZone() {
        // 23:30 UTC on 30 Sep is 00:30 on 1 Oct in Lagos (UTC+1).
        val lateUtc = Instant.parse("2026-09-30T23:30:00Z")
        val summary = buildRevenueSummary(listOf(txn("edge", RevenueCategory.ROOM, 400.0, lateUtc)), today, lagos)
        assertEquals(400.0, summary.dailyPoints.first().total, 0.0)
        assertEquals(400.0, summary.kpis.room, 0.0)
        val september = summary.sixMonths[4]
        assertEquals(0.0, september.room, 0.0)
    }

    @Test fun firstDayOfTheMonthHasASinglePoint() {
        val summary = buildRevenueSummary(emptyList(), LocalDate.of(2026, 10, 1), lagos)
        assertEquals(1, summary.dailyPoints.size)
        assertEquals(LocalDate.of(2026, 10, 1), summary.dailyPoints[0].date)
    }

    // ── mix slices ──

    @Test fun mixDropsZeroSlicesAndKeepsTheCardOrder() {
        val summary = buildRevenueSummary(
            listOf(
                txn("bar", RevenueCategory.BAR, 30.0, oct(2)),
                txn("room", RevenueCategory.ROOM, 70.0, oct(2)),
                txn("laundry-pending", RevenueCategory.LAUNDRY, 999.0, oct(2), ApprovalStatus.PENDING)
            ),
            today, lagos
        )
        assertEquals(listOf(RevenueCategory.ROOM, RevenueCategory.BAR), summary.mixSlices.map { it.category })
        assertEquals(listOf(70.0, 30.0), summary.mixSlices.map { it.amount })
    }

    // ── six months ──

    @Test fun sixMonthsEndThisMonthWithShortLabels() {
        val summary = buildRevenueSummary(emptyList(), today, lagos)
        assertEquals(listOf("May", "Jun", "Jul", "Aug", "Sep", "Oct"), summary.sixMonths.map { it.label })
        assertEquals(YearMonth.of(2026, 10), summary.sixMonths.last().month)
    }

    @Test fun sixMonthsCrossTheYearBoundary() {
        val summary = buildRevenueSummary(emptyList(), LocalDate.of(2026, 1, 15), lagos)
        assertEquals(listOf("Aug", "Sep", "Oct", "Nov", "Dec", "Jan"), summary.sixMonths.map { it.label })
        assertEquals(YearMonth.of(2025, 8), summary.sixMonths.first().month)
    }

    @Test fun sixMonthGroupsSumApprovedRevenuePerSource() {
        val summary = buildRevenueSummary(
            listOf(
                txn("aug-room", RevenueCategory.ROOM, 100.0, at(LocalDate.of(2026, 8, 31))),
                txn("sep-sales", RevenueCategory.SALES, 40.0, at(LocalDate.of(2026, 9, 1))),
                txn("sep-sales2", RevenueCategory.SALES, 60.0, at(LocalDate.of(2026, 9, 30))),
                txn("sep-rest", RevenueCategory.RESTAURANT, 25.0, at(LocalDate.of(2026, 9, 15))),
                txn("oct-bar", RevenueCategory.BAR, 15.0, oct(3)),
                txn("oct-laundry", RevenueCategory.LAUNDRY, 5.0, oct(3)),
                txn("april", RevenueCategory.ROOM, 9_999.0, at(LocalDate.of(2026, 4, 30))),
                txn("sep-pending", RevenueCategory.SALES, 8_888.0, at(LocalDate.of(2026, 9, 10)), ApprovalStatus.PENDING),
                txn("sep-rejected", RevenueCategory.BAR, 7_777.0, at(LocalDate.of(2026, 9, 10)), ApprovalStatus.REJECTED)
            ),
            today, lagos
        )
        val byLabel = summary.sixMonths.associateBy { it.label }
        assertEquals(listOf(100.0, 0.0, 0.0, 0.0, 0.0), byLabel.getValue("Aug").values)
        assertEquals(listOf(0.0, 100.0, 25.0, 0.0, 0.0), byLabel.getValue("Sep").values)
        assertEquals(listOf(0.0, 0.0, 0.0, 15.0, 5.0), byLabel.getValue("Oct").values)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), byLabel.getValue("May").values)
    }

    // ── pending count ──

    @Test fun pendingCountIncludesEveryPendingTransaction() {
        val summary = buildRevenueSummary(
            listOf(
                txn("p1", RevenueCategory.ROOM, 10.0, oct(2), ApprovalStatus.PENDING),
                txn("p2", RevenueCategory.SALES, 10.0, at(LocalDate.of(2026, 8, 2)), ApprovalStatus.PENDING),
                txn("p3", RevenueCategory.BAR, 10.0, null, ApprovalStatus.PENDING),
                txn("a", RevenueCategory.ROOM, 10.0, oct(2)),
                txn("r", RevenueCategory.ROOM, 10.0, oct(2), ApprovalStatus.REJECTED)
            ),
            today, lagos
        )
        assertEquals(3, summary.pendingCount)
        assertEquals(10.0, summary.kpis.total, 0.0)
    }

    @Test fun pendingButtonTextIsSingularOrPlural() {
        assertEquals("1 pending approval", pendingLabel(1))
        assertEquals("2 pending approvals", pendingLabel(2))
        assertEquals("12 pending approvals", pendingLabel(12))
    }

    // ── page state from the ledger ──

    @Test fun pageStateFollowsTheLedger() = runTest {
        val ledger = RevenueScreenFakeLedger(Resource.Loading)
        assertEquals(RevenueUiState.Loading, revenueStateOf(ledger.observe().first(), today, lagos))

        ledger.flow.value = Resource.Error("boom")
        val error = revenueStateOf(ledger.observe().first(), today, lagos) as RevenueUiState.Error
        assertEquals("We couldn't load revenue data.", error.message)

        ledger.flow.value = Resource.Success(listOf(txn("r", RevenueCategory.ROOM, 250.0, oct(2))))
        val ready = revenueStateOf(ledger.observe().first(), today, lagos) as RevenueUiState.Ready
        assertEquals(250.0, ready.summary.kpis.total, 0.0)
    }

    @Test fun invalidTimeZoneFallsBackToLagos() {
        assertEquals(lagos, businessZoneOf("Not/AZone"))
        assertEquals(lagos, businessZoneOf(null))
        assertEquals(ZoneId.of("Europe/London"), businessZoneOf("Europe/London"))
    }
}
