package com.westly.nbms.features.reports

import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.rooms.Room
import java.time.Instant
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One month of the Annual Report. Money is in naira (major unit). */
internal data class AnnualMonth(
    val month: YearMonth,
    /** Approved revenue of the month. */
    val revenue: Double,
    /** Non-deleted expenses of the month. */
    val expenses: Double,
    /** `revenue - expenses`. */
    val profit: Double,
    /** Non-deleted bookings created in the month. */
    val bookings: Int
) {
    /** "Jan", "Feb", ... */
    val label: String get() = annualMonthLabel(month)
}

/** The whole Annual Report: January up to the current month, the year-to-date sums and the current occupancy. */
internal data class AnnualReport(
    val year: Int,
    val months: List<AnnualMonth>,
    val ytdRevenue: Double,
    val ytdExpenses: Double,
    val ytdProfit: Double,
    /** Whole percent of rooms with a checked-in booking; 0 when there are no rooms. */
    val occupancyPercent: Int
)

/** "Jan" for January (English short name, never depends on the phone language). */
internal fun annualMonthLabel(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "2026 Year-to-Date". */
internal fun annualSubtitle(year: Int): String = "$year Year-to-Date"

/** Pure maths of the Annual Report. No Android, no Firestore. Revenue counts APPROVED transactions only. */
internal object AnnualReportCalculator {

    /** First and last millisecond of [month] in the business time zone (the same bounds the Revenue page uses). */
    fun monthBounds(month: YearMonth, zone: ZoneId): Pair<Instant, Instant> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1)
        return start to end
    }

    /** Every month from January of [current]'s year up to and including [current]. */
    fun monthsUpTo(current: YearMonth): List<YearMonth> =
        (1..current.monthValue).map { YearMonth.of(current.year, Month.of(it)) }

    /**
     * Current occupancy: checked-in bookings divided by rooms, as a whole percent. Deleted bookings and deleted rooms
     * are ignored. 0 when there are no rooms.
     */
    fun occupancyPercent(bookings: List<Booking>, rooms: List<Room>): Int {
        val roomCount = rooms.count { !it.isDeleted }
        if (roomCount == 0) return 0
        val checkedIn = bookings.count { !it.isDeleted && it.status == BookingStatus.CHECKED_IN.key }
        return Math.round(checkedIn.toDouble() / roomCount.toDouble() * 100.0).toInt()
    }

    fun build(
        transactions: List<RevenueTransaction>,
        expenses: List<Expense>,
        bookings: List<Booking>,
        rooms: List<Room>,
        current: YearMonth,
        zone: ZoneId
    ): AnnualReport {
        val approved = RevenueMath.approvedOnly(transactions)
        val liveExpenses = expenses.filter { !it.isDeleted }
        val liveBookings = bookings.filter { !it.isDeleted }

        val months = monthsUpTo(current).map { month ->
            val (start, end) = monthBounds(month, zone)
            val revenue = RevenueMath.sum(RevenueMath.inRange(approved, start, end))
            val spent = ExpenseRules.total(ExpenseRules.inMonth(liveExpenses, month, zone))
            val created = liveBookings.count { booking ->
                val at = booking.createdAt?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
                at != null && YearMonth.from(at.atZone(zone)) == month
            }
            AnnualMonth(month = month, revenue = revenue, expenses = spent, profit = revenue - spent, bookings = created)
        }

        val ytdRevenue = months.sumOf { it.revenue }
        val ytdExpenses = months.sumOf { it.expenses }
        return AnnualReport(
            year = current.year,
            months = months,
            ytdRevenue = ytdRevenue,
            ytdExpenses = ytdExpenses,
            ytdProfit = ytdRevenue - ytdExpenses,
            occupancyPercent = occupancyPercent(liveBookings, rooms)
        )
    }
}
