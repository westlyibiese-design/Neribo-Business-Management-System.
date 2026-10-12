package com.westly.nbms.features.performance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The current calendar month in the business zone: [start] inclusive, [endExclusive] is midnight of the next month. */
data class MonthBounds(
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val start: Instant,
    val endExclusive: Instant
)

/** One person's month. Counts include pending records; revenue counts approved records only (NBMS rule A.6.7). */
data class StaffPerformance(
    val userId: String,
    val name: String,
    val roleKey: String?,
    val checkins: Int,
    val checkouts: Int,
    val sales: Int,
    val orders: Int,
    val salesRevenue: Double,
    val ordersRevenue: Double,
    val presentDays: Int
) {
    val totalActivities: Int get() = checkins + checkouts + sales + orders

    /** The leaderboard's Check-Ins column. */
    val checkInsAndOuts: Int get() = checkins + checkouts

    /** The leaderboard's Sales column. */
    val salesAndOrders: Int get() = sales + orders

    /** The leaderboard's Revenue column. */
    val revenue: Double get() = salesRevenue + ordersRevenue
}

/** The y axis of the stacked chart: ticks at 0, [step], 2×[step] … [top]. Whole numbers only. */
data class AxisScale(val step: Int, val top: Int) {
    val ticks: List<Int> get() = (0..top).step(step).toList()
}

/**
 * Every number of the Staff Performance page (Phase 34B), as pure functions. Nothing reads a clock or a database: `now` and
 * the business [ZoneId] are always passed in, and "this month" is the calendar month in that zone (never UTC).
 */
object PerformanceMath {

    /** How many people the chart shows. */
    const val CHART_LIMIT = 8

    private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val MONTH_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

    /** First instant of the month containing [now] in [zone], and of the month after it. */
    fun monthBounds(now: Instant, zone: ZoneId): MonthBounds {
        val first = now.atZone(zone).toLocalDate().withDayOfMonth(1)
        val next = first.plusMonths(1)
        return MonthBounds(
            firstDay = first,
            lastDay = next.minusDays(1),
            start = first.atStartOfDay(zone).toInstant(),
            endExclusive = next.atStartOfDay(zone).toInstant()
        )
    }

    /** "October 2026". */
    fun monthLabel(now: Instant, zone: ZoneId): String = MONTH_YEAR.format(now.atZone(zone))

    /** "October". */
    fun monthName(now: Instant, zone: ZoneId): String = MONTH_ONLY.format(now.atZone(zone))

    /** True when [i] falls inside [bounds]. */
    fun inMonth(i: Instant?, bounds: MonthBounds): Boolean =
        i != null && !i.isBefore(bounds.start) && i.isBefore(bounds.endExclusive)

    /** Revenue rule: only a record whose approvalStatus is "approved" counts. */
    fun isApproved(status: String?): Boolean = status?.trim()?.lowercase() == "approved"

    /** Attendance rule: "present" and "late" are days worked. */
    fun isPresentDay(status: String?): Boolean = status?.trim()?.lowercase().let { it == "present" || it == "late" }

    /** The calendar day of an attendance record: its `dateKey`, else the day of its `date` in [zone]. */
    fun attendanceDay(a: PerfAttendance, zone: ZoneId): LocalDate? {
        val fromKey = a.dateKey?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return fromKey ?: a.date?.atZone(zone)?.toLocalDate()
    }

    /** "Ada Obi" -> "Ada"; a blank name gives "Staff". */
    fun firstName(name: String): String = name.trim().substringBefore(' ').ifBlank { "Staff" }

    /**
     * One [StaffPerformance] per active user for the month of [now] in [zone], most active first (ties by name, ignoring case).
     * Records of people who are not in [users] are ignored.
     */
    fun compute(
        users: List<PerfUser>,
        checkins: List<PerfStamp>,
        checkouts: List<PerfStamp>,
        sales: List<PerfSale>,
        orders: List<PerfSale>,
        attendance: List<PerfAttendance>,
        now: Instant,
        zone: ZoneId
    ): List<StaffPerformance> {
        val month = monthBounds(now, zone)
        val checkinCount = checkins.filter { inMonth(it.at, month) }.groupingBy { it.staffId }.eachCount()
        val checkoutCount = checkouts.filter { inMonth(it.at, month) }.groupingBy { it.staffId }.eachCount()
        val monthSales = sales.filter { inMonth(it.at, month) }.groupBy { it.staffId }
        val monthOrders = orders.filter { inMonth(it.at, month) }.groupBy { it.staffId }
        val presentCount = attendance
            .filter { isPresentDay(it.status) }
            .filter { a -> attendanceDay(a, zone)?.let { it >= month.firstDay && it <= month.lastDay } == true }
            .groupingBy { it.staffId }
            .eachCount()

        return users
            .distinctBy { it.id }
            .map { u ->
                val mySales = monthSales[u.id].orEmpty()
                val myOrders = monthOrders[u.id].orEmpty()
                StaffPerformance(
                    userId = u.id,
                    name = u.name,
                    roleKey = u.role,
                    checkins = checkinCount[u.id] ?: 0,
                    checkouts = checkoutCount[u.id] ?: 0,
                    sales = mySales.size,
                    orders = myOrders.size,
                    salesRevenue = mySales.filter { isApproved(it.approvalStatus) }.sumOf { it.total },
                    ordersRevenue = myOrders.filter { isApproved(it.approvalStatus) }.sumOf { it.total },
                    presentDays = presentCount[u.id] ?: 0
                )
            }
            .sortedWith(compareByDescending<StaffPerformance> { it.totalActivities }.thenBy { it.name.lowercase() })
    }

    /** The people the chart shows: the first [n] of an already sorted list. */
    fun top(sorted: List<StaffPerformance>, n: Int = CHART_LIMIT): List<StaffPerformance> = sorted.take(n.coerceAtLeast(0))

    /** A whole-number axis for [maxValue]: about four steps of 1, 2, 5 or 10 × a power of ten, and a top that covers the value. */
    fun axisScale(maxValue: Int): AxisScale {
        val max = maxValue.coerceAtLeast(1)
        val raw = ceil(max / 4.0).coerceAtLeast(1.0)
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalized = raw / magnitude
        val nice = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        val step = (nice * magnitude).toInt().coerceAtLeast(1)
        val top = ceil(max / step.toDouble()).toInt().coerceAtLeast(1) * step
        return AxisScale(step, top)
    }
}
