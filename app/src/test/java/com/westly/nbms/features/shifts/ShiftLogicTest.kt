package com.westly.nbms.features.shifts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ShiftLogicTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun shift(
        id: String = "s", date: String = "2026-10-12", start: String = "08:00", end: String = "16:00",
        next: Boolean = false, label: String = "Day", status: String = "scheduled", staff: String = "a", series: String? = null
    ) = Shift(id = id, staffId = staff, date = date, startTime = start, endTime = end, endsNextDay = next, label = label, status = status, seriesId = series)

    // ── helpers ──
    @Test fun dateKeysRoundTrip() {
        assertEquals("2026-01-05", d("2026-01-05").toDateKey())
        assertEquals(d("2026-12-31"), "2026-12-31".toLocalDateOrNull())
        assertNull("".toLocalDateOrNull()); assertNull("2026-13-01".toLocalDateOrNull()); assertNull("12/10/2026".toLocalDateOrNull())
    }

    @Test fun toMinutesParsesAndRejects() {
        assertEquals(0, ShiftLogic.toMinutes("00:00")); assertEquals(1439, ShiftLogic.toMinutes("23:59")); assertEquals(510, ShiftLogic.toMinutes("08:30"))
        try { ShiftLogic.toMinutes("25:00"); throw AssertionError("expected failure") } catch (e: IllegalArgumentException) { }
        try { ShiftLogic.toMinutes("abc"); throw AssertionError("expected failure") } catch (e: IllegalArgumentException) { }
    }

    // ── overlap ──
    @Test fun sameDayOverlap() = assertTrue(ShiftLogic.shiftsOverlap("08:00", "16:00", false, "15:00", "20:00", false))
    @Test fun backToBackIsNotOverlap() {
        assertFalse(ShiftLogic.shiftsOverlap("08:00", "16:00", false, "16:00", "22:00", false))
        assertFalse(ShiftLogic.shiftsOverlap("16:00", "22:00", false, "08:00", "16:00", false))
    }
    @Test fun overnightVsLateEveningOverlaps() = assertTrue(ShiftLogic.shiftsOverlap("22:00", "06:00", true, "23:00", "23:30", false))
    @Test fun overnightDoesNotOverlapSameDayEarlyMorning() {
        // the early hours belong to the NEXT day, so a 05:00 shift on the same date does not clash here
        assertFalse(ShiftLogic.shiftsOverlap("22:00", "06:00", true, "05:00", "07:00", false))
    }
    @Test fun endBeforeStartWithoutFlagStillWrapsMidnight() = assertTrue(ShiftLogic.shiftsOverlap("22:00", "06:00", false, "23:00", "23:30", false))

    // ── occurrences ──
    @Test fun noneGivesTheStartDateOnly() = assertEquals(listOf(d("2026-10-12")), ShiftLogic.generateOccurrenceDates(d("2026-10-12"), ShiftRecurrence()))
    @Test fun dailyUntilDateIsInclusive() {
        val r = ShiftLogic.generateOccurrenceDates(d("2026-10-12"), ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-15")))
        assertEquals(listOf("2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15"), r.map { it.toDateKey() })
    }
    @Test fun weeklyUsesSundayZeroDays() {
        // 2026-10-11 is a Sunday. Sunday(0) and Wednesday(3).
        val r = ShiftLogic.generateOccurrenceDates(d("2026-10-11"), ShiftRecurrence(RecurrenceType.WEEKLY, listOf(0, 3), d("2026-10-25")))
        assertEquals(listOf("2026-10-11", "2026-10-14", "2026-10-18", "2026-10-21", "2026-10-25"), r.map { it.toDateKey() })
    }
    @Test fun weeklySaturdayIsSix() {
        val r = ShiftLogic.generateOccurrenceDates(d("2026-10-10"), ShiftRecurrence(RecurrenceType.WEEKLY, listOf(6), d("2026-10-24")))
        assertEquals(listOf("2026-10-10", "2026-10-17", "2026-10-24"), r.map { it.toDateKey() })
    }
    @Test fun weeklyEmptyDaysMeansEveryDay() {
        val r = ShiftLogic.generateOccurrenceDates(d("2026-10-12"), ShiftRecurrence(RecurrenceType.WEEKLY, emptyList(), d("2026-10-18")))
        assertEquals(7, r.size)
    }
    @Test fun capsAtSixtyDates() {
        val r = ShiftLogic.generateOccurrenceDates(d("2026-01-01"), ShiftRecurrence(RecurrenceType.DAILY, until = d("2027-12-31")))
        assertEquals(60, r.size); assertEquals(d("2026-03-01"), r.last())
    }
    @Test fun defaultEndIsThreeMonthsAndIsCappedBySixty() {
        val r = ShiftLogic.generateOccurrenceDates(d("2026-01-31"), ShiftRecurrence(RecurrenceType.DAILY))
        assertEquals(60, r.size)   // 3 months is about 90 days, so the cap wins
        val weekly = ShiftLogic.generateOccurrenceDates(d("2026-01-31"), ShiftRecurrence(RecurrenceType.WEEKLY, listOf(1)))
        assertTrue(weekly.last() <= d("2026-04-30")); assertTrue(weekly.size in 12..14)
    }
    @Test fun fourHundredDayGuardStopsEmptyWalks() {
        // a day number that never matches walks the guard and ends with nothing instead of looping forever
        val r = ShiftLogic.generateOccurrenceDates(d("2026-01-01"), ShiftRecurrence(RecurrenceType.WEEKLY, listOf(9), d("2030-01-01")))
        assertTrue(r.isEmpty())
    }
    @Test fun untilBeforeStartGivesNothing() =
        assertTrue(ShiftLogic.generateOccurrenceDates(d("2026-10-12"), ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-01"))).isEmpty())

    // ── conflicts ──
    @Test fun sameDayConflictIsReported() {
        val c = ShiftLogic.findConflicts(listOf(d("2026-10-12")), "10:00", "12:00", false, listOf(shift(label = "Front desk")))
        assertEquals(listOf(ConflictInfo("2026-10-12", "Front desk", "08:00–16:00")), c)
    }
    @Test fun backToBackIsNotAConflict() =
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-12")), "16:00", "20:00", false, listOf(shift())).isEmpty())
    @Test fun otherDatesAreIgnored() =
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-13")), "10:00", "12:00", false, listOf(shift())).isEmpty())
    @Test fun cancelledShiftsAreIgnored() =
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-12")), "10:00", "12:00", false, listOf(shift(status = "cancelled"))).isEmpty())
    @Test fun yesterdaysOvernightShiftBlocksEarlyHours() {
        val night = shift(date = "2026-10-11", start = "22:00", end = "06:00", next = true, label = "Night")
        val c = ShiftLogic.findConflicts(listOf(d("2026-10-12")), "04:00", "08:00", false, listOf(night))
        assertEquals(listOf(ConflictInfo("2026-10-12", "Night", "22:00–06:00")), c)
        // after the overnight shift ended, no clash
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-12")), "06:00", "10:00", false, listOf(night)).isEmpty())
    }
    @Test fun yesterdaysNonOvernightShiftNeverBlocks() =
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-12")), "00:00", "04:00", false, listOf(shift(date = "2026-10-11", start = "08:00", end = "23:00"))).isEmpty())
    @Test fun overnightCandidateBlocksTomorrowsEarlyShift() {
        val early = shift(date = "2026-10-13", start = "02:00", end = "09:00", label = "Early")
        val c = ShiftLogic.findConflicts(listOf(d("2026-10-12")), "22:00", "06:00", true, listOf(early))
        assertEquals(1, c.size); assertEquals("2026-10-12", c.single().date)
    }
    @Test fun excludeShiftIdSkipsTheShiftBeingEdited() {
        val me = shift(id = "me")
        assertTrue(ShiftLogic.findConflicts(listOf(d("2026-10-12")), "09:00", "17:00", false, listOf(me), excludeShiftId = "me").isEmpty())
        assertEquals(2, ShiftLogic.findConflicts(listOf(d("2026-10-12")), "09:00", "17:00", false, listOf(me, shift(id = "other"))).size)
    }
    @Test fun everyConflictingCandidateDateIsListed() {
        val existing = listOf(shift(date = "2026-10-12"), shift(id = "t", date = "2026-10-14"))
        val c = ShiftLogic.findConflicts(listOf(d("2026-10-12"), d("2026-10-13"), d("2026-10-14")), "10:00", "11:00", false, existing)
        assertEquals(listOf("2026-10-12", "2026-10-14"), c.map { it.date })
    }

    // ── on duty ──
    private fun at(s: String) = LocalDateTime.parse(s)
    @Test fun onDutyBoundaries() {
        val s = shift(date = "2026-10-12", start = "08:00", end = "16:00")
        assertFalse(ShiftLogic.isOnDutyNow(s, at("2026-10-12T07:59:59")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-12T08:00:00")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-12T15:59:59")))
        assertFalse(ShiftLogic.isOnDutyNow(s, at("2026-10-12T16:00:00")))
        assertFalse(ShiftLogic.isOnDutyNow(s, at("2026-10-11T12:00:00")))
    }
    @Test fun overnightShiftIsOnDutyAfterMidnight() {
        val s = shift(date = "2026-10-12", start = "22:00", end = "06:00", next = true)
        assertFalse(ShiftLogic.isOnDutyNow(s, at("2026-10-12T21:59:00")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-12T22:00:00")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-12T23:59:00")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-13T00:00:00")))
        assertTrue(ShiftLogic.isOnDutyNow(s, at("2026-10-13T05:59:00")))
        assertFalse(ShiftLogic.isOnDutyNow(s, at("2026-10-13T06:00:00")))
    }
    @Test fun endBeforeStartIsTreatedAsOvernightEvenWithoutFlag() =
        assertTrue(ShiftLogic.isOnDutyNow(shift(start = "22:00", end = "02:00"), at("2026-10-13T01:00:00")))
    @Test fun cancelledOrBrokenShiftIsNeverOnDuty() {
        assertFalse(ShiftLogic.isOnDutyNow(shift(status = "cancelled"), at("2026-10-12T10:00:00")))
        assertFalse(ShiftLogic.isOnDutyNow(shift(date = "garbage"), at("2026-10-12T10:00:00")))
    }

    // ── view ranges ──
    @Test fun dayRangeIsTheAnchor() = assertEquals(d("2026-10-14") to d("2026-10-14"), ShiftLogic.viewRange(ShiftViewMode.DAY, d("2026-10-14")))
    @Test fun weekRangeRunsSundayToSaturday() {
        assertEquals(d("2026-10-11") to d("2026-10-17"), ShiftLogic.viewRange(ShiftViewMode.WEEK, d("2026-10-14")))   // Wednesday
        assertEquals(d("2026-10-11") to d("2026-10-17"), ShiftLogic.viewRange(ShiftViewMode.WEEK, d("2026-10-11")))   // Sunday
        assertEquals(d("2026-10-11") to d("2026-10-17"), ShiftLogic.viewRange(ShiftViewMode.WEEK, d("2026-10-17")))   // Saturday
    }
    @Test fun monthRangeCoversTheWholeMonth() {
        assertEquals(d("2026-10-01") to d("2026-10-31"), ShiftLogic.viewRange(ShiftViewMode.MONTH, d("2026-10-14")))
        assertEquals(d("2026-02-01") to d("2026-02-28"), ShiftLogic.viewRange(ShiftViewMode.MONTH, d("2026-02-10")))
        assertEquals(d("2028-02-01") to d("2028-02-29"), ShiftLogic.viewRange(ShiftViewMode.MONTH, d("2028-02-10")))
    }
    @Test fun stepSizes() {
        val a = d("2026-10-14")
        assertEquals(d("2026-10-15"), ShiftLogic.step(ShiftViewMode.DAY, a, 1)); assertEquals(d("2026-10-13"), ShiftLogic.step(ShiftViewMode.DAY, a, -1))
        assertEquals(d("2026-10-21"), ShiftLogic.step(ShiftViewMode.WEEK, a, 1)); assertEquals(d("2026-10-07"), ShiftLogic.step(ShiftViewMode.WEEK, a, -1))
        assertEquals(d("2026-11-13"), ShiftLogic.step(ShiftViewMode.MONTH, a, 1)); assertEquals(d("2026-09-14"), ShiftLogic.step(ShiftViewMode.MONTH, a, -1))
    }

    // ── series cancel selection ──
    @Test fun seriesCancelPicksNonCancelledOnOrAfterFromDate() {
        val series = listOf(
            shift(id = "1", date = "2026-10-10"), shift(id = "2", date = "2026-10-12"),
            shift(id = "3", date = "2026-10-13", status = "cancelled"), shift(id = "4", date = "2026-10-20"), shift(id = "5", date = "bad")
        )
        assertEquals(listOf("2", "4"), ShiftLogic.selectSeriesToCancel(series, d("2026-10-12")).map { it.id })
    }
}
