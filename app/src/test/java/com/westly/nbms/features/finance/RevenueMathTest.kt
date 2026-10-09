package com.westly.nbms.features.finance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RevenueMathTest {

    private val lagos = ZoneId.of("Africa/Lagos")

    @Before fun useLagos() { RevenueMath.zone = lagos }
    @After fun restoreZone() { RevenueMath.zone = lagos }

    private fun startOf(d: LocalDate, zone: ZoneId = lagos): Instant = d.atStartOfDay(zone).toInstant()
    private fun endOf(d: LocalDate, zone: ZoneId = lagos): Instant = d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)

    // ── filters and sums ──

    private val mixed = listOf(
        financeTxn("a", amount = 100.0, status = ApprovalStatus.APPROVED, category = RevenueCategory.ROOM),
        financeTxn("b", amount = 50.0, status = ApprovalStatus.APPROVED, category = RevenueCategory.BAR),
        financeTxn("c", amount = 70.0, status = ApprovalStatus.PENDING, category = RevenueCategory.ROOM),
        financeTxn("d", amount = 30.0, status = ApprovalStatus.REJECTED, category = RevenueCategory.SALES)
    )

    @Test fun statusFilters() {
        assertEquals(listOf("a", "b"), RevenueMath.approvedOnly(mixed).map { it.id })
        assertEquals(listOf("c"), RevenueMath.pendingOnly(mixed).map { it.id })
        assertEquals(listOf("d"), RevenueMath.rejectedOnly(mixed).map { it.id })
    }

    @Test fun sumAddsTheAmountsGiven() {
        assertEquals(150.0, RevenueMath.sum(RevenueMath.approvedOnly(mixed)), 0.0)
        assertEquals(0.0, RevenueMath.sum(emptyList()), 0.0)
    }

    @Test fun sumByCategoryHasEveryCategory() {
        val sums = RevenueMath.sumByCategory(RevenueMath.approvedOnly(mixed))
        assertEquals(RevenueCategory.entries.toSet(), sums.keys)
        assertEquals(100.0, sums.getValue(RevenueCategory.ROOM), 0.0)
        assertEquals(50.0, sums.getValue(RevenueCategory.BAR), 0.0)
        assertEquals(0.0, sums.getValue(RevenueCategory.SALES), 0.0)
    }

    @Test fun inRangeIsInclusiveAndSkipsUndated() {
        val start = Instant.parse("2026-10-01T00:00:00Z")
        val end = Instant.parse("2026-10-31T23:59:59.999Z")
        val list = listOf(
            financeTxn("before", date = start.minusMillis(1)),
            financeTxn("atStart", date = start),
            financeTxn("middle", date = Instant.parse("2026-10-15T12:00:00Z")),
            financeTxn("atEnd", date = end),
            financeTxn("after", date = end.plusMillis(1)),
            financeTxn("undated", date = null)
        )
        assertEquals(listOf("atStart", "middle", "atEnd"), RevenueMath.inRange(list, start, end).map { it.id })
    }

    // ── resolveRange ──

    @Test fun todayIsTheReferenceDay() {
        val ref = LocalDate.of(2026, 10, 7)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.TODAY, ref)
        assertEquals(startOf(ref), s)
        assertEquals(endOf(ref), e)
    }

    @Test fun weekRunsSundayToSaturdayFromAMidweekDay() {
        val wednesday = LocalDate.of(2026, 10, 7)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.WEEK, wednesday)
        assertEquals(startOf(LocalDate.of(2026, 10, 4)), s)
        assertEquals(endOf(LocalDate.of(2026, 10, 10)), e)
    }

    @Test fun weekStartingOnSundayKeepsThatSunday() {
        val sunday = LocalDate.of(2026, 10, 4)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.WEEK, sunday)
        assertEquals(startOf(sunday), s)
        assertEquals(endOf(LocalDate.of(2026, 10, 10)), e)
    }

    @Test fun saturdayBelongsToTheWeekThatStartedOnSunday() {
        val saturday = LocalDate.of(2026, 10, 10)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.WEEK, saturday)
        assertEquals(startOf(LocalDate.of(2026, 10, 4)), s)
        assertEquals(endOf(saturday), e)
    }

    @Test fun monthRunsFirstToLastDay() {
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.MONTH, LocalDate.of(2026, 2, 14))
        assertEquals(startOf(LocalDate.of(2026, 2, 1)), s)
        assertEquals(endOf(LocalDate.of(2026, 2, 28)), e)
    }

    @Test fun yearRunsJanuaryFirstToDecemberThirtyFirst() {
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.YEAR, LocalDate.of(2026, 10, 7))
        assertEquals(startOf(LocalDate.of(2026, 1, 1)), s)
        assertEquals(endOf(LocalDate.of(2026, 12, 31)), e)
    }

    @Test fun customUsesTheGivenDates() {
        val (s, e) = RevenueMath.resolveRange(
            DateRangePreset.CUSTOM, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20)
        )
        assertEquals(startOf(LocalDate.of(2026, 9, 10)), s)
        assertEquals(endOf(LocalDate.of(2026, 9, 20)), e)
    }

    @Test fun customDefaultsToFirstOfReferenceMonthUntilReferenceDay() {
        val ref = LocalDate.of(2026, 10, 7)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.CUSTOM, ref)
        assertEquals(startOf(LocalDate.of(2026, 10, 1)), s)
        assertEquals(endOf(ref), e)
    }

    @Test fun endOfRangeIsTheLastMillisecondOfTheDay() {
        val ref = LocalDate.of(2026, 10, 7)
        val (_, e) = RevenueMath.resolveRange(DateRangePreset.TODAY, ref)
        assertEquals(startOf(ref.plusDays(1)).minusMillis(1), e)
    }

    @Test fun rangesFollowTheBusinessTimeZone() {
        val newYork = ZoneId.of("America/New_York")
        RevenueMath.zone = newYork
        val ref = LocalDate.of(2026, 10, 7)
        val (s, e) = RevenueMath.resolveRange(DateRangePreset.TODAY, ref)
        assertEquals(startOf(ref, newYork), s)
        assertEquals(endOf(ref, newYork), e)
        assertTrue(s != startOf(ref, lagos))
    }

    // ── groupByDay ──

    @Test fun groupByDaySumsApprovedOnlyAndCountsEverything() {
        val day = Instant.parse("2026-10-07T10:00:00Z")
        val list = listOf(
            financeTxn("1", category = RevenueCategory.ROOM, amount = 100.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("2", category = RevenueCategory.RESTAURANT, amount = 40.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("3", category = RevenueCategory.SALES, amount = 10.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("4", category = RevenueCategory.BAR, amount = 20.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("5", category = RevenueCategory.LAUNDRY, amount = 5.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("6", category = RevenueCategory.OTHER, amount = 2.0, status = ApprovalStatus.APPROVED, date = day),
            financeTxn("7", category = RevenueCategory.ROOM, amount = 900.0, status = ApprovalStatus.PENDING, date = day),
            financeTxn("8", category = RevenueCategory.ROOM, amount = 800.0, status = ApprovalStatus.REJECTED, date = day)
        )
        val record = RevenueMath.groupByDay(list).single()
        assertEquals(LocalDate.of(2026, 10, 7), record.date)
        assertEquals("Oct 7, 2026", record.label)
        assertEquals(100.0, record.room, 0.0)
        assertEquals(40.0, record.restaurant, 0.0)
        assertEquals(10.0, record.sales, 0.0)
        assertEquals(20.0, record.bar, 0.0)
        assertEquals(5.0, record.laundry, 0.0)
        assertEquals(2.0, record.other, 0.0)
        assertEquals(177.0, record.total, 0.0)
        assertEquals(8, record.transactionCount)
        assertEquals(6, record.approved)
        assertEquals(1, record.pending)
        assertEquals(1, record.rejected)
    }

    @Test fun groupByDayIsNewestFirstAndUsesTheBusinessZoneForTheDayEdge() {
        // 23:30Z on Oct 6 is already Oct 7 in Lagos (UTC+1).
        val list = listOf(
            financeTxn("late", date = Instant.parse("2026-10-06T23:30:00Z"), amount = 1.0, status = ApprovalStatus.APPROVED),
            financeTxn("early", date = Instant.parse("2026-10-06T10:00:00Z"), amount = 2.0, status = ApprovalStatus.APPROVED),
            financeTxn("later", date = Instant.parse("2026-10-08T10:00:00Z"), amount = 4.0, status = ApprovalStatus.APPROVED),
            financeTxn("undated", date = null)
        )
        val days = RevenueMath.groupByDay(list)
        assertEquals(listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 6)), days.map { it.date })
        assertEquals(1.0, days[1].total, 0.0)
        assertEquals(2.0, days[2].total, 0.0)
        assertEquals(3, days.sumOf { it.transactionCount })
    }

    @Test fun aDayWithOnlyPendingMoneyHasZeroTotalButCounts() {
        val record = RevenueMath.groupByDay(listOf(financeTxn("p", amount = 500.0, status = ApprovalStatus.PENDING))).single()
        assertEquals(0.0, record.total, 0.0)
        assertEquals(1, record.transactionCount)
        assertEquals(1, record.pending)
    }

    @Test fun groupByDayOfNothingIsEmpty() {
        assertTrue(RevenueMath.groupByDay(emptyList()).isEmpty())
    }
}
