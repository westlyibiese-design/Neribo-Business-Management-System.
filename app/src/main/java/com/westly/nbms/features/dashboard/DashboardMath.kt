package com.westly.nbms.features.dashboard

import com.westly.nbms.features.dashboard.data.DashAttendance
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashExpense
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import com.westly.nbms.features.dashboard.data.DashLaundry
import com.westly.nbms.features.dashboard.data.DashMaintenance
import com.westly.nbms.features.dashboard.data.DashOrder
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.dashboard.data.DashTask
import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.charts.ChartPoint
import com.westly.nbms.features.gym.GymLogic
import com.westly.nbms.features.gym.MembershipStatus
import com.westly.nbms.features.inventory.InventoryItem
import com.westly.nbms.features.inventory.InventoryLogic
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RoomCounts(
    val total: Int,
    val available: Int,
    val occupied: Int,
    val cleaning: Int,
    val maintenance: Int,
    val reserved: Int,
    val outOfService: Int
)

data class MonthPair(val label: String, val revenue: Double, val expenses: Double)

data class GymSummary(val activeMembers: Int, val inGymNow: Int, val visitsToday: Int)

/**
 * Every number the management dashboards show, as pure functions (Phase 33A1). Nothing here reads a clock or a database:
 * `now` and the business [ZoneId] are always passed in. Deleted rows (`isDeleted`) are ignored everywhere, and revenue is
 * the approved-only ledger.
 */
object DashboardMath {

    private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    // ── rooms ──

    /** Status null/blank counts as "available"; deleted rooms are excluded; [RoomCounts.total] is the number of live rooms. */
    fun roomCounts(rooms: List<DashRoom>): RoomCounts {
        val live = rooms.filter { !it.isDeleted }
        fun count(key: String) = live.count { roomStatus(it) == key }
        return RoomCounts(
            total = live.size,
            available = count("available"),
            occupied = count("occupied"),
            cleaning = count("cleaning"),
            maintenance = count("maintenance"),
            reserved = count("reserved"),
            outOfService = count("out_of_service")
        )
    }

    private fun roomStatus(room: DashRoom): String =
        room.status?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_').orEmpty().ifEmpty { "available" }

    /** 0 when there are no rooms, else the rounded percentage. */
    fun occupancyPercent(occupied: Int, total: Int): Int =
        if (total <= 0) 0 else Math.round(occupied * 100.0 / total).toInt()

    // ── bookings ──

    /** The time a check-in/check-out really happened, else the time it was scheduled. */
    fun eventTime(actual: Instant?, scheduled: Instant?): Instant? = actual ?: scheduled

    /** True when [i] falls on the same calendar day as [now] in [zone] (never UTC). */
    fun isToday(i: Instant?, now: Instant, zone: ZoneId): Boolean =
        i != null && i.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

    fun todayCheckIns(b: List<DashBooking>, now: Instant, zone: ZoneId): List<DashBooking> =
        b.filter { !it.isDeleted && isToday(eventTime(it.checkInAt, it.checkIn), now, zone) }

    fun todayCheckOuts(b: List<DashBooking>, now: Instant, zone: ZoneId): List<DashBooking> =
        b.filter { !it.isDeleted && isToday(eventTime(it.checkOutAt, it.checkOut), now, zone) }

    /** status == "pending" (walk-ins included). */
    fun pendingBookings(b: List<DashBooking>): List<DashBooking> =
        b.filter { !it.isDeleted && it.status == "pending" }

    /** status == "pending" and not a walk-in (online reservations waiting for review). */
    fun pendingReservations(b: List<DashBooking>): List<DashBooking> =
        b.filter { !it.isDeleted && it.status == "pending" && it.source != "walk_in" }

    /** Newest `createdAt` first; a booking without `createdAt` goes last. */
    fun recentBookings(b: List<DashBooking>, n: Int): List<DashBooking> =
        b.filter { !it.isDeleted }
            .sortedByDescending { it.createdAt ?: Instant.MIN }
            .take(n.coerceAtLeast(0))

    // ── money ──

    /** Approved revenue of the calendar month of [now] in [zone]. Anything not approved is ignored even if passed in. */
    fun monthRevenue(approved: List<RevenueTransaction>, now: Instant, zone: ZoneId): Double {
        val (start, end) = monthBounds(YearMonth.from(now.atZone(zone)), zone)
        return RevenueMath.sum(RevenueMath.inRange(RevenueMath.approvedOnly(approved), start, end))
    }

    /** Sum of the non-deleted expenses dated within the calendar month of [now] in [zone]. */
    fun monthExpenses(e: List<DashExpense>, now: Instant, zone: ZoneId): Double {
        val (start, end) = monthBounds(YearMonth.from(now.atZone(zone)), zone)
        return e.filter { !it.isDeleted && it.date != null && !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sumOf { it.amount }
    }

    /** 0 when revenue is 0, else round(net / revenue × 100). */
    fun margin(revenue: Double, net: Double): Int =
        if (revenue == 0.0) 0 else Math.round(net / revenue * 100.0).toInt()

    /** The last 7 local days, oldest first, today last. Label "MMM d"; a day without revenue is 0. */
    fun last7Days(approved: List<RevenueTransaction>, now: Instant, zone: ZoneId): List<ChartPoint> {
        val today = now.atZone(zone).toLocalDate()
        val perDay = RevenueMath.approvedOnly(approved)
            .filter { it.date != null }
            .groupBy { it.date!!.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> RevenueMath.sum(list) }
        return (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            ChartPoint(label = day.format(DAY_LABEL), value = perDay[day] ?: 0.0)
        }
    }

    /** The last 6 calendar months, oldest first, current month last. Label "MMM". */
    fun last6Months(
        approved: List<RevenueTransaction>,
        expenses: List<DashExpense>,
        now: Instant,
        zone: ZoneId
    ): List<MonthPair> {
        val current = YearMonth.from(now.atZone(zone))
        val revenuePerMonth = RevenueMath.approvedOnly(approved)
            .filter { it.date != null }
            .groupBy { YearMonth.from(it.date!!.atZone(zone)) }
            .mapValues { (_, list) -> RevenueMath.sum(list) }
        val expensesPerMonth = expenses
            .filter { !it.isDeleted && it.date != null }
            .groupBy { YearMonth.from(it.date!!.atZone(zone)) }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        return (5 downTo 0).map { back ->
            val month = current.minusMonths(back.toLong())
            MonthPair(
                label = month.atDay(1).format(MONTH_LABEL),
                revenue = revenuePerMonth[month] ?: 0.0,
                expenses = expensesPerMonth[month] ?: 0.0
            )
        }
    }

    private fun monthBounds(month: YearMonth, zone: ZoneId): Pair<Instant, Instant> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1)
        return start to end
    }

    // ── stock, staff, tasks ──

    fun lowStock(items: List<InventoryItem>): List<InventoryItem> =
        items.filter { !it.isDeleted && InventoryLogic.isLow(it) }

    /** Attendance of [todayKey] (yyyy-MM-dd in the business zone) marked "present". */
    fun staffOnDuty(a: List<DashAttendance>, todayKey: String): List<DashAttendance> =
        a.filter { !it.isDeleted && it.dateKey == todayKey && it.status == "present" }

    /** Not completed and not cancelled. */
    fun activeTasks(t: List<DashTask>): List<DashTask> =
        t.filter { !it.isDeleted && it.status !in TASK_FINISHED }

    /** Active tasks whose due time has passed. */
    fun overdueTasks(t: List<DashTask>, now: Instant): List<DashTask> =
        activeTasks(t).filter { it.dueAt != null && it.dueAt.isBefore(now) }

    // ── operations ──

    /** Laundry that is not delivered and not cancelled. */
    fun activeLaundry(l: List<DashLaundry>): List<DashLaundry> =
        l.filter { !it.isDeleted && it.status !in LAUNDRY_FINISHED }

    /**
     * Maintenance that is not resolved. NBMS stores a finished request as "closed" (Westly's word was "resolved"), so both
     * count as finished.
     */
    fun openMaintenance(m: List<DashMaintenance>): List<DashMaintenance> =
        m.filter { !it.isDeleted && it.status !in MAINTENANCE_FINISHED }

    fun pendingFood(o: List<DashOrder>): List<DashOrder> =
        o.filter { !it.isDeleted && it.status == "pending" }

    /** Pending orders with no waiter: `waiterId` null, blank or "unassigned". */
    fun unassignedFood(o: List<DashOrder>): List<DashOrder> =
        pendingFood(o).filter { val w = it.waiterId; w.isNullOrBlank() || w == "unassigned" }

    // ── gym ──

    /**
     * Active members use Phase 29's effective status (an end date in the past is expired even if stored "active");
     * [GymSummary.inGymNow] counts visits with no check-out; [GymSummary.visitsToday] counts visits whose dateKey is [todayKey].
     */
    fun gymSummary(
        members: List<DashGymMember>,
        visits: List<DashGymVisit>,
        todayKey: String,
        now: Instant
    ): GymSummary {
        val liveVisits = visits.filter { !it.isDeleted }
        return GymSummary(
            activeMembers = members.count {
                !it.isDeleted && GymLogic.effectiveStatus(it.status, it.endDate, now) == MembershipStatus.ACTIVE
            },
            inGymNow = liveVisits.count { it.checkOutAt == null },
            visitsToday = liveVisits.count { it.dateKey == todayKey }
        )
    }

    // ── labels ──

    /** The customer name, else "Room {n}", else "Table {n}", else "Guest". Blank text counts as missing. */
    fun partyLabel(customerName: String?, roomNumber: String?, tableNumber: String?): String {
        val name = customerName?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        val room = roomNumber?.trim().orEmpty()
        if (room.isNotEmpty()) return "Room $room"
        val table = tableNumber?.trim().orEmpty()
        if (table.isNotEmpty()) return "Table $table"
        return "Guest"
    }

    private val TASK_FINISHED = setOf("completed", "cancelled")
    private val LAUNDRY_FINISHED = setOf("delivered", "cancelled")
    private val MAINTENANCE_FINISHED = setOf("resolved", "closed")
}
