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
import com.westly.nbms.features.finance.ApprovalStatus
import com.westly.nbms.features.finance.RevenueCategory
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.SourceCollection
import com.westly.nbms.features.inventory.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DashboardMathTest {

    private val lagos = ZoneId.of("Africa/Lagos")          // UTC+1, no daylight saving
    private val auckland = ZoneId.of("Pacific/Auckland")   // UTC+13 in October

    private fun at(zone: ZoneId, local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun txn(
        id: String,
        amount: Double,
        date: Instant?,
        status: ApprovalStatus = ApprovalStatus.APPROVED
    ) = RevenueTransaction(
        id = id, source = SourceCollection.PAYMENTS, category = RevenueCategory.ROOM, typeLabel = "Room", guestName = "G",
        amount = amount, paymentMethod = "cash", date = date, recordedBy = null, recordedByName = "", approvalStatus = status,
        approvedBy = null, approvedByName = null, approvedAt = null, rejectedReason = null
    )

    // ── rooms ──

    @Test fun roomCountsWithNoRoomsIsAllZero() {
        val c = DashboardMath.roomCounts(emptyList())
        assertEquals(RoomCounts(0, 0, 0, 0, 0, 0, 0), c)
        assertEquals(0, DashboardMath.occupancyPercent(c.occupied, c.total))
    }

    @Test fun roomCountsCountsEveryStatusAndTreatsBlankAsAvailable() {
        val rooms = listOf(
            DashRoom("1", "101", "available"), DashRoom("2", "102", null), DashRoom("3", "103", "  "),
            DashRoom("4", "104", "occupied"), DashRoom("5", "105", "occupied"),
            DashRoom("6", "106", "cleaning"), DashRoom("7", "107", "maintenance"),
            DashRoom("8", "108", "reserved"), DashRoom("9", "109", "out_of_service"),
            DashRoom("10", "110", "Out Of Service")
        )
        val c = DashboardMath.roomCounts(rooms)
        assertEquals(RoomCounts(total = 10, available = 3, occupied = 2, cleaning = 1, maintenance = 1, reserved = 1, outOfService = 2), c)
    }

    @Test fun deletedRoomsAreIgnored() {
        val c = DashboardMath.roomCounts(listOf(DashRoom("1", "101", "occupied", isDeleted = true), DashRoom("2", "102", "occupied")))
        assertEquals(1, c.total)
        assertEquals(1, c.occupied)
    }

    @Test fun occupancyPercentRoundsAndGuardsAgainstZeroTotal() {
        assertEquals(0, DashboardMath.occupancyPercent(3, 0))
        assertEquals(33, DashboardMath.occupancyPercent(1, 3))
        assertEquals(67, DashboardMath.occupancyPercent(2, 3))
        assertEquals(100, DashboardMath.occupancyPercent(5, 5))
        assertEquals(0, DashboardMath.occupancyPercent(0, 5))
        assertEquals(13, DashboardMath.occupancyPercent(1, 8)) // 12.5 rounds up
    }

    // ── time ──

    @Test fun eventTimePrefersTheActualTime() {
        val actual = Instant.parse("2026-10-12T10:00:00Z")
        val scheduled = Instant.parse("2026-10-11T10:00:00Z")
        assertEquals(actual, DashboardMath.eventTime(actual, scheduled))
        assertEquals(scheduled, DashboardMath.eventTime(null, scheduled))
        assertNull(DashboardMath.eventTime(null, null))
    }

    @Test fun isTodayIsFalseForNull() {
        assertFalse(DashboardMath.isToday(null, Instant.parse("2026-10-12T10:00:00Z"), lagos))
    }

    @Test fun isTodayUsesTheBusinessZoneInLagos() {
        val now = at(lagos, "2026-10-12T23:30:00")            // 22:30Z
        assertTrue(DashboardMath.isToday(at(lagos, "2026-10-12T00:05:00"), now, lagos))
        assertTrue(DashboardMath.isToday(now, now, lagos))
        // 00:15 local on the 13th is 23:15Z on the 12th: "today" in UTC, but tomorrow for the business
        val justAfterMidnight = at(lagos, "2026-10-13T00:15:00")
        assertEquals("2026-10-12T23:15:00Z", justAfterMidnight.toString())
        assertFalse(DashboardMath.isToday(justAfterMidnight, now, lagos))
    }

    @Test fun isTodayUsesTheBusinessZoneInAuckland() {
        val now = at(auckland, "2026-10-12T23:30:00")         // 10:30Z on the 12th
        // 00:10 local on the 12th is 11:10Z on the 11th: "yesterday" in UTC, but today for the business
        val earlyToday = at(auckland, "2026-10-12T00:10:00")
        assertEquals("2026-10-11T11:10:00Z", earlyToday.toString())
        assertTrue(DashboardMath.isToday(earlyToday, now, auckland))
        assertTrue(DashboardMath.isToday(at(auckland, "2026-10-12T23:59:00"), now, auckland))
        assertFalse(DashboardMath.isToday(at(auckland, "2026-10-11T23:59:00"), now, auckland))
        assertFalse(DashboardMath.isToday(at(auckland, "2026-10-13T00:00:00"), now, auckland))
    }

    // ── bookings ──

    @Test fun checkInsFallBackToTheScheduledTimeWhenCheckInAtIsMissing() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val scheduledToday = DashBooking(id = "a", checkIn = at(lagos, "2026-10-12T14:00:00"))
        val scheduledOtherDay = DashBooking(id = "b", checkIn = at(lagos, "2026-10-13T14:00:00"))
        val noTimes = DashBooking(id = "c")
        val result = DashboardMath.todayCheckIns(listOf(scheduledToday, scheduledOtherDay, noTimes), now, lagos)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test fun checkInAtWinsOverTheScheduledDate() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val arrivedToday = DashBooking(id = "a", checkIn = at(lagos, "2026-10-11T14:00:00"), checkInAt = at(lagos, "2026-10-12T09:00:00"))
        val arrivedYesterday = DashBooking(id = "b", checkIn = at(lagos, "2026-10-12T14:00:00"), checkInAt = at(lagos, "2026-10-11T22:00:00"))
        assertEquals(listOf("a"), DashboardMath.todayCheckIns(listOf(arrivedToday, arrivedYesterday), now, lagos).map { it.id })
    }

    @Test fun checkOutsUseCheckOutAtThenCheckOutAndIgnoreDeletedRows() {
        val now = at(auckland, "2026-10-12T23:30:00")
        val scheduled = DashBooking(id = "a", checkOut = at(auckland, "2026-10-12T00:10:00"))
        val actual = DashBooking(id = "b", checkOut = at(auckland, "2026-10-14T11:00:00"), checkOutAt = at(auckland, "2026-10-12T23:00:00"))
        val deleted = DashBooking(id = "c", checkOut = at(auckland, "2026-10-12T11:00:00"), isDeleted = true)
        val other = DashBooking(id = "d", checkOut = at(auckland, "2026-10-13T00:30:00"))
        assertEquals(listOf("a", "b"), DashboardMath.todayCheckOuts(listOf(scheduled, actual, deleted, other), now, auckland).map { it.id })
    }

    @Test fun pendingBookingsAndReservationsFollowStatusAndSource() {
        val list = listOf(
            DashBooking(id = "web", status = "pending", source = "website"),
            DashBooking(id = "walk", status = "pending", source = "walk_in"),
            DashBooking(id = "nosrc", status = "pending", source = null),
            DashBooking(id = "ok", status = "confirmed", source = "website"),
            DashBooking(id = "gone", status = "pending", source = "website", isDeleted = true)
        )
        assertEquals(listOf("web", "walk", "nosrc"), DashboardMath.pendingBookings(list).map { it.id })
        assertEquals(listOf("web", "nosrc"), DashboardMath.pendingReservations(list).map { it.id })
    }

    @Test fun recentBookingsAreNewestFirstLimitedAndSkipDeleted() {
        val list = listOf(
            DashBooking(id = "old", createdAt = Instant.parse("2026-10-01T00:00:00Z")),
            DashBooking(id = "none", createdAt = null),
            DashBooking(id = "new", createdAt = Instant.parse("2026-10-10T00:00:00Z")),
            DashBooking(id = "gone", createdAt = Instant.parse("2026-10-11T00:00:00Z"), isDeleted = true),
            DashBooking(id = "mid", createdAt = Instant.parse("2026-10-05T00:00:00Z"))
        )
        assertEquals(listOf("new", "mid", "old", "none"), DashboardMath.recentBookings(list, 10).map { it.id })
        assertEquals(listOf("new", "mid"), DashboardMath.recentBookings(list, 2).map { it.id })
        assertTrue(DashboardMath.recentBookings(list, 0).isEmpty())
    }

    // ── money ──

    @Test fun monthRevenueCountsApprovedTransactionsOfTheMonthOnly() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val list = listOf(
            txn("first", 1_000.0, at(lagos, "2026-10-01T00:00:00")),
            txn("last", 500.0, at(lagos, "2026-10-31T23:59:59")),
            txn("september", 9_999.0, at(lagos, "2026-09-30T23:59:59")),
            txn("november", 9_999.0, at(lagos, "2026-11-01T00:00:00")),
            txn("pending", 7_000.0, at(lagos, "2026-10-12T10:00:00"), ApprovalStatus.PENDING),
            txn("rejected", 7_000.0, at(lagos, "2026-10-12T10:00:00"), ApprovalStatus.REJECTED),
            txn("nodate", 7_000.0, null)
        )
        assertEquals(1_500.0, DashboardMath.monthRevenue(list, now, lagos), 0.001)
    }

    @Test fun monthRevenueUsesTheBusinessZoneForMonthEdges() {
        val now = at(lagos, "2026-10-12T12:00:00")
        // 23:30Z on 30 September is 00:30 on 1 October in Lagos: October revenue
        val list = listOf(txn("edge", 250.0, Instant.parse("2026-09-30T23:30:00Z")))
        assertEquals(250.0, DashboardMath.monthRevenue(list, now, lagos), 0.001)
        assertEquals(0.0, DashboardMath.monthRevenue(list, Instant.parse("2026-09-15T12:00:00Z"), lagos), 0.001)
    }

    @Test fun monthExpensesIgnoreDeletedRowsAndOtherMonths() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val list = listOf(
            DashExpense("a", 100.0, at(lagos, "2026-10-02T10:00:00")),
            DashExpense("b", 50.5, at(lagos, "2026-10-31T23:59:00")),
            DashExpense("c", 900.0, at(lagos, "2026-10-05T10:00:00"), isDeleted = true),
            DashExpense("d", 900.0, at(lagos, "2026-09-30T23:59:00")),
            DashExpense("e", 900.0, null)
        )
        assertEquals(150.5, DashboardMath.monthExpenses(list, now, lagos), 0.001)
        assertEquals(0.0, DashboardMath.monthExpenses(emptyList(), now, lagos), 0.001)
    }

    @Test fun marginIsZeroWithoutRevenueAndRoundsOtherwise() {
        assertEquals(0, DashboardMath.margin(0.0, 500.0))
        assertEquals(0, DashboardMath.margin(0.0, 0.0))
        assertEquals(40, DashboardMath.margin(1_000.0, 400.0))
        assertEquals(33, DashboardMath.margin(300.0, 100.0))
        assertEquals(-50, DashboardMath.margin(1_000.0, -500.0))
        assertEquals(100, DashboardMath.margin(750.0, 750.0))
    }

    @Test fun last7DaysIsOldestFirstWithZeroForGapDays() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val list = listOf(
            txn("today1", 100.0, at(lagos, "2026-10-12T08:00:00")),
            txn("today2", 50.0, at(lagos, "2026-10-12T09:00:00")),
            txn("sixAgo", 70.0, at(lagos, "2026-10-06T23:59:00")),
            txn("tooOld", 999.0, at(lagos, "2026-10-05T23:59:00")),
            txn("pending", 999.0, at(lagos, "2026-10-10T09:00:00"), ApprovalStatus.PENDING)
        )
        val points = DashboardMath.last7Days(list, now, lagos)
        assertEquals(listOf("Oct 6", "Oct 7", "Oct 8", "Oct 9", "Oct 10", "Oct 11", "Oct 12"), points.map { it.label })
        assertEquals(listOf(70.0, 0.0, 0.0, 0.0, 0.0, 0.0, 150.0), points.map { it.value })
    }

    @Test fun last7DaysGroupsByTheLocalDateNotByUtc() {
        val now = at(lagos, "2026-10-12T12:00:00")
        // 23:30Z on the 11th is 00:30 on the 12th in Lagos
        val points = DashboardMath.last7Days(listOf(txn("late", 40.0, Instant.parse("2026-10-11T23:30:00Z"))), now, lagos)
        assertEquals(0.0, points[5].value, 0.0)
        assertEquals(40.0, points[6].value, 0.0)
    }

    @Test fun last7DaysCrossesAMonthBoundary() {
        val now = at(lagos, "2026-11-03T12:00:00")
        val labels = DashboardMath.last7Days(emptyList(), now, lagos).map { it.label }
        assertEquals(listOf("Oct 28", "Oct 29", "Oct 30", "Oct 31", "Nov 1", "Nov 2", "Nov 3"), labels)
    }

    @Test fun last6MonthsCrossesAYearBoundary() {
        val now = at(lagos, "2026-02-14T12:00:00")
        val revenue = listOf(
            txn("feb", 300.0, at(lagos, "2026-02-01T00:30:00")),
            txn("jan", 200.0, at(lagos, "2026-01-31T23:00:00")),
            txn("dec", 100.0, at(lagos, "2025-12-15T10:00:00")),
            txn("sep", 10.0, at(lagos, "2025-09-30T10:00:00")),
            txn("tooOld", 999.0, at(lagos, "2025-08-31T10:00:00")),
            txn("pending", 999.0, at(lagos, "2026-02-02T10:00:00"), ApprovalStatus.PENDING)
        )
        val expenses = listOf(
            DashExpense("1", 60.0, at(lagos, "2026-02-03T10:00:00")),
            DashExpense("2", 40.0, at(lagos, "2025-12-20T10:00:00")),
            DashExpense("3", 999.0, at(lagos, "2025-12-21T10:00:00"), isDeleted = true)
        )
        val months = DashboardMath.last6Months(revenue, expenses, now, lagos)
        assertEquals(listOf("Sep", "Oct", "Nov", "Dec", "Jan", "Feb"), months.map { it.label })
        assertEquals(listOf(10.0, 0.0, 0.0, 100.0, 200.0, 300.0), months.map { it.revenue })
        assertEquals(listOf(0.0, 0.0, 0.0, 40.0, 0.0, 60.0), months.map { it.expenses })
    }

    // ── stock, staff, tasks ──

    @Test fun lowStockIsAtOrBelowTheMinimumAndSkipsDeletedItems() {
        val items = listOf(
            InventoryItem(id = "low", name = "Soap", quantity = 2, minStock = 5),
            InventoryItem(id = "equal", name = "Towels", quantity = 5, minStock = 5),
            InventoryItem(id = "fine", name = "Tea", quantity = 6, minStock = 5),
            InventoryItem(id = "gone", name = "Old", quantity = 0, minStock = 5, isDeleted = true)
        )
        assertEquals(listOf("low", "equal"), DashboardMath.lowStock(items).map { it.id })
    }

    @Test fun staffOnDutyIsPresentTodayOnly() {
        val list = listOf(
            DashAttendance("1", "s1", "A", "2026-10-12", "present"),
            DashAttendance("2", "s2", "B", "2026-10-12", "absent"),
            DashAttendance("3", "s3", "C", "2026-10-11", "present"),
            DashAttendance("4", "s4", "D", null, "present"),
            DashAttendance("5", "s5", "E", "2026-10-12", "present", isDeleted = true)
        )
        assertEquals(listOf("1"), DashboardMath.staffOnDuty(list, "2026-10-12").map { it.id })
    }

    @Test fun activeAndOverdueTasks() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val past = Instant.parse("2026-10-12T08:00:00Z")
        val future = Instant.parse("2026-10-12T18:00:00Z")
        val tasks = listOf(
            DashTask("pending", "pending", past),
            DashTask("progress", "in_progress", future),
            DashTask("accepted", "accepted", null),
            DashTask("done", "completed", past),
            DashTask("cancelled", "cancelled", past),
            DashTask("deleted", "pending", past, isDeleted = true)
        )
        assertEquals(listOf("pending", "progress", "accepted"), DashboardMath.activeTasks(tasks).map { it.id })
        assertEquals(listOf("pending"), DashboardMath.overdueTasks(tasks, now).map { it.id })
    }

    // ── operations ──

    @Test fun activeLaundryExcludesDeliveredCancelledAndDeleted() {
        val list = listOf(
            DashLaundry("a", "received"), DashLaundry("b", "washing"), DashLaundry("c", "ready"), DashLaundry("d", null),
            DashLaundry("e", "delivered"), DashLaundry("f", "cancelled"), DashLaundry("g", "received", isDeleted = true)
        )
        assertEquals(listOf("a", "b", "c", "d"), DashboardMath.activeLaundry(list).map { it.id })
    }

    @Test fun openMaintenanceExcludesResolvedClosedAndDeleted() {
        val list = listOf(
            DashMaintenance("open", "open"), DashMaintenance("none", null), DashMaintenance("progress", "in_progress"),
            DashMaintenance("resolved", "resolved"), DashMaintenance("closed", "closed"),
            DashMaintenance("gone", "open", isDeleted = true)
        )
        assertEquals(listOf("open", "none", "progress"), DashboardMath.openMaintenance(list).map { it.id })
    }

    @Test fun pendingAndUnassignedFoodOrders() {
        val list = listOf(
            DashOrder(id = "assigned", status = "pending", waiterId = "w1"),
            DashOrder(id = "null", status = "pending", waiterId = null),
            DashOrder(id = "blank", status = "pending", waiterId = "  "),
            DashOrder(id = "word", status = "pending", waiterId = "unassigned"),
            DashOrder(id = "preparing", status = "preparing", waiterId = null),
            DashOrder(id = "gone", status = "pending", waiterId = null, isDeleted = true)
        )
        assertEquals(listOf("assigned", "null", "blank", "word"), DashboardMath.pendingFood(list).map { it.id })
        assertEquals(listOf("null", "blank", "word"), DashboardMath.unassignedFood(list).map { it.id })
    }

    // ── gym ──

    @Test fun gymSummaryUsesTheEffectiveStatusOpenVisitsAndTodaysKey() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val members = listOf(
            DashGymMember("active", "A", "active", Instant.parse("2026-11-01T00:00:00Z")),
            DashGymMember("noEnd", "B", "active", null),
            DashGymMember("expiredByDate", "C", "active", Instant.parse("2026-10-01T00:00:00Z")),
            DashGymMember("renewed", "D", "expired", Instant.parse("2026-12-01T00:00:00Z")),
            DashGymMember("suspended", "E", "suspended", Instant.parse("2026-12-01T00:00:00Z")),
            DashGymMember("cancelled", "F", "cancelled", null),
            DashGymMember("gone", "G", "active", null, isDeleted = true)
        )
        val visits = listOf(
            DashGymVisit("in1", "m1", dateKey = "2026-10-12"),
            DashGymVisit("in2", "m2", dateKey = "2026-10-12"),
            DashGymVisit("out", "m3", checkOutAt = Instant.parse("2026-10-12T10:00:00Z"), dateKey = "2026-10-12"),
            DashGymVisit("yesterday", "m4", checkOutAt = Instant.parse("2026-10-11T10:00:00Z"), dateKey = "2026-10-11"),
            DashGymVisit("gone", "m5", dateKey = "2026-10-12", isDeleted = true)
        )
        val summary = DashboardMath.gymSummary(members, visits, "2026-10-12", now)
        assertEquals(GymSummary(activeMembers = 3, inGymNow = 2, visitsToday = 3), summary)
    }

    @Test fun gymSummaryWithNothingIsZero() {
        assertEquals(GymSummary(0, 0, 0), DashboardMath.gymSummary(emptyList(), emptyList(), "2026-10-12", Instant.now()))
    }

    // ── labels ──

    @Test fun partyLabelPrefersNameThenRoomThenTableThenGuest() {
        assertEquals("Ada Obi", DashboardMath.partyLabel("Ada Obi", "12", "3"))
        assertEquals("Room 12", DashboardMath.partyLabel(null, "12", "3"))
        assertEquals("Room 12", DashboardMath.partyLabel("  ", "12", null))
        assertEquals("Table 3", DashboardMath.partyLabel("", "", "3"))
        assertEquals("Table 3", DashboardMath.partyLabel(null, null, "3"))
        assertEquals("Guest", DashboardMath.partyLabel(null, null, null))
        assertEquals("Guest", DashboardMath.partyLabel(" ", " ", " "))
    }
}
