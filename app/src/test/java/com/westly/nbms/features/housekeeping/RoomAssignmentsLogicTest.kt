package com.westly.nbms.features.housekeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RoomAssignmentsLogicTest {

    private fun group(id: String, status: String = "active", createdAt: Instant? = null, deleted: Boolean = false, rooms: List<String> = emptyList()) =
        parseAssignmentGroup(id, mapOf("status" to status, "isDeleted" to deleted, "roomNumbers" to rooms)).copy(createdAt = createdAt)

    @Test fun calendarDaysAreReadInUtcSoTheyNeverShift() {
        val midnight = Instant.parse("2026-03-05T00:00:00Z")
        assertEquals(LocalDate.of(2026, 3, 5), calendarDayOf(midnight))
        assertEquals("Mar 5, 2026", calendarDayText(midnight))
        assertEquals("Dec 31, 2026", calendarDayText(Instant.parse("2026-12-31T00:00:00Z")))
        assertEquals("—", calendarDayText(null)); assertNull(calendarDayOf(null))
    }

    @Test fun rangeTextHasEndOrOngoing() {
        val s = Instant.parse("2026-03-05T00:00:00Z"); val e = Instant.parse("2026-04-05T00:00:00Z")
        assertEquals("Mar 5, 2026 – Apr 5, 2026", assignmentRangeText(s, e))
        assertEquals("Mar 5, 2026 · ongoing", assignmentRangeText(s, null))
    }

    @Test fun groupsAreNewestFirstAndDeletedOnesLeftOut() {
        val t1 = Instant.parse("2026-03-01T10:00:00Z"); val t2 = Instant.parse("2026-03-02T10:00:00Z")
        val sorted = sortGroupsNewestFirst(listOf(group("old", createdAt = t1), group("gone", createdAt = t2, deleted = true), group("new", createdAt = t2), group("pending")))
        assertEquals(listOf("pending", "new", "old"), sorted.map { it.id }) // a group still waiting for its server time is the newest
    }

    @Test fun tabsSplitActiveAndEnded() {
        val all = listOf(group("a"), group("b", "ended"), group("c"), group("d", ""))
        assertEquals(listOf("a", "c"), groupsWithStatus(all, "active").map { it.id })
        assertEquals(listOf("b"), groupsWithStatus(all, "ended").map { it.id })
    }

    @Test fun chipsShowTwelveThenMore() {
        val nums = (1..15).map { it.toString() }
        val chips = roomChipsOf(nums)
        assertEquals(12, chips.shown.size); assertEquals(3, chips.more)
        assertEquals(RoomChips(listOf("1", "2"), 0), roomChipsOf(listOf("1", "2")))
        assertEquals(RoomChips(emptyList(), 0), roomChipsOf(emptyList()))
        assertEquals(0, roomChipsOf((1..12).map { it.toString() }).more)
    }

    @Test fun editDatesAreInvalidOnlyWhenEndIsBeforeStartAndNotOngoing() {
        val d = LocalDate.of(2026, 3, 5)
        assertTrue(assignmentDatesInvalid(d, d.minusDays(1), false))
        assertFalse(assignmentDatesInvalid(d, d, false))
        assertFalse(assignmentDatesInvalid(d, d.minusDays(9), true))
    }
}
