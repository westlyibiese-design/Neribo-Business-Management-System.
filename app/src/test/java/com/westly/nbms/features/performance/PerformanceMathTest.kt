package com.westly.nbms.features.performance

import com.westly.nbms.core.data.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PerformanceMathTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    /** Monday 12 October 2026, 10:00 in Lagos. */
    private val now: Instant = at("2026-10-12T10:00:00")

    private fun at(local: String, zone: ZoneId = lagos): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun user(id: String, name: String = "User $id", role: String? = "receptionist") = PerfUser(id = id, name = name, role = role)

    private fun stamp(staff: String, local: String) = PerfStamp(staffId = staff, at = at(local))

    private fun sale(staff: String, local: String, total: Double, status: String?) =
        PerfSale(staffId = staff, at = at(local), total = total, approvalStatus = status)

    private fun compute(
        users: List<PerfUser>,
        checkins: List<PerfStamp> = emptyList(),
        checkouts: List<PerfStamp> = emptyList(),
        sales: List<PerfSale> = emptyList(),
        orders: List<PerfSale> = emptyList(),
        attendance: List<PerfAttendance> = emptyList(),
        zone: ZoneId = lagos,
        asOf: Instant = now
    ) = PerformanceMath.compute(users, checkins, checkouts, sales, orders, attendance, asOf, zone)

    // ---- month bounds in the business zone

    @Test fun monthBoundsFollowTheBusinessZone() {
        // 23:30Z on 31 October is already 00:30 on 1 November in Lagos
        val b = PerformanceMath.monthBounds(Instant.parse("2026-10-31T23:30:00Z"), lagos)
        assertEquals(LocalDate.of(2026, 11, 1), b.firstDay)
        assertEquals(LocalDate.of(2026, 11, 30), b.lastDay)
        assertEquals(Instant.parse("2026-10-31T23:00:00Z"), b.start)
        assertEquals(Instant.parse("2026-11-30T23:00:00Z"), b.endExclusive)
    }

    @Test fun theSameMomentIsStillOctoberInUtc() {
        val b = PerformanceMath.monthBounds(Instant.parse("2026-10-31T23:30:00Z"), ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 10, 1), b.firstDay)
        assertEquals(LocalDate.of(2026, 10, 31), b.lastDay)
    }

    @Test fun monthBoundaryRecordsAreAssignedInTheBusinessZone() {
        val users = listOf(user("a"))
        val checkins = listOf(
            stamp("a", "2026-10-01T00:30:00"),   // first minutes of October in Lagos: counts
            stamp("a", "2026-09-30T23:30:00"),   // September: no
            stamp("a", "2026-10-31T23:30:00"),   // last evening of October: counts
            stamp("a", "2026-11-01T00:30:00")    // November: no
        )
        assertEquals(2, compute(users, checkins = checkins).single().checkins)
    }

    @Test fun monthLabelsUseTheBusinessZone() {
        assertEquals("October 2026", PerformanceMath.monthLabel(now, lagos))
        assertEquals("October", PerformanceMath.monthName(now, lagos))
        val lateOnTheLast = Instant.parse("2026-10-31T23:30:00Z")
        assertEquals("November 2026", PerformanceMath.monthLabel(lateOnTheLast, lagos))
        assertEquals("October 2026", PerformanceMath.monthLabel(lateOnTheLast, ZoneId.of("UTC")))
    }

    // ---- counts and approved-only revenue

    @Test fun activityCountsIncludePendingRows() {
        val p = compute(
            users = listOf(user("a")),
            checkins = listOf(stamp("a", "2026-10-02T09:00:00"), stamp("a", "2026-10-03T09:00:00")),
            checkouts = listOf(stamp("a", "2026-10-04T09:00:00")),
            sales = listOf(
                sale("a", "2026-10-05T09:00:00", 1_000.0, "pending"),
                sale("a", "2026-10-05T10:00:00", 2_000.0, "approved"),
                sale("a", "2026-10-05T11:00:00", 4_000.0, "rejected")
            ),
            orders = listOf(sale("a", "2026-10-06T09:00:00", 500.0, null))
        ).single()
        assertEquals(2, p.checkins)
        assertEquals(1, p.checkouts)
        assertEquals(3, p.sales)
        assertEquals(1, p.orders)
        assertEquals(7, p.totalActivities)
        assertEquals(3, p.checkInsAndOuts)
        assertEquals(4, p.salesAndOrders)
    }

    @Test fun revenueCountsApprovedRowsOnly() {
        val p = compute(
            users = listOf(user("a")),
            sales = listOf(
                sale("a", "2026-10-05T09:00:00", 1_000.0, "pending"),
                sale("a", "2026-10-05T10:00:00", 2_000.0, "approved"),
                sale("a", "2026-10-05T11:00:00", 4_000.0, "rejected"),
                sale("a", "2026-10-05T12:00:00", 8_000.0, " Approved ")
            ),
            orders = listOf(
                sale("a", "2026-10-06T09:00:00", 500.0, "approved"),
                sale("a", "2026-10-06T10:00:00", 700.0, "pending"),
                sale("a", "2026-10-06T11:00:00", 900.0, null)
            )
        ).single()
        assertEquals(10_000.0, p.salesRevenue, 0.001)
        assertEquals(500.0, p.ordersRevenue, 0.001)
        assertEquals(10_500.0, p.revenue, 0.001)
    }

    @Test fun approvedRevenueOutsideTheMonthIsIgnored() {
        val p = compute(
            users = listOf(user("a")),
            sales = listOf(
                sale("a", "2026-09-30T12:00:00", 9_999.0, "approved"),
                sale("a", "2026-10-10T12:00:00", 300.0, "approved")
            )
        ).single()
        assertEquals(1, p.sales)
        assertEquals(300.0, p.salesRevenue, 0.001)
    }

    // ---- attendance

    @Test fun presentAndLateAreDaysWorked() {
        val att = listOf(
            PerfAttendance("a", "2026-10-01", null, "present"),
            PerfAttendance("a", "2026-10-02", null, "late"),
            PerfAttendance("a", "2026-10-03", null, "absent"),
            PerfAttendance("a", "2026-10-04", null, "on_leave"),
            PerfAttendance("a", "2026-09-30", null, "present"),      // previous month
            PerfAttendance("a", "2026-11-01", null, "present"),      // next month
            PerfAttendance("a", null, at("2026-10-07T00:00:00"), "present"),   // no dateKey: the date field is used
            PerfAttendance("b", "2026-10-01", null, "present")       // someone else
        )
        assertEquals(3, compute(listOf(user("a")), attendance = att).single().presentDays)
    }

    @Test fun attendanceWithoutAnyDateIsIgnored() {
        val att = listOf(PerfAttendance("a", null, null, "present"), PerfAttendance("a", "not-a-date", null, "present"))
        assertEquals(0, compute(listOf(user("a")), attendance = att).single().presentDays)
    }

    // ---- sorting, ties, slicing

    @Test fun sortedByTotalActivitiesDescendingThenByName() {
        val users = listOf(user("1", "bola"), user("2", "Ade"), user("3", "Chi"), user("4", "Zed"))
        val checkins = listOf(
            stamp("4", "2026-10-02T09:00:00"), stamp("4", "2026-10-03T09:00:00"), stamp("4", "2026-10-04T09:00:00"),
            stamp("1", "2026-10-02T09:00:00"),
            stamp("2", "2026-10-02T09:00:00"),
            stamp("3", "2026-10-02T09:00:00")
        )
        val names = compute(users, checkins = checkins).map { it.name }
        assertEquals(listOf("Zed", "Ade", "bola", "Chi"), names)   // ties (1 activity each) by name, ignoring case
    }

    @Test fun everyActiveUserGetsARowEvenWithNoActivity() {
        val rows = compute(listOf(user("a", "Ada"), user("b", "Bayo")))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.totalActivities == 0 && it.revenue == 0.0 && it.presentDays == 0 })
    }

    @Test fun recordsOfPeopleWhoAreNotListedAreIgnored() {
        val rows = compute(
            users = listOf(user("a")),
            checkins = listOf(stamp("ghost", "2026-10-02T09:00:00"), stamp("a", "2026-10-02T09:00:00"))
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().checkins)
    }

    @Test fun aPersonListedTwiceIsOneRow() {
        assertEquals(1, compute(listOf(user("a"), user("a"))).size)
    }

    @Test fun chartShowsTheTopEight() {
        val users = (1..10).map { user("u$it", "Person $it") }
        // person k has k check-ins this month
        val checkins = users.flatMapIndexed { i, u -> (1..(i + 1)).map { stamp(u.id, "2026-10-02T09:00:00") } }
        val rows = compute(users, checkins = checkins)
        val top = PerformanceMath.top(rows)
        assertEquals(10, rows.size)
        assertEquals(8, top.size)
        assertEquals("Person 10", top.first().name)
        assertEquals("Person 3", top.last().name)
        assertEquals(rows.take(8), top)
    }

    @Test fun topOfAShortListIsTheWholeList() {
        val rows = compute(listOf(user("a"), user("b")))
        assertEquals(2, PerformanceMath.top(rows).size)
        assertEquals(0, PerformanceMath.top(rows, 0).size)
    }

    @Test fun firstNameIsTheFirstWord() {
        assertEquals("Ada", PerformanceMath.firstName("Ada Obi"))
        assertEquals("Ada", PerformanceMath.firstName("  Ada  "))
        assertEquals("Staff", PerformanceMath.firstName("   "))
    }

    // ---- axis

    @Test fun axisCoversTheLargestBarWithWholeNumbers() {
        for (max in 0..500) {
            val s = PerformanceMath.axisScale(max)
            assertTrue("top ${s.top} must cover $max", s.top >= max)
            assertTrue(s.step >= 1)
            assertEquals(0, s.top % s.step)
            assertEquals(0, s.ticks.first())
            assertEquals(s.top, s.ticks.last())
        }
    }

    @Test fun axisUsesRoundSteps() {
        assertEquals(listOf(0, 1, 2, 3), PerformanceMath.axisScale(3).ticks)
        assertEquals(listOf(0, 5, 10), PerformanceMath.axisScale(10).ticks)
        assertEquals(10, PerformanceMath.axisScale(37).step)
        assertEquals(40, PerformanceMath.axisScale(37).top)
        assertEquals(listOf(0, 50, 100), PerformanceMath.axisScale(100).ticks)
    }

    // ---- page state

    private val ok = Resource.Success(emptyList<PerfUser>())

    @Test fun stateIsLoadingUntilEverySixListsArrive() {
        val s = performanceStateOf(
            ok, Resource.Loading, Resource.Success(emptyList()), Resource.Success(emptyList()),
            Resource.Success(emptyList()), Resource.Success(emptyList()), now, lagos
        )
        assertTrue(s.loading)
        assertFalse(s.failed)
    }

    @Test fun anErrorInAnyListIsAnError() {
        val s = performanceStateOf(
            ok, Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Success(emptyList()),
            Resource.Success(emptyList()), Resource.Error("x"), now, lagos
        )
        assertTrue(s.failed)
        assertFalse(s.loading)
    }

    @Test fun errorWinsOverLoading() {
        val s = performanceStateOf(
            Resource.Loading, Resource.Error("x"), Resource.Loading, Resource.Loading, Resource.Loading, Resource.Loading, now, lagos
        )
        assertTrue(s.failed)
        assertFalse(s.loading)
    }

    @Test fun readyStateCarriesTheLeaderboardAndTheChartSlice() {
        val users = Resource.Success((1..10).map { user("u$it", "Person $it") })
        val checkins = Resource.Success(listOf(stamp("u3", "2026-10-02T09:00:00")))
        val s = performanceStateOf(
            users, checkins, Resource.Success(emptyList()), Resource.Success(emptyList()),
            Resource.Success(emptyList()), Resource.Success(emptyList()), now, lagos
        )
        assertFalse(s.loading)
        assertFalse(s.failed)
        assertEquals(10, s.rows.size)
        assertEquals(8, s.top.size)
        assertEquals("Person 3", s.rows.first().name)
        assertFalse(s.chartIsEmpty)
    }

    @Test fun chartIsEmptyWhenNobodyDidAnything() {
        val s = performanceStateOf(
            Resource.Success(listOf(user("a"))), Resource.Success(emptyList()), Resource.Success(emptyList()),
            Resource.Success(emptyList()), Resource.Success(emptyList()), Resource.Success(emptyList()), now, lagos
        )
        assertTrue(s.chartIsEmpty)
        assertEquals(1, s.rows.size)
    }
}
