package com.westly.nbms.features.finance

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Pure revenue maths (port of Westly `lib/revenue.ts`). A transaction counts as revenue ONLY when it is APPROVED.
 *
 * A.6.7 gives [resolveRange] no time-zone parameter, so the business zone lives in [zone]. `RevenueLedgerImpl`
 * sets it from `Business.timezone` whenever the session changes; until then it is Africa/Lagos.
 */
object RevenueMath {

    @Volatile
    internal var zone: ZoneId = ZoneId.of("Africa/Lagos")

    private val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    fun approvedOnly(t: List<RevenueTransaction>): List<RevenueTransaction> =
        t.filter { it.approvalStatus == ApprovalStatus.APPROVED }

    fun pendingOnly(t: List<RevenueTransaction>): List<RevenueTransaction> =
        t.filter { it.approvalStatus == ApprovalStatus.PENDING }

    fun rejectedOnly(t: List<RevenueTransaction>): List<RevenueTransaction> =
        t.filter { it.approvalStatus == ApprovalStatus.REJECTED }

    /** Plain sum of the amounts given (the caller decides which transactions to pass; use [approvedOnly] for revenue). */
    fun sum(t: List<RevenueTransaction>): Double = t.sumOf { it.amount }

    /** Sum per category over the transactions given; every category is present (0.0 when empty). */
    fun sumByCategory(t: List<RevenueTransaction>): Map<RevenueCategory, Double> {
        val result = RevenueCategory.entries.associateWith { 0.0 }.toMutableMap()
        for (txn in t) result[txn.category] = (result[txn.category] ?: 0.0) + txn.amount
        return result
    }

    /** Transactions whose date lies in [start, end], both inclusive. A transaction without a date is never in range. */
    fun inRange(t: List<RevenueTransaction>, start: Instant, end: Instant): List<RevenueTransaction> =
        t.filter { txn ->
            val d = txn.date
            d != null && !d.isBefore(start) && !d.isAfter(end)
        }

    /** Start and end instants of [preset] around [reference], in the business time zone. Weeks start on Sunday. */
    fun resolveRange(
        preset: DateRangePreset,
        reference: LocalDate,
        customStart: LocalDate? = null,
        customEnd: LocalDate? = null
    ): Pair<Instant, Instant> {
        val (firstDay, lastDay) = when (preset) {
            DateRangePreset.TODAY -> reference to reference
            DateRangePreset.WEEK -> {
                val sunday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                sunday to sunday.plusDays(6)
            }
            DateRangePreset.MONTH -> reference.withDayOfMonth(1) to reference.with(TemporalAdjusters.lastDayOfMonth())
            DateRangePreset.YEAR -> reference.withDayOfYear(1) to reference.with(TemporalAdjusters.lastDayOfYear())
            DateRangePreset.CUSTOM -> (customStart ?: reference.withDayOfMonth(1)) to (customEnd ?: reference)
        }
        return startOfDay(firstDay) to endOfDay(lastDay)
    }

    /**
     * One record per calendar day (business time zone), newest day first. Category sums and [DailyRecord.total]
     * use APPROVED transactions only; the counts use every transaction. Transactions without a date are skipped.
     */
    fun groupByDay(t: List<RevenueTransaction>): List<DailyRecord> {
        val byDay = t.filter { it.date != null }.groupBy { it.date!!.atZone(zone).toLocalDate() }
        return byDay.entries
            .sortedByDescending { it.key }
            .map { (day, list) ->
                val sums = sumByCategory(approvedOnly(list))
                val room = sums.getValue(RevenueCategory.ROOM)
                val restaurant = sums.getValue(RevenueCategory.RESTAURANT)
                val sales = sums.getValue(RevenueCategory.SALES)
                val bar = sums.getValue(RevenueCategory.BAR)
                val laundry = sums.getValue(RevenueCategory.LAUNDRY)
                val other = sums.getValue(RevenueCategory.OTHER)
                DailyRecord(
                    date = day,
                    label = day.format(dayLabel),
                    room = room,
                    restaurant = restaurant,
                    sales = sales,
                    bar = bar,
                    laundry = laundry,
                    other = other,
                    total = room + restaurant + sales + bar + laundry + other,
                    transactionCount = list.size,
                    pending = list.count { it.approvalStatus == ApprovalStatus.PENDING },
                    approved = list.count { it.approvalStatus == ApprovalStatus.APPROVED },
                    rejected = list.count { it.approvalStatus == ApprovalStatus.REJECTED }
                )
            }
    }

    private fun startOfDay(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()

    /** 23:59:59.999 of [d]. */
    private fun endOfDay(d: LocalDate): Instant = d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
}
