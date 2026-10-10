package com.westly.nbms.features.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class MyTasksRulesTest {

    private val t0 = TASK_NOW
    private fun ago(h: Long) = t0.minus(Duration.ofHours(h))
    private val today = LocalDate.of(2026, 10, 9)

    // ---- active tasks ----

    @Test fun activeTasksAreNotFinishedNotDeletedAndOldestFirst() {
        val result = MyTasksRules.activeTasks(
            listOf(
                taskOf("new", status = TaskStatus.PENDING, createdAt = ago(1)),
                taskOf("old", status = TaskStatus.IN_PROGRESS, createdAt = ago(10)),
                taskOf("mid", status = TaskStatus.ACCEPTED, createdAt = ago(5)),
                taskOf("done", status = TaskStatus.COMPLETED, createdAt = ago(20)),
                taskOf("gone", status = TaskStatus.CANCELLED, createdAt = ago(30)),
                taskOf("deleted", status = TaskStatus.PENDING, createdAt = ago(40), deleted = true)
            )
        )
        assertEquals(listOf("old", "mid", "new"), result.map { it.id })
    }

    @Test fun activeTaskWithoutCreatedAtGoesLast() {
        val result = MyTasksRules.activeTasks(listOf(taskOf("fresh"), taskOf("old", createdAt = ago(3))))
        assertEquals(listOf("old", "fresh"), result.map { it.id })
    }

    // ---- recently finished ----

    @Test fun finishedUsesNewestOfCompletedAtOrCreatedAt() {
        val result = MyTasksRules.recentlyFinished(
            listOf(
                taskOf("a", status = TaskStatus.COMPLETED, createdAt = ago(50), completedAt = ago(2)),
                taskOf("b", status = TaskStatus.CANCELLED, createdAt = ago(1)),
                taskOf("c", status = TaskStatus.COMPLETED, createdAt = ago(60), completedAt = ago(30)),
                taskOf("active", status = TaskStatus.PENDING, createdAt = ago(1)),
                taskOf("deleted", status = TaskStatus.COMPLETED, completedAt = ago(0), deleted = true)
            )
        )
        assertEquals(listOf("b", "a", "c"), result.map { it.id })
    }

    @Test fun finishedIsCappedAtTwentyNewestFirst() {
        val many = (1..25).map { taskOf("t$it", status = TaskStatus.COMPLETED, completedAt = ago(it.toLong())) }
        val result = MyTasksRules.recentlyFinished(many)
        assertEquals(20, result.size)
        assertEquals("t1", result.first().id)
        assertEquals("t20", result.last().id)
    }

    // ---- buttons per status ----

    @Test fun buttonsPerStatus() {
        assertEquals(listOf(MyTaskAction.ACCEPT), MyTasksRules.actionsFor(TaskStatus.PENDING))
        assertEquals(listOf(MyTaskAction.START, MyTaskAction.COMPLETE), MyTasksRules.actionsFor(TaskStatus.ACCEPTED))
        assertEquals(listOf(MyTaskAction.COMPLETE), MyTasksRules.actionsFor(TaskStatus.IN_PROGRESS))
        assertTrue(MyTasksRules.actionsFor(TaskStatus.COMPLETED).isEmpty())
        assertTrue(MyTasksRules.actionsFor(TaskStatus.CANCELLED).isEmpty())
    }

    @Test fun staleTapsAreNotAllowed() {
        assertTrue(MyTasksRules.isAllowed(taskOf("a", status = TaskStatus.PENDING), MyTaskAction.ACCEPT))
        assertEquals(false, MyTasksRules.isAllowed(taskOf("a", status = TaskStatus.PENDING), MyTaskAction.COMPLETE))
        assertEquals(false, MyTasksRules.isAllowed(taskOf("a", status = TaskStatus.COMPLETED), MyTaskAction.COMPLETE))
    }

    @Test fun subtitleSingularAndPlural() {
        assertEquals("0 active tasks assigned to you", MyTasksRules.subtitle(0))
        assertEquals("1 active task assigned to you", MyTasksRules.subtitle(1))
        assertEquals("3 active tasks assigned to you", MyTasksRules.subtitle(3))
    }

    // ---- shifts ----

    private fun shift(
        id: String, date: String, start: String = "08:00", staff: String = "me",
        status: String = "scheduled", next: Boolean = false
    ) = MyShiftDoc(id = id, staffId = staff, date = date, startTime = start, endTime = "16:00", label = "Morning", endsNextDay = next, status = status)

    @Test fun upcomingShiftsFilterAndSort() {
        val result = MyTasksRules.upcomingShifts(
            listOf(
                shift("late", "2026-10-11", "14:00"),
                shift("early", "2026-10-11", "06:00"),
                shift("today", "2026-10-09"),
                shift("past", "2026-10-08"),
                shift("other", "2026-10-10", staff = "someone"),
                shift("cancelled", "2026-10-10", status = "cancelled")
            ),
            uid = "me", today = today
        )
        assertEquals(listOf("today", "early", "late"), result.map { it.id })
    }

    @Test fun upcomingShiftsKeepFirstEight() {
        val shifts = (10..24).map { shift("s$it", "2026-10-$it") }.shuffled()
        val result = MyTasksRules.upcomingShifts(shifts, "me", today)
        assertEquals(8, result.size)
        assertEquals("s10", result.first().id)
        assertEquals("s17", result.last().id)
    }

    @Test fun noShiftsMeansEmptyList() {
        assertTrue(MyTasksRules.shiftRows(emptyList(), "me", today).isEmpty())
    }

    @Test fun dayLabelTodayAndWeekday() {
        assertEquals("Today", MyTasksRules.dayLabel("2026-10-09", today))
        assertEquals("Mon, Mar 5", MyTasksRules.dayLabel("2018-03-05", today))
        assertEquals("Sat, Oct 10", MyTasksRules.dayLabel("2026-10-10", today))
        assertEquals("not-a-date", MyTasksRules.dayLabel("not-a-date", today))
    }

    @Test fun timeRangeAddsNextDaySuffix() {
        assertEquals("08:00–16:00", MyTasksRules.timeRange(shift("a", "2026-10-09")))
        assertEquals("08:00–16:00 (+1 day)", MyTasksRules.timeRange(shift("a", "2026-10-09", next = true)))
    }

    @Test fun rowsCombineLabelAndTime() {
        val row = MyTasksRules.shiftRows(listOf(shift("a", "2026-10-09", next = true)), "me", today).single()
        assertEquals("Today", row.dayLabel)
        assertEquals("Morning", row.label)
        assertEquals("08:00–16:00 (+1 day)", row.timeRange)
    }
}
