package com.westly.nbms.features.gym

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The five numbers at the top of the Reports page. */
data class GymReportStats(
    val total: Int,
    val active: Int,
    val expired: Int,
    val suspended: Int,
    val cancelled: Int,
    /** Active members whose end date is between now and now + 7 days. */
    val expiringSoon: Int,
    /** Sum of `packagePrice` of members registered or renewed/edited this month (each member once). */
    val monthRevenue: Double
)

/** One bar of the "Visits — Last 7 Days" chart. [label] is "Mon"…"Sun". */
data class DayVisits(val dateKey: String, val label: String, val count: Int)

data class PackageCount(val name: String, val count: Int)

data class TopMember(val name: String, val visits: Int)

/** Everything the Reports page shows, worked out at one moment. */
data class GymReportData(
    val stats: GymReportStats,
    val last7Days: List<DayVisits>,
    val packages: List<PackageCount>,
    val topMembers: List<TopMember>
)

/** The pure maths of Westly's `GymReportsPage`. Uses the effective status of [GymLogic]; nothing here touches the database. */
object GymReportsLogic {

    const val EXPIRING_WINDOW_DAYS = 7
    const val TOP_MEMBER_COUNT = 5
    const val CUSTOM_PACKAGE_LABEL = "Custom"

    private const val DAY_SECONDS = 86_400L

    /** Counts per effective status, the expiring-in-7-days count and this month's revenue. Removed members are ignored. */
    fun stats(members: List<GymMember>, now: Instant, timezone: String): GymReportStats {
        val live = members.filter { !it.isDeleted }
        val monthStart = monthStart(now, timezone)
        val windowEnd = now.plusSeconds(EXPIRING_WINDOW_DAYS * DAY_SECONDS)
        var active = 0
        var expired = 0
        var suspended = 0
        var cancelled = 0
        var expiring = 0
        var revenue = 0.0
        for (member in live) {
            when (GymLogic.effectiveStatus(member, now)) {
                MembershipStatus.ACTIVE -> {
                    active++
                    val end = member.endDate.toGymInstant()
                    if (end != null && !end.isBefore(now) && !end.isAfter(windowEnd)) expiring++
                }
                MembershipStatus.EXPIRED -> expired++
                MembershipStatus.SUSPENDED -> suspended++
                MembershipStatus.CANCELLED -> cancelled++
            }
            val created = member.createdAt.toGymInstant()
            val updated = member.updatedAt.toGymInstant()
            val thisMonth = (created != null && !created.isBefore(monthStart)) || (updated != null && !updated.isBefore(monthStart))
            if (thisMonth) revenue += member.packagePrice
        }
        return GymReportStats(live.size, active, expired, suspended, cancelled, expiring, revenue)
    }

    /** The first moment of the current month in the business time zone. */
    fun monthStart(now: Instant, timezone: String): Instant {
        val zone = GymLogic.zoneOf(timezone)
        return now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
    }

    /** Seven days, oldest first, today last; each with its visit count (matched on `dateKey`, removed visits ignored). */
    fun last7Days(visits: List<GymVisit>, now: Instant, timezone: String): List<DayVisits> {
        val today = LocalDate.parse(GymLogic.dateKey(now, timezone))
        val counts = visits.filter { !it.isDeleted }.groupingBy { it.dateKey }.eachCount()
        return (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            val key = day.toString()
            DayVisits(key, weekdayLabel(day.dayOfWeek), counts[key] ?: 0)
        }
    }

    internal fun weekdayLabel(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.US)

    /** Non-cancelled members grouped by package name ("Custom" when blank), biggest group first, then by name. */
    fun packageBreakdown(members: List<GymMember>, now: Instant): List<PackageCount> =
        members
            .filter { !it.isDeleted }
            .filter { GymLogic.effectiveStatus(it, now) != MembershipStatus.CANCELLED }
            .groupingBy { it.packageName.trim().ifEmpty { CUSTOM_PACKAGE_LABEL } }
            .eachCount()
            .map { (name, count) -> PackageCount(name, count) }
            .sortedWith(compareByDescending<PackageCount> { it.count }.thenBy { it.name.lowercase() })

    /** The [limit] members with the most visits; members with no visit are left out. */
    fun topMembers(members: List<GymMember>, limit: Int = TOP_MEMBER_COUNT): List<TopMember> =
        members
            .filter { !it.isDeleted && it.visitCount >= 1 }
            .sortedWith(compareByDescending<GymMember> { it.visitCount }.thenBy { it.name.lowercase() })
            .take(limit)
            .map { TopMember(it.name.trim().ifEmpty { "—" }, it.visitCount) }

    /** The tallest of the seven bars counts as at least 1, so an empty week does not divide by zero. */
    fun barScaleMax(days: List<DayVisits>): Int = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    fun reportOf(members: List<GymMember>, visits: List<GymVisit>, now: Instant, timezone: String): GymReportData =
        GymReportData(
            stats = stats(members, now, timezone),
            last7Days = last7Days(visits, now, timezone),
            packages = packageBreakdown(members, now),
            topMembers = topMembers(members.filter { !it.isDeleted })
        )
}
