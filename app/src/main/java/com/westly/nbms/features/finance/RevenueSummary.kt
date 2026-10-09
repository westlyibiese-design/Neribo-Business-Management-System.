package com.westly.nbms.features.finance

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The five income categories shown on the Revenue page, in the order of the cards, the donut and the bar series. */
internal val REVENUE_PAGE_CATEGORIES: List<RevenueCategory> = listOf(
    RevenueCategory.ROOM,
    RevenueCategory.SALES,
    RevenueCategory.RESTAURANT,
    RevenueCategory.BAR,
    RevenueCategory.LAUNDRY
)

/** Approved money in the current month. [total] counts every approved transaction (including "Other Income"). */
internal data class MonthKpis(
    val total: Double,
    val room: Double,
    val sales: Double,
    val restaurant: Double,
    val bar: Double,
    val laundry: Double
)

/** One day of the current month: [date] is the calendar day in the business time zone, [total] its approved revenue. */
internal data class DayPoint(val date: LocalDate, val total: Double)

/** One non-zero slice of the Revenue Mix. */
internal data class MixSlice(val category: RevenueCategory, val amount: Double)

/** Approved revenue of one calendar month, per source. [label] is the short month name such as "Oct". */
internal data class MonthGroup(
    val month: YearMonth,
    val label: String,
    val room: Double,
    val sales: Double,
    val restaurant: Double,
    val bar: Double,
    val laundry: Double
) {
    /** Values in the order of [REVENUE_PAGE_CATEGORIES]. */
    val values: List<Double> get() = listOf(room, sales, restaurant, bar, laundry)
}

/** Everything the Revenue page shows, computed in one pure step from the ledger's transactions. */
internal data class RevenueSummary(
    val today: LocalDate,
    val kpis: MonthKpis,
    val dailyPoints: List<DayPoint>,
    val mixSlices: List<MixSlice>,
    val sixMonths: List<MonthGroup>,
    val pendingCount: Int
)

private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

/**
 * Builds the page numbers. ONLY approved transactions count as revenue; pending and rejected ones never add to anything.
 *
 * Month boundaries come from [RevenueMath.resolveRange] and [RevenueMath.inRange] (the ledger sets their time zone from
 * `Business.timezone`); the day of each transaction is read in the same business zone, [zone].
 * [pendingCount] is every pending transaction, whatever its date.
 */
internal fun buildRevenueSummary(txns: List<RevenueTransaction>, today: LocalDate, zone: ZoneId): RevenueSummary {
    val approved = RevenueMath.approvedOnly(txns)

    fun approvedInMonth(day: LocalDate): List<RevenueTransaction> {
        val (start, end) = RevenueMath.resolveRange(DateRangePreset.MONTH, day)
        return RevenueMath.inRange(approved, start, end)
    }

    val thisMonth = approvedInMonth(today)
    val byCategory = RevenueMath.sumByCategory(thisMonth)
    val kpis = MonthKpis(
        total = RevenueMath.sum(thisMonth),
        room = byCategory.getValue(RevenueCategory.ROOM),
        sales = byCategory.getValue(RevenueCategory.SALES),
        restaurant = byCategory.getValue(RevenueCategory.RESTAURANT),
        bar = byCategory.getValue(RevenueCategory.BAR),
        laundry = byCategory.getValue(RevenueCategory.LAUNDRY)
    )

    val perDay: Map<LocalDate, Double> = thisMonth
        .filter { it.date != null }
        .groupBy { it.date!!.atZone(zone).toLocalDate() }
        .mapValues { (_, list) -> RevenueMath.sum(list) }
    val monthStart = today.withDayOfMonth(1)
    val dailyPoints = (1..today.dayOfMonth).map { dayOfMonth ->
        val date = monthStart.withDayOfMonth(dayOfMonth)
        DayPoint(date, perDay[date] ?: 0.0)
    }

    val mixSlices = REVENUE_PAGE_CATEGORIES
        .map { MixSlice(it, byCategory.getValue(it)) }
        .filter { it.amount > 0.0 }

    val thisYearMonth = YearMonth.from(today)
    val sixMonths = (5 downTo 0).map { back ->
        val month = thisYearMonth.minusMonths(back.toLong())
        val sums = RevenueMath.sumByCategory(approvedInMonth(month.atDay(1)))
        MonthGroup(
            month = month,
            label = month.format(MONTH_SHORT),
            room = sums.getValue(RevenueCategory.ROOM),
            sales = sums.getValue(RevenueCategory.SALES),
            restaurant = sums.getValue(RevenueCategory.RESTAURANT),
            bar = sums.getValue(RevenueCategory.BAR),
            laundry = sums.getValue(RevenueCategory.LAUNDRY)
        )
    }

    return RevenueSummary(
        today = today,
        kpis = kpis,
        dailyPoints = dailyPoints,
        mixSlices = mixSlices,
        sixMonths = sixMonths,
        pendingCount = RevenueMath.pendingOnly(txns).size
    )
}
