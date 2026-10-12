package com.westly.nbms.features.dashboard

import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import com.westly.nbms.features.inventory.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class FrontlineMathTest {

    private val lagos = ZoneId.of("Africa/Lagos")          // UTC+1, no daylight saving
    private val auckland = ZoneId.of("Pacific/Auckland")   // UTC+13 in October

    private fun at(zone: ZoneId, local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    // ── isTodayIn ──

    @Test fun nullIsNeverToday() {
        assertFalse(FrontlineMath.isTodayIn(null, at(lagos, "2026-10-12T10:00:00"), lagos))
    }

    @Test fun todayBoundaryAroundLocalMidnightInLagos() {
        val now = at(lagos, "2026-10-12T12:00:00")
        assertTrue(FrontlineMath.isTodayIn(at(lagos, "2026-10-12T00:00:00"), now, lagos))
        assertTrue(FrontlineMath.isTodayIn(at(lagos, "2026-10-12T23:59:59"), now, lagos))
        assertFalse(FrontlineMath.isTodayIn(at(lagos, "2026-10-11T23:59:59"), now, lagos))
        assertFalse(FrontlineMath.isTodayIn(at(lagos, "2026-10-13T00:00:00"), now, lagos))
    }

    @Test fun todayBoundaryAroundLocalMidnightInAuckland() {
        val now = at(auckland, "2026-10-12T12:00:00")
        assertTrue(FrontlineMath.isTodayIn(at(auckland, "2026-10-12T00:00:00"), now, auckland))
        assertTrue(FrontlineMath.isTodayIn(at(auckland, "2026-10-12T23:59:59"), now, auckland))
        assertFalse(FrontlineMath.isTodayIn(at(auckland, "2026-10-11T23:59:59"), now, auckland))
        assertFalse(FrontlineMath.isTodayIn(at(auckland, "2026-10-13T00:00:00"), now, auckland))
    }

    @Test fun sameInstantCanBeTodayInOneZoneAndNotInAnother() {
        // 2026-10-12 23:30 in Lagos is already 2026-10-13 in Auckland.
        val instant = at(lagos, "2026-10-12T23:30:00")
        assertTrue(FrontlineMath.isTodayIn(instant, at(lagos, "2026-10-12T08:00:00"), lagos))
        assertFalse(FrontlineMath.isTodayIn(instant, at(lagos, "2026-10-12T08:00:00"), auckland))
    }

    // ── receptionStats ──

    private fun booking(
        id: String, status: String?, checkIn: Instant? = null, checkOut: Instant? = null, deleted: Boolean = false
    ) = DashBooking(id = id, status = status, checkIn = checkIn, checkOut = checkOut, isDeleted = deleted)

    @Test fun receptionStatsUsesTheRightStatusesAndDays() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val today = at(lagos, "2026-10-12T14:00:00")
        val tomorrow = at(lagos, "2026-10-13T14:00:00")
        val list = listOf(
            booking("a", "confirmed", checkIn = today),            // expected in
            booking("b", "confirmed", checkIn = tomorrow),         // not today
            booking("c", "pending", checkIn = today),              // wrong status
            booking("d", "checked_in", checkIn = today, checkOut = today),    // expected out + in house
            booking("e", "checked_in", checkOut = tomorrow),       // in house only
            booking("f", "confirmed", checkOut = today),           // confirmed is never expected out
            booking("g", "checked_out", checkOut = today),         // already left
            booking("h", "confirmed", checkIn = today, deleted = true),
            booking("i", "checked_in", checkOut = today, deleted = true)
        )
        val s = FrontlineMath.receptionStats(list, now, lagos)
        assertEquals(listOf("a"), s.expectedIn.map { it.id })
        assertEquals(listOf("d"), s.expectedOut.map { it.id })
        assertEquals(2, s.inHouse)
    }

    @Test fun receptionStatsOfNothingIsEmpty() {
        val s = FrontlineMath.receptionStats(emptyList(), Instant.now(), lagos)
        assertTrue(s.expectedIn.isEmpty())
        assertTrue(s.expectedOut.isEmpty())
        assertEquals(0, s.inHouse)
    }

    // ── myTotals ──

    @Test fun myTotalsCountsRegardlessOfApproval() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val rows = listOf(
            AmountRow(1000.0, at(lagos, "2026-10-12T09:00:00"), "approved"),
            AmountRow(500.0, at(lagos, "2026-10-12T10:00:00"), "pending"),
            AmountRow(250.0, at(lagos, "2026-10-12T11:00:00"), "rejected"),
            AmountRow(2000.0, at(lagos, "2026-10-11T23:59:00"), "approved"),
            AmountRow(300.0, null, null)
        )
        val (today, all) = FrontlineMath.myTotals(rows, now, lagos)
        assertEquals(3 to 1750.0, today)
        assertEquals(5 to 4050.0, all)
    }

    @Test fun myTotalsOfNothingIsZero() {
        val (today, all) = FrontlineMath.myTotals(emptyList(), Instant.now(), lagos)
        assertEquals(0 to 0.0, today)
        assertEquals(0 to 0.0, all)
    }

    @Test fun myTotalsUsesTheBusinessDayNotUtc() {
        // 00:30 Auckland on 13 Oct is still 12 Oct in UTC.
        val now = at(auckland, "2026-10-13T08:00:00")
        val rows = listOf(AmountRow(100.0, at(auckland, "2026-10-13T00:30:00")))
        assertEquals(1 to 100.0, FrontlineMath.myTotals(rows, now, auckland).first)
        assertEquals(0 to 0.0, FrontlineMath.myTotals(rows, at(lagos, "2026-10-12T08:00:00"), lagos).first)
    }

    // ── orderStats ──

    @Test fun orderStatsPendingCountIgnoresTheDate() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val rows = listOf(
            OrderRow("pending", 1000.0, at(lagos, "2026-10-12T09:00:00")),
            OrderRow("pending", 700.0, at(lagos, "2026-10-01T09:00:00")),    // old but still pending
            OrderRow("served", 300.0, at(lagos, "2026-10-12T10:00:00")),
            OrderRow("cancelled", 50.0, at(lagos, "2026-10-11T10:00:00")),
            OrderRow("pending", 10.0, null)                                  // no date: pending only
        )
        val s = FrontlineMath.orderStats(rows, now, lagos)
        assertEquals(2, s.todayCount)
        assertEquals(3, s.pendingCount)
        assertEquals(1300.0, s.todayAmount, 0.0001)
    }

    @Test fun orderStatsOfNothingIsZero() {
        assertEquals(OrderStats(0, 0, 0.0), FrontlineMath.orderStats(emptyList(), Instant.now(), lagos))
    }

    // ── laundryStats ──

    @Test fun laundryStatsActiveReadyAndDeliveredToday() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val rows = listOf(
            FrontlineLaundryRow("received"), FrontlineLaundryRow("washing"), FrontlineLaundryRow("drying"),
            FrontlineLaundryRow("ironing"), FrontlineLaundryRow("ready"), FrontlineLaundryRow("ready"),
            FrontlineLaundryRow("delivered", at(lagos, "2026-10-12T08:00:00")),
            FrontlineLaundryRow("delivered", at(lagos, "2026-10-11T08:00:00")),
            FrontlineLaundryRow("cancelled"),
            FrontlineLaundryRow(null)
        )
        val s = FrontlineMath.laundryStats(rows, now, lagos)
        assertEquals(6, s.active)
        assertEquals(2, s.ready)
        assertEquals(1, s.deliveredToday)
    }

    @Test fun laundryDeliveredTodayNeedsStatusDelivered() {
        val now = at(lagos, "2026-10-12T12:00:00")
        val rows = listOf(
            FrontlineLaundryRow("ready", at(lagos, "2026-10-12T08:00:00")),      // has a delivery time but is not delivered
            FrontlineLaundryRow("delivered", null)                               // delivered without a time
        )
        assertEquals(0, FrontlineMath.laundryStats(rows, now, lagos).deliveredToday)
    }

    // ── housekeepingStats ──

    @Test fun housekeepingHighPriorityIsUrgentPlusHigh() {
        val queue = listOf(
            HkTaskRow("101", "urgent", "pending"), HkTaskRow("102", "high", "in_progress"),
            HkTaskRow("103", "medium", "pending"), HkTaskRow("104", "low", "pending"),
            HkTaskRow("105", "urgent", "completed"),       // not in the queue
            HkTaskRow("106", "high", "skipped")             // not in the queue
        )
        val s = FrontlineMath.housekeepingStats(queue, rooms = 4)
        assertEquals(4, s.queue)
        assertEquals(2, s.highPriority)
        assertEquals(4, s.myRooms)
    }

    @Test fun housekeepingStatsOfNothing() {
        assertEquals(HkStats(0, 0, 0), FrontlineMath.housekeepingStats(emptyList(), 0))
    }

    // ── gymDeskStats ──

    private val gymNow = at(lagos, "2026-10-12T12:00:00")

    private fun member(name: String, status: String?, end: Instant?, deleted: Boolean = false) =
        DashGymMember(id = name, name = name, status = status, endDate = end, isDeleted = deleted)

    private fun visit(id: String, key: String?, out: Instant? = null, deleted: Boolean = false) =
        DashGymVisit(id = id, memberId = "m", dateKey = key, checkOutAt = out, isDeleted = deleted)

    @Test fun gymExpiringWindowIsZeroToSevenDaysInclusive() {
        val members = listOf(
            member("Today", "active", gymNow.plus(Duration.ofHours(3))),                      // ceil 1 day
            member("Exactly7", "active", gymNow.plus(Duration.ofDays(7))),                    // 7 → included
            member("Over7", "active", gymNow.plus(Duration.ofDays(7)).plusSeconds(3600)),     // 8 → excluded
            member("Day3", "active", gymNow.plus(Duration.ofDays(3))),
            member("NoEnd", "active", null)
        )
        val s = FrontlineMath.gymDeskStats(members, emptyList(), "2026-10-12", gymNow)
        assertEquals(listOf("Today" to 1, "Day3" to 3, "Exactly7" to 7), s.expiring.map { it.name to it.daysLeft })
        assertEquals(5, s.active)
    }

    @Test fun gymEndingRightNowCountsAsZeroDaysLeft() {
        val members = listOf(member("Now", "active", gymNow))
        val s = FrontlineMath.gymDeskStats(members, emptyList(), "2026-10-12", gymNow)
        assertEquals(listOf(ExpiringMember("Now", 0)), s.expiring)
    }

    @Test fun gymExpiredMembersAreExcluded() {
        val members = listOf(
            member("Expired", "active", gymNow.minus(Duration.ofHours(1))),       // end date passed: effective status expired
            member("OldExpired", "active", gymNow.minus(Duration.ofDays(3))),
            member("Stored", "expired", gymNow.minus(Duration.ofDays(1))),
            member("Suspended", "suspended", gymNow.plus(Duration.ofDays(2))),
            member("Cancelled", "cancelled", gymNow.plus(Duration.ofDays(2))),
            member("Deleted", "active", gymNow.plus(Duration.ofDays(2)), deleted = true),
            member("Live", "active", gymNow.plus(Duration.ofDays(2)))
        )
        val s = FrontlineMath.gymDeskStats(members, emptyList(), "2026-10-12", gymNow)
        assertEquals(1, s.active)
        assertEquals(listOf(ExpiringMember("Live", 2)), s.expiring)
    }

    @Test fun gymExpiringIsSoonestFirstThenByName() {
        val members = listOf(
            member("Zed", "active", gymNow.plus(Duration.ofDays(2))),
            member("Amy", "active", gymNow.plus(Duration.ofDays(2))),
            member("Ben", "active", gymNow.plus(Duration.ofDays(1)))
        )
        val s = FrontlineMath.gymDeskStats(members, emptyList(), "2026-10-12", gymNow)
        assertEquals(listOf("Ben", "Amy", "Zed"), s.expiring.map { it.name })
    }

    @Test fun gymVisitsInGymNowAndVisitsToday() {
        val visits = listOf(
            visit("1", "2026-10-12"),                                             // still in the gym, today
            visit("2", "2026-10-12", out = gymNow),                               // left, today
            visit("3", "2026-10-11"),                                             // still "in" from yesterday, not today
            visit("4", "2026-10-12", deleted = true),
            visit("5", null)
        )
        val s = FrontlineMath.gymDeskStats(emptyList(), visits, "2026-10-12", gymNow)
        assertEquals(3, s.inGymNow)          // visits 1, 3 and 5 have no check-out; the deleted one is ignored
        assertEquals(2, s.visitsToday)
        assertEquals(0, s.active)
        assertTrue(s.expiring.isEmpty())
    }

    // ── lowStockTop ──

    private fun item(name: String, qty: Int, min: Int, deleted: Boolean = false) =
        InventoryItem(id = name, name = name, quantity = qty, minStock = min, isDeleted = deleted)

    @Test fun lowStockTopKeepsTheFirstNLowItems() {
        val items = listOf(
            item("a", 1, 5), item("b", 50, 5), item("c", 5, 5), item("d", 0, 2, deleted = true),
            item("e", 2, 5), item("f", 3, 5), item("g", 1, 1), item("h", 0, 1)
        )
        assertEquals(listOf("a", "c", "e", "f", "g"), FrontlineMath.lowStockTop(items).map { it.name })
        assertEquals(listOf("a", "c"), FrontlineMath.lowStockTop(items, 2).map { it.name })
        assertEquals(6, FrontlineMath.lowStockTop(items, 50).size)
        assertTrue(FrontlineMath.lowStockTop(items, 0).isEmpty())
        assertTrue(FrontlineMath.lowStockTop(items, -3).isEmpty())
    }
}
