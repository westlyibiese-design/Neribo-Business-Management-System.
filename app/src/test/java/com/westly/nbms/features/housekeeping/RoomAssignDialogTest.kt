package com.westly.nbms.features.housekeeping

import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.users.models.StaffUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoomAssignDialogTest {
    private val today = LocalDate.of(2026, 3, 5)
    private fun form(
        housekeeperId: String? = "hk1", start: LocalDate = today, end: LocalDate = today.plusMonths(1),
        ongoing: Boolean = false, rooms: Set<String> = setOf("r1")
    ) = RoomAssignForm(housekeeperId, start, end, ongoing, "", rooms, "")

    // ---- validation order ----

    @Test fun validFormPasses() { assertNull(validateRoomAssign(form())) }

    @Test fun housekeeperIsCheckedFirst() {
        // everything is wrong: no housekeeper, no rooms, end before start
        val f = form(housekeeperId = null, rooms = emptySet(), start = today, end = today.minusDays(3))
        assertEquals(RoomAssignIssue.NO_HOUSEKEEPER, validateRoomAssign(f))
        assertEquals(RoomAssignIssue.NO_HOUSEKEEPER, validateRoomAssign(f.copy(housekeeperId = "")))
    }

    @Test fun roomsAreCheckedSecond() {
        val f = form(rooms = emptySet(), end = today.minusDays(3))
        assertEquals(RoomAssignIssue.NO_ROOMS, validateRoomAssign(f))
    }

    @Test fun datesAreCheckedLast() {
        assertEquals(RoomAssignIssue.INVALID_DATES, validateRoomAssign(form(end = today.minusDays(1))))
        assertNull("same day is fine", validateRoomAssign(form(end = today)))
        assertNull("ongoing ignores the end date", validateRoomAssign(form(end = today.minusDays(30), ongoing = true)))
    }

    @Test fun issueTextsAreExact() {
        assertEquals("Select a housekeeper", RoomAssignIssue.NO_HOUSEKEEPER.title); assertNull(RoomAssignIssue.NO_HOUSEKEEPER.message)
        assertEquals("Select at least one room", RoomAssignIssue.NO_ROOMS.title); assertNull(RoomAssignIssue.NO_ROOMS.message)
        assertEquals("Invalid dates", RoomAssignIssue.INVALID_DATES.title)
        assertEquals("End date must be on or after the start date.", RoomAssignIssue.INVALID_DATES.message)
    }

    // ---- quick buttons (calendar maths, no time zone) ----

    @Test fun quickButtonsAddToTheStartDateAndTurnOngoingOff() {
        val f = form(ongoing = true)
        assertEquals(LocalDate.of(2026, 3, 6), f.withQuickEnd(QuickRange.DAY).endDate)
        assertEquals(LocalDate.of(2026, 3, 12), f.withQuickEnd(QuickRange.WEEK).endDate)
        assertEquals(LocalDate.of(2026, 4, 5), f.withQuickEnd(QuickRange.MONTH).endDate)
        QuickRange.entries.forEach { assertFalse(f.withQuickEnd(it).ongoing) }
    }

    @Test fun quickButtonsCrossMonthAndYearEndsAndLeapDays() {
        val dec = form(start = LocalDate.of(2026, 12, 31))
        assertEquals(LocalDate.of(2027, 1, 1), dec.withQuickEnd(QuickRange.DAY).endDate)
        assertEquals(LocalDate.of(2027, 1, 7), dec.withQuickEnd(QuickRange.WEEK).endDate)
        assertEquals(LocalDate.of(2027, 1, 31), dec.withQuickEnd(QuickRange.MONTH).endDate)
        assertEquals(LocalDate.of(2026, 2, 28), form(start = LocalDate.of(2026, 1, 31)).withQuickEnd(QuickRange.MONTH).endDate)
        assertEquals(LocalDate.of(2028, 2, 29), form(start = LocalDate.of(2028, 1, 31)).withQuickEnd(QuickRange.MONTH).endDate)
    }

    @Test fun quickButtonsLeaveEverythingElseAlone() {
        val f = form(rooms = setOf("a", "b")).copy(notes = "hi", search = "10")
        val q = f.withQuickEnd(QuickRange.WEEK)
        assertEquals(f.copy(endDate = q.endDate), q)
    }

    // ---- state reset on open ----

    @Test fun openingStartsFreshWithDefaults() {
        val f = newRoomAssignForm(today, emptyList(), null)
        assertNull(f.housekeeperId); assertEquals(today, f.startDate); assertEquals(LocalDate.of(2026, 4, 5), f.endDate)
        assertFalse(f.ongoing); assertEquals("", f.notes); assertTrue(f.roomIds.isEmpty()); assertEquals("", f.search)
    }

    @Test fun openingAgainDiscardsEarlierEdits() {
        val edited = newRoomAssignForm(today, emptyList(), null).copy(housekeeperId = "x", notes = "typed", ongoing = true, search = "10")
        val reopened = newRoomAssignForm(today, emptyList(), null)
        assertTrue(edited != reopened)
        assertEquals(newRoomAssignForm(today, emptyList(), null), reopened)
    }

    @Test fun preselectionsAndDefaultHousekeeperCarryIn() {
        val f = newRoomAssignForm(today, listOf("r1", "r2", "r1"), "hk9")
        assertEquals("hk9", f.housekeeperId); assertEquals(setOf("r1", "r2"), f.roomIds)
    }

    // ---- room list helpers ----

    private fun room(id: String, number: String, type: String = "Standard Room", deleted: Boolean = false) =
        Room(id = id, number = number, type = type, isDeleted = deleted)

    @Test fun roomsSortNumericallyAndSkipDeleted() {
        val sorted = sortRoomsForAssign(listOf(room("a", "10"), room("b", "2"), room("c", "101"), room("d", "1", deleted = true), room("e", "Penthouse"), room("f", "9")))
        assertEquals(listOf("2", "9", "10", "101", "Penthouse"), sorted.map { it.number })
    }

    @Test fun roomSearchMatchesNumberOrType() {
        val rooms = listOf(room("a", "101", "Deluxe Room"), room("b", "102", "Junior Suite"), room("c", "201", "Deluxe Room"))
        assertEquals(listOf("101", "201"), filterRoomsForAssign(rooms, " deluxe ").map { it.number })
        assertEquals(listOf("101", "102"), filterRoomsForAssign(rooms, "10").map { it.number })
        assertEquals(3, filterRoomsForAssign(rooms, "").size)
        assertTrue(filterRoomsForAssign(rooms, "zzz").isEmpty())
    }

    @Test fun housekeeperListIsActiveHousekeepingStaffAToZ() {
        val users = listOf(
            StaffUser(id = "1", name = "zed", role = "housekeeping"),
            StaffUser(id = "2", name = "Amy", role = "housekeeping"),
            StaffUser(id = "3", name = "Bob", role = "receptionist"),
            StaffUser(id = "4", name = "Cy", role = "housekeeping", status = "suspended")
        )
        assertEquals(listOf("Amy", "zed"), housekeepersOf(users).map { it.name })
    }
}
