package com.westly.nbms.features.gym

import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GymReportsLogicTest {
    // Saturday 10 Oct 2026, 12:00 UTC = 13:00 in Lagos. The Lagos month started at 2026-09-30T23:00:00Z.
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val lagos = "Africa/Lagos"
    private val day = 86_400L
    private val monthStart = Instant.parse("2026-09-30T23:00:00Z")

    private fun ts(i: Instant) = Timestamp(i.epochSecond, 0)

    private fun member(
        id: String = "m",
        name: String = "Ada",
        status: String = "active",
        endOffset: Long? = 30 * day,
        price: Double = 0.0,
        pkg: String = "Monthly Pass",
        created: Instant? = null,
        updated: Instant? = null,
        visits: Int = 0,
        deleted: Boolean = false
    ) = GymMember(
        id = id, name = name, status = status, endDate = endOffset?.let { ts(now.plusSeconds(it)) },
        packageName = pkg, packagePrice = price, createdAt = created?.let(::ts), updatedAt = updated?.let(::ts),
        visitCount = visits, isDeleted = deleted
    )

    // ── counts ──

    @Test fun countsUseTheEffectiveStatusAndLeaveOutRemovedMembers() {
        val members = listOf(
            member("a1"),
            member("a2", endOffset = 3 * day),
            member("e1", endOffset = -day),
            member("e2", status = "expired", endOffset = -day),
            member("s1", status = "suspended"),
            member("c1", status = "cancelled"),
            member("gone", deleted = true)
        )
        val s = GymReportsLogic.stats(members, now, lagos)
        assertEquals(6, s.total)
        assertEquals(2, s.active)
        assertEquals(2, s.expired)
        assertEquals(1, s.suspended)
        assertEquals(1, s.cancelled)
    }

    @Test fun aStoredActiveMemberWhoseEndDatePassedCountsAsExpiredNotActive() {
        val s = GymReportsLogic.stats(listOf(member(status = "active", endOffset = -1)), now, lagos)
        assertEquals(0, s.active)
        assertEquals(1, s.expired)
    }

    // ── expiring within 7 days ──

    @Test fun expiringWindowRunsFromNowToSevenDaysInclusive() {
        val members = listOf(
            member("now", endOffset = 0),
            member("in3", endOffset = 3 * day),
            member("in7", endOffset = 7 * day),
            member("in7plus", endOffset = 7 * day + 1),
            member("in30", endOffset = 30 * day),
            member("past", endOffset = -1),
            member("noEnd", endOffset = null)
        )
        assertEquals(3, GymReportsLogic.stats(members, now, lagos).expiringSoon)
    }

    @Test fun onlyActiveMembersCountAsExpiring() {
        val members = listOf(
            member("susp", status = "suspended", endOffset = 2 * day),
            member("canc", status = "cancelled", endOffset = 2 * day),
            member("ok", endOffset = 2 * day)
        )
        assertEquals(1, GymReportsLogic.stats(members, now, lagos).expiringSoon)
    }

    // ── month revenue ──

    @Test fun revenueCountsRegistrationsAndRenewalsThisMonthEachMemberOnce() {
        val thisMonth = now.minusSeconds(2 * day)
        val lastMonth = monthStart.minusSeconds(10 * day)
        val members = listOf(
            member("registered", price = 15_000.0, created = thisMonth, updated = lastMonth),
            member("renewed", price = 5_000.0, created = lastMonth, updated = thisMonth),
            member("both", price = 20_000.0, created = thisMonth, updated = thisMonth),
            member("old", price = 99_000.0, created = lastMonth, updated = lastMonth),
            member("noDates", price = 7_000.0),
            member("removed", price = 1_000.0, created = thisMonth, deleted = true)
        )
        assertEquals(40_000.0, GymReportsLogic.stats(members, now, lagos).monthRevenue, 0.0)
    }

    @Test fun theMonthStartsAtMidnightInTheBusinessTimeZone() {
        assertEquals(monthStart, GymReportsLogic.monthStart(now, lagos))
        val onTheLine = member("a", price = 100.0, created = monthStart)
        val justBefore = member("b", price = 200.0, created = monthStart.minusSeconds(1))
        // 30 Sep 23:30 UTC is already 1 Oct in Lagos, but still September in UTC.
        val lagosOctober = member("c", price = 400.0, created = Instant.parse("2026-09-30T23:30:00Z"))
        val s = GymReportsLogic.stats(listOf(onTheLine, justBefore, lagosOctober), now, lagos)
        assertEquals(500.0, s.monthRevenue, 0.0)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), GymReportsLogic.monthStart(now, "UTC"))
    }

    @Test fun anUnknownTimeZoneFallsBackToLagos() {
        assertEquals(monthStart, GymReportsLogic.monthStart(now, "Not/AZone"))
    }

    // ── last 7 days ──

    private fun visit(key: String, deleted: Boolean = false) = GymVisit(id = key + deleted, dateKey = key, isDeleted = deleted)

    @Test fun sevenDaysOldestFirstEndingToday() {
        val visits = listOf(
            visit("2026-10-10"), visit("2026-10-10"), visit("2026-10-10"),
            visit("2026-10-08"), visit("2026-10-04"), visit("2026-10-03"), visit("2026-10-11"),
            visit("2026-10-10", deleted = true), visit("")
        )
        val days = GymReportsLogic.last7Days(visits, now, lagos)
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), days.map { it.label })
        assertEquals(
            listOf("2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10"),
            days.map { it.dateKey }
        )
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 3), days.map { it.count })
    }

    @Test fun todayFollowsTheBusinessTimeZoneNotUtc() {
        val lateUtc = Instant.parse("2026-10-10T23:30:00Z") // already Sunday 11 Oct in Lagos
        val days = GymReportsLogic.last7Days(listOf(visit("2026-10-11"), visit("2026-10-10")), lateUtc, lagos)
        assertEquals("2026-10-11", days.last().dateKey)
        assertEquals("Sun", days.last().label)
        assertEquals("2026-10-05", days.first().dateKey)
        assertEquals(1, days.last().count)
        assertEquals("2026-10-10", GymReportsLogic.last7Days(emptyList(), lateUtc, "UTC").last().dateKey)
    }

    @Test fun barScaleIsAtLeastOneAndBarsAreNeverTooThin() {
        val empty = GymReportsLogic.last7Days(emptyList(), now, lagos)
        assertEquals(1, GymReportsLogic.barScaleMax(empty))
        assertEquals(0.dp, barHeightOf(0, 1, 120.dp))

        val busy = listOf(DayVisits("a", "Mon", 100), DayVisits("b", "Tue", 1), DayVisits("c", "Wed", 50))
        val max = GymReportsLogic.barScaleMax(busy)
        assertEquals(100, max)
        assertEquals(120.dp, barHeightOf(100, max, 120.dp))
        assertEquals(60.dp, barHeightOf(50, max, 120.dp))
        assertEquals(4.dp, barHeightOf(1, max, 120.dp)) // 1.2dp would be too thin
    }

    // ── package breakdown ──

    @Test fun packagesAreGroupedByNameWithCustomForBlankAndCancelledLeftOut() {
        val members = listOf(
            member("1", pkg = "Gold"), member("2", pkg = " Gold "), member("3", pkg = "Silver"),
            member("4", pkg = ""), member("5", pkg = "   "),
            member("6", pkg = "Silver", status = "cancelled"),
            member("7", pkg = "Gold", endOffset = -day), // expired still counts
            member("8", pkg = "Gold", deleted = true)
        )
        val rows = GymReportsLogic.packageBreakdown(members, now)
        assertEquals(listOf(PackageCount("Gold", 3), PackageCount("Custom", 2), PackageCount("Silver", 1)), rows)
    }

    @Test fun packageTiesAreOrderedByName() {
        val rows = GymReportsLogic.packageBreakdown(listOf(member("1", pkg = "Zed"), member("2", pkg = "Alpha")), now)
        assertEquals(listOf("Alpha", "Zed"), rows.map { it.name })
    }

    @Test fun noMembersMeansNoPackageRows() {
        assertTrue(GymReportsLogic.packageBreakdown(emptyList(), now).isEmpty())
    }

    // ── top members ──

    @Test fun topFiveByVisitsWithAtLeastOneVisit() {
        val members = listOf(
            member("1", "Ada", visits = 9), member("2", "Bola", visits = 12), member("3", "Chi", visits = 0),
            member("4", "Dayo", visits = 3), member("5", "Efe", visits = 9), member("6", "Femi", visits = 1),
            member("7", "Gina", visits = 4), member("8", "Hope", visits = 20, deleted = true)
        )
        val top = GymReportsLogic.topMembers(members)
        assertEquals(listOf("Bola", "Ada", "Efe", "Gina", "Dayo"), top.map { it.name })
        assertEquals(listOf(12, 9, 9, 4, 3), top.map { it.visits })
    }

    @Test fun nobodyWithVisitsMeansNoRows() {
        assertTrue(GymReportsLogic.topMembers(listOf(member("1", visits = 0))).isEmpty())
    }

    @Test fun reportOfPutsEverythingTogether() {
        val report = GymReportsLogic.reportOf(listOf(member("1", visits = 2, price = 10.0, created = now)), listOf(visit("2026-10-10")), now, lagos)
        assertEquals(1, report.stats.total)
        assertEquals(10.0, report.stats.monthRevenue, 0.0)
        assertEquals(7, report.last7Days.size)
        assertEquals(1, report.packages.size)
        assertEquals(1, report.topMembers.size)
    }
}
