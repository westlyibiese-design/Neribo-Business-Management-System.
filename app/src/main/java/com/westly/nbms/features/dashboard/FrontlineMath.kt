package com.westly.nbms.features.dashboard

import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import com.westly.nbms.features.gym.GymLogic
import com.westly.nbms.features.gym.MembershipStatus
import com.westly.nbms.features.inventory.InventoryItem
import java.time.Instant
import java.time.ZoneId

data class ReceptionStats(val expectedIn: List<DashBooking>, val expectedOut: List<DashBooking>, val inHouse: Int)

/** pendingCount = status "pending", all time. */
data class OrderStats(val todayCount: Int, val pendingCount: Int, val todayAmount: Double)

data class LaundryStats(val active: Int, val ready: Int, val deliveredToday: Int)

data class HkStats(val queue: Int, val highPriority: Int, val myRooms: Int)

data class ExpiringMember(val name: String, val daysLeft: Int)

/** expiring = active members with 0 <= daysUntilExpiry <= 7, soonest first. */
data class GymDeskStats(val active: Int, val inGymNow: Int, val visitsToday: Int, val expiring: List<ExpiringMember>)

/**
 * Every number the front-line dashboards show (Phase 34A1), as pure functions. Nothing reads a clock or a database:
 * `now` and the business [ZoneId] are always passed in, and "today" is the calendar day in that zone (never UTC).
 */
object FrontlineMath {

    private val LAUNDRY_ACTIVE = setOf("received", "washing", "drying", "ironing", "ready")
    private val HK_QUEUE = setOf("pending", "in_progress")
    private val HK_HIGH = setOf("urgent", "high")
    private const val EXPIRY_WINDOW_DAYS = 7

    /** True when [i] falls on the same calendar day as [now] in [zone]. */
    fun isTodayIn(i: Instant?, now: Instant, zone: ZoneId): Boolean =
        i != null && i.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

    /**
     * expectedIn: status "confirmed" and check-in is today; expectedOut: status "checked_in" and check-out is today;
     * inHouse: status "checked_in". Deleted bookings are ignored.
     */
    fun receptionStats(b: List<DashBooking>, now: Instant, zone: ZoneId): ReceptionStats {
        val live = b.filter { !it.isDeleted }
        return ReceptionStats(
            expectedIn = live.filter { it.status == "confirmed" && isTodayIn(it.checkIn, now, zone) },
            expectedOut = live.filter { it.status == "checked_in" && isTodayIn(it.checkOut, now, zone) },
            inHouse = live.count { it.status == "checked_in" }
        )
    }

    /**
     * ((todayCount, todayAmount), (allCount, allAmount)). The sum of `total` regardless of approval: these are the person's
     * own activity, not approved revenue.
     */
    fun myTotals(rows: List<AmountRow>, now: Instant, zone: ZoneId): Pair<Pair<Int, Double>, Pair<Int, Double>> {
        val today = rows.filter { isTodayIn(it.createdAt, now, zone) }
        return (today.size to today.sumOf { it.total }) to (rows.size to rows.sumOf { it.total })
    }

    /** todayCount and todayAmount use the zone's day; pendingCount is every order with status "pending", whatever its date. */
    fun orderStats(o: List<OrderRow>, now: Instant, zone: ZoneId): OrderStats {
        val today = o.filter { isTodayIn(it.createdAt, now, zone) }
        return OrderStats(
            todayCount = today.size,
            pendingCount = o.count { it.status == "pending" },
            todayAmount = today.sumOf { it.total }
        )
    }

    /** active = received, washing, drying, ironing or ready; ready = "ready"; deliveredToday = "delivered" with deliveredAt today. */
    fun laundryStats(l: List<FrontlineLaundryRow>, now: Instant, zone: ZoneId): LaundryStats = LaundryStats(
        active = l.count { it.status in LAUNDRY_ACTIVE },
        ready = l.count { it.status == "ready" },
        deliveredToday = l.count { it.status == "delivered" && isTodayIn(it.deliveredAt, now, zone) }
    )

    /** queue = tasks pending or in progress; highPriority = those with priority urgent or high; myRooms = active assignments. */
    fun housekeepingStats(queue: List<HkTaskRow>, rooms: Int): HkStats {
        val q = queue.filter { it.status in HK_QUEUE }
        return HkStats(queue = q.size, highPriority = q.count { it.priority in HK_HIGH }, myRooms = rooms)
    }

    /**
     * active = Phase 29's effective status (an end date in the past is expired even if stored "active"); inGymNow = visits
     * with no check-out; visitsToday = visits whose dateKey is [todayKey]; expiring = active members with 0..7 days left
     * (inclusive), soonest first, then by name.
     */
    fun gymDeskStats(
        members: List<DashGymMember>,
        visits: List<DashGymVisit>,
        todayKey: String,
        now: Instant
    ): GymDeskStats {
        val liveVisits = visits.filter { !it.isDeleted }
        val active = members.filter {
            !it.isDeleted && GymLogic.effectiveStatus(it.status, it.endDate, now) == MembershipStatus.ACTIVE
        }
        val expiring = active.mapNotNull { m ->
            val days = GymLogic.daysUntilExpiry(m.endDate, now)
            if (days != null && days in 0..EXPIRY_WINDOW_DAYS) ExpiringMember(m.name, days) else null
        }.sortedWith(compareBy<ExpiringMember> { it.daysLeft }.thenBy { it.name.lowercase() })
        return GymDeskStats(
            active = active.size,
            inGymNow = liveVisits.count { it.checkOutAt == null },
            visitsToday = liveVisits.count { it.dateKey == todayKey },
            expiring = expiring
        )
    }

    /** Phase 33's low-stock list, first [n] items. */
    fun lowStockTop(items: List<InventoryItem>, n: Int = 5): List<InventoryItem> =
        DashboardMath.lowStock(items).take(n.coerceAtLeast(0))
}
