package com.westly.nbms.features.reports

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.finance.ApprovalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class AnnualReportCalculatorTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")      // UTC+1, no daylight saving
    private val october = YearMonth.of(2026, 10)

    private fun build(
        txns: List<com.westly.nbms.features.finance.RevenueTransaction> = emptyList(),
        expenses: List<Expense> = emptyList(),
        bookings: List<com.westly.nbms.features.bookings.Booking> = emptyList(),
        rooms: List<com.westly.nbms.features.rooms.Room> = emptyList(),
        current: YearMonth = october
    ) = AnnualReportCalculator.build(txns, expenses, bookings, rooms, current, lagos)

    // ---- months ----

    @Test fun months_runFromJanuaryToTheCurrentMonth() {
        val report = build()
        assertEquals(10, report.months.size)
        assertEquals("Jan", report.months.first().label)
        assertEquals("Oct", report.months.last().label)
        assertEquals(2026, report.year)
    }

    @Test fun months_inJanuary_isASingleMonth() {
        val report = build(current = YearMonth.of(2026, 1))
        assertEquals(listOf("Jan"), report.months.map { it.label })
    }

    // ---- revenue ----

    @Test fun revenue_countsApprovedOnly() {
        val report = build(
            txns = listOf(
                annualTxn("a", 1000.0),
                annualTxn("b", 500.0, status = ApprovalStatus.PENDING),
                annualTxn("c", 700.0, status = ApprovalStatus.REJECTED)
            )
        )
        assertEquals(1000.0, report.months.last().revenue, 0.0)
        assertEquals(1000.0, report.ytdRevenue, 0.0)
    }

    @Test fun revenue_isGroupedByMonthInTheBusinessZone() {
        // 2026-09-30T23:30Z is already 00:30 on 1 October in Lagos.
        val report = build(
            txns = listOf(
                annualTxn("sept", 100.0, date = "2026-09-15T12:00:00Z"),
                annualTxn("edge", 200.0, date = "2026-09-30T23:30:00Z"),
                annualTxn("oct", 400.0, date = "2026-10-15T12:00:00Z"),
                annualTxn("no-date", 800.0, date = null),
                annualTxn("next-year", 1600.0, date = "2027-01-15T12:00:00Z")
            )
        )
        assertEquals(100.0, report.months[8].revenue, 0.0)
        assertEquals(600.0, report.months[9].revenue, 0.0)
        assertEquals(700.0, report.ytdRevenue, 0.0)
    }

    // ---- expenses and profit ----

    @Test fun expenses_skipDeleted_andUndated_andOtherMonths() {
        val report = build(
            expenses = listOf(
                annualExpense("a", 100.0),
                annualExpense("b", 50.0, date = "2026-09-15T10:00:00Z"),
                annualExpense("c", 75.0, isDeleted = true),
                annualExpense("d", 25.0, date = null)
            )
        )
        assertEquals(100.0, report.months[9].expenses, 0.0)
        assertEquals(50.0, report.months[8].expenses, 0.0)
        assertEquals(150.0, report.ytdExpenses, 0.0)
    }

    @Test fun profit_isRevenueMinusExpenses_andCanBeNegative() {
        val report = build(
            txns = listOf(annualTxn("a", 1000.0), annualTxn("b", 200.0, date = "2026-09-10T10:00:00Z")),
            expenses = listOf(annualExpense("e", 400.0), annualExpense("f", 500.0, date = "2026-09-10T10:00:00Z"))
        )
        assertEquals(600.0, report.months[9].profit, 0.0)
        assertEquals(-300.0, report.months[8].profit, 0.0)
        assertEquals(1200.0, report.ytdRevenue, 0.0)
        assertEquals(900.0, report.ytdExpenses, 0.0)
        assertEquals(300.0, report.ytdProfit, 0.0)
    }

    // ---- bookings ----

    @Test fun bookings_areCountedByCreatedAt_notDeleted() {
        val report = build(
            bookings = listOf(
                annualBooking("a"),
                annualBooking("b", createdAt = "2026-10-20T10:00:00Z"),
                annualBooking("c", createdAt = "2026-09-20T10:00:00Z"),
                annualBooking("d", isDeleted = true),
                annualBooking("e", createdAt = null),
                annualBooking("f", createdAt = "2025-10-20T10:00:00Z")
            )
        )
        assertEquals(2, report.months[9].bookings)
        assertEquals(1, report.months[8].bookings)
        assertEquals(0, report.months[0].bookings)
    }

    // ---- occupancy ----

    @Test fun occupancy_isCheckedInOverRooms_rounded() {
        val rooms = listOf(annualRoom("1"), annualRoom("2"), annualRoom("3"))
        val bookings = listOf(
            annualBooking("a", status = "checked_in"),
            annualBooking("b", status = "checked_in"),
            annualBooking("c", status = "confirmed"),
            annualBooking("d", status = "checked_in", isDeleted = true)
        )
        assertEquals(67, build(bookings = bookings, rooms = rooms).occupancyPercent)      // 2 / 3 = 66.67
    }

    @Test fun occupancy_ignoresDeletedRooms() {
        val rooms = listOf(annualRoom("1"), annualRoom("2"), annualRoom("3", isDeleted = true))
        val bookings = listOf(annualBooking("a", status = "checked_in"))
        assertEquals(50, build(bookings = bookings, rooms = rooms).occupancyPercent)
    }

    @Test fun occupancy_isZeroWhenThereAreNoRooms() {
        val bookings = listOf(annualBooking("a", status = "checked_in"))
        assertEquals(0, build(bookings = bookings, rooms = emptyList()).occupancyPercent)
        assertEquals(0, build(bookings = bookings, rooms = listOf(annualRoom("x", isDeleted = true))).occupancyPercent)
    }

    // ---- the page state ----

    @Test fun view_isLoadingUntilEverythingArrived_andErrorWinsOverLoading() {
        val ready = Resource.Success(emptyList<Nothing>())
        assertTrue(annualReportViewOf(Resource.Loading, ready, ready, ready, october, lagos) is AnnualReportView.Loading)
        assertTrue(annualReportViewOf(ready, ready, ready, Resource.Loading, october, lagos) is AnnualReportView.Loading)
        val error = annualReportViewOf(Resource.Loading, Resource.Error("x"), ready, ready, october, lagos)
        assertEquals(AnnualReportView.Error("We couldn't load report data."), error)
        assertTrue(annualReportViewOf(ready, ready, ready, ready, october, lagos) is AnnualReportView.Ready)
    }

    // ---- the line chart's y axis ----

    @Test fun wholeNumberTicks_smallOrEmptyDataGivesZeroToFour() {
        val expected = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        assertEquals(expected, wholeNumberTicks(0.0))
        assertEquals(expected, wholeNumberTicks(1.0))
        assertEquals(expected, wholeNumberTicks(4.0))
        assertEquals(expected, wholeNumberTicks(Double.NaN))
    }

    @Test fun wholeNumberTicks_stepsAreWholeNumbers_andReachTheMax() {
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0), wholeNumberTicks(5.0))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), wholeNumberTicks(40.0))
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0), wholeNumberTicks(45.0))
        for (max in listOf(7.0, 13.0, 99.0, 250.0, 1234.0)) {
            val ticks = wholeNumberTicks(max)
            assertTrue(ticks.last() >= max)
            assertTrue(ticks.all { it == Math.floor(it) })
        }
    }
}
