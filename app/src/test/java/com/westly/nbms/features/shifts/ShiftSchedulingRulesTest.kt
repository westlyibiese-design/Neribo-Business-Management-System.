package com.westly.nbms.features.shifts

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ShiftSchedulingRulesTest {

    private fun d(s: String) = LocalDate.parse(s)

    private fun shift(
        id: String = "s1", date: String = "2026-03-02", start: String = "08:00", end: String = "16:00",
        next: Boolean = false, status: String = "scheduled", name: String = "Ada", staffId: String = "ada",
        label: String = "Morning", seriesId: String? = null
    ) = Shift(
        id = id, role = "housekeeping", staffId = staffId, staffName = name, date = date, startTime = start,
        endTime = end, endsNextDay = next, label = label, status = status, seriesId = seriesId
    )

    private val ada = ShiftStaff("ada", "Ada")
    private val bola = ShiftStaff("bola", "Bola")

    private fun form(
        staffId: String? = "ada", label: String = "Morning Shift",
        type: RecurrenceType = RecurrenceType.NONE, weekdays: Set<Int> = emptySet()
    ) = ShiftFormState(staffId = staffId, label = label, date = d("2026-03-02"), recurrenceType = type, weekdays = weekdays)

    // ---- validation order ----

    @Test fun noStaffComesFirstEvenWhenEverythingElseIsWrong() {
        val f = form(staffId = null, label = " ", type = RecurrenceType.WEEKLY)
        assertEquals(ShiftFormIssue.NO_STAFF, validateShiftForm(f, staffSelected = false, isEdit = false))
    }

    @Test fun blankLabelComesBeforeTheWeekdayCheck() {
        val f = form(label = "   ", type = RecurrenceType.WEEKLY)
        assertEquals(ShiftFormIssue.MISSING_LABEL, validateShiftForm(f, true, isEdit = false))
    }

    @Test fun weeklyWithoutAWeekdayIsRefusedForNewShiftsOnly() {
        val f = form(type = RecurrenceType.WEEKLY)
        assertEquals(ShiftFormIssue.NO_WEEKDAY, validateShiftForm(f, true, isEdit = false))
        assertNull(validateShiftForm(f, true, isEdit = true))
    }

    @Test fun validFormsPass() {
        assertNull(validateShiftForm(form(), true, isEdit = false))
        assertNull(validateShiftForm(form(type = RecurrenceType.DAILY), true, isEdit = false))
        assertNull(validateShiftForm(form(type = RecurrenceType.WEEKLY, weekdays = setOf(1)), true, isEdit = false))
    }

    @Test fun issueToastsUseTheSpecWording() {
        assertEquals(ShiftToastText(null, "Select a staff member"), ShiftFormIssue.NO_STAFF.toToast())
        assertEquals(
            ShiftToastText("Give the shift a label", "e.g. Morning Shift, Night Shift"),
            ShiftFormIssue.MISSING_LABEL.toToast()
        )
        assertEquals(ShiftToastText(null, "Pick at least one weekday"), ShiftFormIssue.NO_WEEKDAY.toToast())
    }

    // ---- form building ----

    @Test fun newFormStartsWithTheFirstPersonAndDefaultTimes() {
        val f = newShiftForm(d("2026-03-02"), listOf(ada, bola))
        assertEquals("ada", f.staffId)
        assertEquals("08:00", f.startTime)
        assertEquals("16:00", f.endTime)
        assertFalse(f.endsNextDay)
        assertEquals(RecurrenceType.NONE, f.recurrenceType)
        assertNull(newShiftForm(d("2026-03-02"), emptyList()).staffId)
    }

    @Test fun editFormIsFilledFromTheShift() {
        val f = editShiftForm(shift(start = "22:00", end = "06:00", next = true, label = "Night").copy(notes = "Keys"), d("2026-01-01"))
        assertEquals("ada", f.staffId)
        assertEquals("Night", f.label)
        assertEquals(d("2026-03-02"), f.date)
        assertEquals("22:00", f.startTime)
        assertTrue(f.endsNextDay)
        assertEquals("Keys", f.notes)
        assertEquals(d("2026-01-01"), editShiftForm(shift(date = "oops"), d("2026-01-01")).date)
    }

    @Test fun staffResolvesToTheFirstPersonOnlyWhenNothingWasChosen() {
        assertEquals(ada, resolveShiftStaff(form(staffId = null), listOf(ada, bola)))
        assertEquals(bola, resolveShiftStaff(form(staffId = "bola"), listOf(ada, bola)))
        assertNull(resolveShiftStaff(form(staffId = "gone"), listOf(ada, bola)))
        assertNull(resolveShiftStaff(form(staffId = null), emptyList()))
    }

    @Test fun inputIsTrimmedAndBlankNotesBecomeNull() {
        val input = form(label = "  Morning Shift ").copy(notes = "   ").toInput(Role.HOUSEKEEPING, ada)
        assertEquals("Morning Shift", input.label)
        assertNull(input.notes)
        assertEquals(Role.HOUSEKEEPING, input.role)
        assertEquals("ada", input.staffId)
        assertEquals("Ada", input.staffName)
        assertEquals(d("2026-03-02"), input.startDate)
        assertEquals("Keys", form().copy(notes = " Keys ").toInput(Role.HOUSEKEEPING, ada).notes)
    }

    @Test fun recurrenceFollowsTheForm() {
        assertEquals(ShiftRecurrence(RecurrenceType.NONE), form().copy(until = d("2026-04-01")).toRecurrence())
        assertEquals(
            ShiftRecurrence(RecurrenceType.DAILY, emptyList(), d("2026-04-01")),
            form(type = RecurrenceType.DAILY, weekdays = setOf(1)).copy(until = d("2026-04-01")).toRecurrence()
        )
        assertEquals(
            ShiftRecurrence(RecurrenceType.WEEKLY, listOf(1, 3, 5), null),
            form(type = RecurrenceType.WEEKLY, weekdays = setOf(5, 1, 3)).toRecurrence()
        )
    }

    @Test fun updateCarriesOnlyTheEditableFields() {
        val u = form(label = " Late ").copy(startTime = "10:00", endTime = "18:00", endsNextDay = false, notes = "").toUpdate(bola)
        assertEquals(ShiftUpdate("bola", "Bola", "10:00", "18:00", false, "Late", null), u)
    }

    @Test fun labelsAndTitles() {
        assertEquals("Does not repeat", recurrenceLabel(RecurrenceType.NONE))
        assertEquals("Repeats daily", recurrenceLabel(RecurrenceType.DAILY))
        assertEquals("Repeats weekly on selected days", recurrenceLabel(RecurrenceType.WEEKLY))
        assertEquals("Schedule Shift · Housekeeping", shiftSheetTitle(Role.HOUSEKEEPING, null))
        assertEquals("Edit Shift — Night", shiftSheetTitle(Role.HOUSEKEEPING, shift(label = "Night")))
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), WEEKDAY_LABELS)
    }

    @Test fun elevenRolesWorkShiftsAndReceptionistIsTheDefault() {
        val roles = shiftRoleOptions()
        assertEquals(11, roles.size)
        assertEquals(Role.RECEPTIONIST, roles.first())
        assertEquals(Role.RECEPTIONIST, DEFAULT_SHIFT_ROLE)
        assertFalse(Role.MANAGER in roles)
    }

    // ---- range label and day titles ----

    @Test fun dayLabel() {
        assertEquals("Monday, March 2, 2026", rangeLabel(ShiftViewMode.DAY, d("2026-03-02")))
    }

    @Test fun weekLabelRunsSundayToSaturday() {
        assertEquals("Mar 1 – Mar 7, 2026", rangeLabel(ShiftViewMode.WEEK, d("2026-03-04")))
        assertEquals("Mar 1 – Mar 7, 2026", rangeLabel(ShiftViewMode.WEEK, d("2026-03-01")))
        assertEquals("Mar 1 – Mar 7, 2026", rangeLabel(ShiftViewMode.WEEK, d("2026-03-07")))
    }

    @Test fun weekLabelAcrossMonthsAndYears() {
        assertEquals("Mar 29 – Apr 4, 2026", rangeLabel(ShiftViewMode.WEEK, d("2026-03-30")))
        assertEquals("Dec 27 – Jan 2, 2027", rangeLabel(ShiftViewMode.WEEK, d("2026-12-30")))
    }

    @Test fun monthLabel() {
        assertEquals("March 2026", rangeLabel(ShiftViewMode.MONTH, d("2026-03-17")))
    }

    @Test fun dayTitles() {
        assertEquals("Monday, Mar 2", dayTitle(d("2026-03-02"), compact = false))
        assertEquals("Mar 2", dayTitle(d("2026-03-02"), compact = true))
    }

    @Test fun daysInRange() {
        val week = daysInRange(ShiftViewMode.WEEK, d("2026-03-04"))
        assertEquals(7, week.size)
        assertEquals(d("2026-03-01"), week.first())
        assertEquals(d("2026-03-07"), week.last())
        assertEquals(31, daysInRange(ShiftViewMode.MONTH, d("2026-03-20")).size)
        assertEquals(listOf(d("2026-03-04")), daysInRange(ShiftViewMode.DAY, d("2026-03-04")))
    }

    // ---- grouping, sorting, hiding cancelled ----

    @Test fun cancelledShiftsAreHidden() {
        val all = listOf(shift("a"), shift("b", status = "cancelled"), shift("c"))
        assertEquals(listOf("a", "c"), visibleShifts(all).map { it.id })
    }

    @Test fun shiftsAreGroupedPerDaySortedByStartTimeThenName() {
        val days = daysInRange(ShiftViewMode.WEEK, d("2026-03-04"))
        val grouped = groupShiftsByDay(
            listOf(
                shift("late", start = "10:00", name = "Cara"),
                shift("b", start = "08:00", name = "Bola"),
                shift("a", start = "08:00", name = "Ada"),
                shift("other", date = "2026-03-05", start = "06:00"),
                shift("before", date = "2026-02-28", start = "06:00")
            ),
            days
        )
        assertEquals(7, grouped.size)
        assertEquals(listOf("a", "b", "late"), grouped.first { it.date == d("2026-03-02") }.shifts.map { it.id })
        assertEquals(listOf("other"), grouped.first { it.date == d("2026-03-05") }.shifts.map { it.id })
        assertTrue(grouped.first { it.date == d("2026-03-03") }.shifts.isEmpty())
        assertEquals(0, grouped.sumOf { g -> g.shifts.count { it.id == "before" } })
    }

    // ---- stats, on duty, board ----

    private val staff3 = listOf(ada, bola, ShiftStaff("cara", "Cara"))

    @Test fun statsCountRosterShiftsInViewAndOnDuty() {
        val board = buildShiftBoard(
            role = Role.HOUSEKEEPING, mode = ShiftViewMode.WEEK, anchor = d("2026-03-04"),
            shifts = listOf(
                shift("on", date = "2026-03-04", start = "08:00", end = "16:00", name = "Ada"),
                shift("later", date = "2026-03-04", start = "18:00", end = "22:00", name = "Bola", staffId = "bola"),
                shift("ended", date = "2026-03-03", start = "22:00", end = "06:00", next = true, name = "Ada"),
                shift("gone", date = "2026-03-04", start = "08:00", end = "16:00", name = "Cara", staffId = "cara", status = "cancelled")
            ),
            staff = staff3,
            now = LocalDateTime.parse("2026-03-04T09:00:00")
        )
        assertEquals(ShiftStats(onRoster = 3, inView = 3, onDutyNow = 1), board.stats)
        assertEquals(listOf("on"), board.onDuty.map { it.id })
        assertEquals(d("2026-03-04"), board.today)
    }

    @Test fun anOvernightShiftFromBeforeTheRangeIsOnDutyButNotInView() {
        val board = buildShiftBoard(
            role = Role.HOUSEKEEPING, mode = ShiftViewMode.WEEK, anchor = d("2026-03-04"),
            shifts = listOf(shift("night", date = "2026-02-28", start = "22:00", end = "06:00", next = true)),
            staff = staff3,
            now = LocalDateTime.parse("2026-03-01T02:00:00")
        )
        assertEquals(0, board.stats.inView)
        assertEquals(1, board.stats.onDutyNow)
    }

    @Test fun onDutyChipsAreOrderedByStartTime() {
        val board = buildShiftBoard(
            role = Role.HOUSEKEEPING, mode = ShiftViewMode.DAY, anchor = d("2026-03-04"),
            shifts = listOf(
                shift("b", date = "2026-03-04", start = "09:00", end = "17:00", name = "Bola", staffId = "bola"),
                shift("a", date = "2026-03-04", start = "07:00", end = "15:00", name = "Ada")
            ),
            staff = staff3,
            now = LocalDateTime.parse("2026-03-04T10:00:00")
        )
        assertEquals(listOf("Ada · 07:00–15:00", "Bola · 09:00–17:00"), board.onDuty.map(::onDutyChipText))
    }

    @Test fun nobodyOnDutyGivesAnEmptyBanner() {
        val board = buildShiftBoard(
            Role.HOUSEKEEPING, ShiftViewMode.DAY, d("2026-03-04"),
            listOf(shift(date = "2026-03-04", start = "08:00", end = "16:00")), staff3,
            LocalDateTime.parse("2026-03-04T16:00:00")
        )
        assertTrue(board.onDuty.isEmpty())
        assertEquals(0, board.stats.onDutyNow)
    }

    @Test fun aBadShiftTimeNeverCrashesTheBoard() {
        val board = buildShiftBoard(
            Role.HOUSEKEEPING, ShiftViewMode.DAY, d("2026-03-04"),
            listOf(shift(date = "2026-03-04", start = "xx", end = "16:00")), staff3,
            LocalDateTime.parse("2026-03-04T10:00:00")
        )
        assertEquals(1, board.stats.inView)
        assertEquals(0, board.stats.onDutyNow)
    }

    // ---- time labels ----

    @Test fun overnightShiftsGetAPlusOneSuffix() {
        assertEquals("22:00–06:00+1", shiftTimeRangeLabel(shift(start = "22:00", end = "06:00", next = true)))
        assertEquals("08:00–16:00", shiftTimeRangeLabel(shift()))
        assertEquals("22:00–06:00", shiftTimeRangeLabel(shift(start = "22:00", end = "06:00", next = false)))
        assertEquals("22:00–06:00", shiftTimeRange(shift(start = "22:00", end = "06:00", next = true)))
    }

    @Test fun rowTitleShowsTheLabelUnlessCompact() {
        assertEquals("Ada · Morning", shiftRowTitle(shift(), compact = false))
        assertEquals("Ada", shiftRowTitle(shift(), compact = true))
    }

    @Test fun bannerChipText() {
        assertEquals("Ada · 08:00–16:00", onDutyChipText(shift()))
    }

    // ---- conflict box ----

    private fun conflicts(n: Int) = (1..n).map { ConflictInfo("2026-03-%02d".format(it), "Morning", "08:00–16:00") }

    @Test fun conflictBoxHasHeadingLinesAndFooter() {
        val box = conflictBox("Ada", conflicts(1))
        assertEquals("Scheduling conflict — Ada is already booked", box.heading)
        assertEquals(listOf("2026-03-01: overlaps \"Morning\" (08:00–16:00)"), box.lines)
        assertNull(box.moreLine)
        assertEquals("Pick a different staff member or time and try again.", box.footer)
    }

    @Test fun conflictBoxShowsAtMostSixLinesAndCountsTheRest() {
        val box = conflictBox("Ada", conflicts(8))
        assertEquals(6, box.lines.size)
        assertEquals("+ 2 more conflicting date(s)", box.moreLine)
        assertNull(conflictBox("Ada", conflicts(6)).moreLine)
        assertEquals(6, conflictBox("Ada", conflicts(6)).lines.size)
        assertEquals("+ 1 more conflicting date(s)", conflictBox("Ada", conflicts(7)).moreLine)
    }

    // ---- toasts ----

    @Test fun scheduledToastMentionsTheCountOnlyForSeries() {
        assertEquals(ShiftToastText(null, "Shift Scheduled"), scheduledToast(1))
        assertEquals(ShiftToastText("Shift Scheduled", "5 shifts created"), scheduledToast(5))
        assertEquals(ShiftToastText("Series Cancelled", "This and every future shift in the series was cancelled."), SERIES_CANCELLED_TOAST)
        assertEquals("Shift Updated", UPDATED_TOAST.message)
        assertEquals("Shift Cancelled", CANCELLED_TOAST.message)
    }

    // ---- staff source ----

    @Test fun onlyActiveStaffAreListedAToZ() {
        val docs = listOf(
            ShiftUserDoc("2", "bola", "housekeeping", "active"),
            ShiftUserDoc("1", "Ada", "housekeeping", "active"),
            ShiftUserDoc("3", "Cara", "housekeeping", "suspended"),
            ShiftUserDoc("4", "Dayo", "housekeeping", "active", isDeleted = true)
        )
        assertEquals(listOf(ShiftStaff("1", "Ada"), ShiftStaff("2", "bola")), activeShiftStaff(docs))
    }
}
