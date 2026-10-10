package com.westly.nbms.features.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskRulesTest {
    private val now = TASK_NOW
    private val past = now.minusSeconds(3600)
    private val future = now.plusSeconds(3600)
    private val lagos = ZoneId.of("Africa/Lagos")

    // ---- overdue / active ----

    @Test fun overdueNeedsADueTimeInThePastAndAnOpenTask() {
        assertTrue(TaskRules.isOverdue(taskOf("a", dueAt = past), now))
        assertTrue(TaskRules.isOverdue(taskOf("a", dueAt = past, status = TaskStatus.IN_PROGRESS), now))
        assertFalse("future due", TaskRules.isOverdue(taskOf("a", dueAt = future), now))
        assertFalse("no due", TaskRules.isOverdue(taskOf("a"), now))
        assertFalse("due exactly now is not yet overdue", TaskRules.isOverdue(taskOf("a", dueAt = now), now))
        assertFalse(TaskRules.isOverdue(taskOf("a", dueAt = past, status = TaskStatus.COMPLETED), now))
        assertFalse(TaskRules.isOverdue(taskOf("a", dueAt = past, status = TaskStatus.CANCELLED), now))
    }

    @Test fun activeMeansNotCompletedAndNotCancelled() {
        assertTrue(TaskRules.isActive(taskOf("a", status = TaskStatus.PENDING)))
        assertTrue(TaskRules.isActive(taskOf("a", status = TaskStatus.ACCEPTED)))
        assertTrue(TaskRules.isActive(taskOf("a", status = TaskStatus.IN_PROGRESS)))
        assertFalse(TaskRules.isActive(taskOf("a", status = TaskStatus.COMPLETED)))
        assertFalse(TaskRules.isActive(taskOf("a", status = TaskStatus.CANCELLED)))
    }

    @Test fun visibleTasksDropsDeleted() {
        val list = listOf(taskOf("a"), taskOf("b", deleted = true))
        assertEquals(listOf("a"), TaskRules.visibleTasks(list).map { it.id })
    }

    // ---- ordering ----

    @Test fun overdueFirstThenNewestFirst() {
        val list = listOf(
            taskOf("old", createdAt = now.minusSeconds(900)),
            taskOf("new", createdAt = now.minusSeconds(100)),
            taskOf("lateOld", dueAt = past, createdAt = now.minusSeconds(800)),
            taskOf("lateNew", dueAt = past, createdAt = now.minusSeconds(50)),
            taskOf("noDate")
        )
        assertEquals(listOf("lateNew", "lateOld", "new", "old", "noDate"), TaskRules.orderForAssignment(list, now).map { it.id })
    }

    // ---- stats ----

    @Test fun statsCountActiveOverdueAndCompletedToday() {
        val list = listOf(
            taskOf("p", dueAt = past),
            taskOf("q", status = TaskStatus.IN_PROGRESS),
            taskOf("done1", status = TaskStatus.COMPLETED, completedAt = now.minusSeconds(600)),
            taskOf("doneYesterday", status = TaskStatus.COMPLETED, completedAt = now.minusSeconds(86_400 * 2L)),
            taskOf("x", status = TaskStatus.CANCELLED),
            taskOf("deleted", dueAt = past, deleted = true),
            taskOf("deletedDone", status = TaskStatus.COMPLETED, completedAt = now.minusSeconds(60), deleted = true)
        )
        assertEquals(TaskStats(active = 2, overdue = 1, completedToday = 1), TaskRules.stats(list, now, lagos))
    }

    @Test fun completedTodayUsesTheBusinessZoneDate() {
        // 23:30 Lagos on 9 Oct is 22:30Z; "now" is 00:30 Lagos on 10 Oct (23:30Z on 9 Oct).
        val justAfterMidnight = Instant.parse("2026-10-09T23:30:00Z")
        val doneBeforeMidnight = Instant.parse("2026-10-09T22:30:00Z")
        val list = listOf(taskOf("d", status = TaskStatus.COMPLETED, completedAt = doneBeforeMidnight))
        assertEquals(0, TaskRules.stats(list, justAfterMidnight, lagos).completedToday)
        assertEquals(1, TaskRules.stats(list, justAfterMidnight, ZoneId.of("UTC")).completedToday)
    }

    @Test fun completedWithoutACompletedAtIsNotCountedToday() {
        assertEquals(0, TaskRules.stats(listOf(taskOf("d", status = TaskStatus.COMPLETED)), now, lagos).completedToday)
    }

    // ---- filters ----

    private val sample = listOf(
        taskOf("1", title = "Clean Room 204", type = TaskType.HOUSEKEEPING, status = TaskStatus.PENDING, names = listOf("Ada Obi")),
        taskOf("2", title = "Fix tap", type = TaskType.MAINTENANCE, status = TaskStatus.IN_PROGRESS, names = listOf("Bola Ade", "Chidi")),
        taskOf("3", title = "Airport pickup", type = TaskType.TRANSPORT, status = TaskStatus.COMPLETED, names = listOf("Dayo")),
        taskOf("4", title = "Wash sheets", type = TaskType.LAUNDRY, status = TaskStatus.CANCELLED, names = listOf("Ada Obi")),
        taskOf("5", title = "Greet VIP", type = TaskType.GUEST_REQUEST, status = TaskStatus.ACCEPTED, names = listOf("Femi"))
    )

    private fun ids(list: List<StaffTask>) = list.map { it.id }

    @Test fun activeFilterHidesCompletedAndCancelled() {
        assertEquals(listOf("1", "2", "5"), ids(TaskRules.filter(sample, "", TaskStatusFilter.ACTIVE, null)))
    }

    @Test fun allFilterKeepsEverything() {
        assertEquals(listOf("1", "2", "3", "4", "5"), ids(TaskRules.filter(sample, "", TaskStatusFilter.ALL, null)))
    }

    @Test fun eachStatusFilterKeepsOnlyThatStatus() {
        assertEquals(listOf("1"), ids(TaskRules.filter(sample, "", TaskStatusFilter.PENDING, null)))
        assertEquals(listOf("5"), ids(TaskRules.filter(sample, "", TaskStatusFilter.ACCEPTED, null)))
        assertEquals(listOf("2"), ids(TaskRules.filter(sample, "", TaskStatusFilter.IN_PROGRESS, null)))
        assertEquals(listOf("3"), ids(TaskRules.filter(sample, "", TaskStatusFilter.COMPLETED, null)))
        assertEquals(listOf("4"), ids(TaskRules.filter(sample, "", TaskStatusFilter.CANCELLED, null)))
    }

    @Test fun typeFilterKeepsOnlyThatType() {
        assertEquals(listOf("2"), ids(TaskRules.filter(sample, "", TaskStatusFilter.ALL, TaskType.MAINTENANCE)))
        assertEquals(emptyList<String>(), ids(TaskRules.filter(sample, "", TaskStatusFilter.ALL, TaskType.SECURITY)))
    }

    @Test fun searchMatchesTitleOrAnyAssignedNameIgnoringCase() {
        assertEquals(listOf("1"), ids(TaskRules.filter(sample, "ROOM 204", TaskStatusFilter.ALL, null)))
        assertEquals(listOf("2"), ids(TaskRules.filter(sample, "chidi", TaskStatusFilter.ALL, null)))
        assertEquals(listOf("1", "4"), ids(TaskRules.filter(sample, "  ada obi ", TaskStatusFilter.ALL, null)))
        assertEquals(emptyList<String>(), ids(TaskRules.filter(sample, "zzz", TaskStatusFilter.ALL, null)))
    }

    @Test fun filtersCombine() {
        assertEquals(listOf("1"), ids(TaskRules.filter(sample, "ada", TaskStatusFilter.ACTIVE, TaskType.HOUSEKEEPING)))
        assertEquals(emptyList<String>(), ids(TaskRules.filter(sample, "ada", TaskStatusFilter.ACTIVE, TaskType.LAUNDRY)))
    }

    // ---- transitions ----

    @Test fun validTransitions() {
        assertTrue(TaskRules.canTransition(TaskStatus.PENDING, TaskStatus.ACCEPTED))
        assertTrue(TaskRules.canTransition(TaskStatus.PENDING, TaskStatus.CANCELLED))
        assertTrue(TaskRules.canTransition(TaskStatus.ACCEPTED, TaskStatus.IN_PROGRESS))
        assertTrue(TaskRules.canTransition(TaskStatus.ACCEPTED, TaskStatus.COMPLETED))
        assertTrue(TaskRules.canTransition(TaskStatus.ACCEPTED, TaskStatus.CANCELLED))
        assertTrue(TaskRules.canTransition(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED))
        assertTrue(TaskRules.canTransition(TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED))
    }

    @Test fun everyOtherTransitionIsRefused() {
        val allowed = setOf(
            TaskStatus.PENDING to TaskStatus.ACCEPTED, TaskStatus.PENDING to TaskStatus.CANCELLED,
            TaskStatus.ACCEPTED to TaskStatus.IN_PROGRESS, TaskStatus.ACCEPTED to TaskStatus.COMPLETED,
            TaskStatus.ACCEPTED to TaskStatus.CANCELLED,
            TaskStatus.IN_PROGRESS to TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS to TaskStatus.CANCELLED
        )
        for (from in TaskStatus.entries) for (to in TaskStatus.entries) {
            assertEquals("$from -> $to", (from to to) in allowed, TaskRules.canTransition(from, to))
        }
    }

    @Test fun completedAndCancelledAreFinal() {
        TaskStatus.entries.forEach {
            assertFalse(TaskRules.canTransition(TaskStatus.COMPLETED, it))
            assertFalse(TaskRules.canTransition(TaskStatus.CANCELLED, it))
        }
    }

    // ---- assignable pool ----

    private val staff = listOf(
        TaskStaffUser("1", "Zainab", "receptionist"),
        TaskStaffUser("2", "ada", "housekeeping"),
        TaskStaffUser("3", "Bola", "waiter"),
        TaskStaffUser("4", "Chidi", "bar_attendant"),
        TaskStaffUser("5", "Boss", "super_admin"),
        TaskStaffUser("6", "Mgr", "manager"),
        TaskStaffUser("7", "Ops", "operations_manager"),
        TaskStaffUser("8", "Acc", "accountant"),
        TaskStaffUser("9", "Dayo", "staff"),
        TaskStaffUser("10", "Emeka", "maintenance_technician")
    )

    private fun names(list: List<TaskStaffUser>) = list.map { it.name }

    @Test fun poolNeverContainsManagementRoles() {
        val all = TaskRules.assignablePool(staff, TaskType.OTHER, suggestedOnly = false, search = "")
        assertEquals(listOf("ada", "Bola", "Chidi", "Dayo", "Emeka", "Zainab"), names(all))
        val suggestedOther = TaskRules.assignablePool(staff, TaskType.OTHER, suggestedOnly = true, search = "")
        assertEquals("a type with no suggested roles shows everyone", names(all), names(suggestedOther))
    }

    @Test fun suggestedKeepsOnlyTheTypesRoles() {
        assertEquals(listOf("Zainab"), names(TaskRules.assignablePool(staff, TaskType.BOOKING, true, "")))
        assertEquals(listOf("Bola", "Dayo"), names(TaskRules.assignablePool(staff, TaskType.FOOD_ORDER, true, "")))
        assertEquals(listOf("ada", "Emeka"), names(TaskRules.assignablePool(staff, TaskType.MAINTENANCE, true, "")))
        assertEquals(listOf("Chidi"), names(TaskRules.assignablePool(staff, TaskType.DRINK_ORDER, true, "")))
        assertEquals(emptyList<String>(), names(TaskRules.assignablePool(staff, TaskType.SECURITY, true, "")))
    }

    @Test fun allStaffIgnoresSuggestions() {
        assertEquals(6, TaskRules.assignablePool(staff, TaskType.BOOKING, false, "").size)
    }

    @Test fun poolSearchFiltersByNameIgnoringCase() {
        assertEquals(listOf("Bola"), names(TaskRules.assignablePool(staff, TaskType.OTHER, false, " BOL ")))
        assertEquals(listOf("ada", "Bola", "Dayo", "Emeka", "Zainab"), names(TaskRules.assignablePool(staff, TaskType.OTHER, false, "a")))
    }

    @Test fun poolIsSortedByNameIgnoringCase() {
        val sorted = names(TaskRules.assignablePool(staff, TaskType.OTHER, false, ""))
        assertEquals(sorted.sortedBy { it.lowercase() }, sorted)
    }

    // ---- due time ----

    @Test fun dueTimeIsTodayAtThatTimeInTheZone() {
        val due = TaskRules.dueAtFromTime(LocalTime.of(14, 30), LocalDate.of(2026, 10, 9), lagos)
        assertEquals(Instant.parse("2026-10-09T13:30:00Z"), due)
    }

    @Test fun dueTimeDropsSecondsAndNanos() {
        val due = TaskRules.dueAtFromTime(LocalTime.of(9, 5, 42, 999), LocalDate.of(2026, 10, 9), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-10-09T09:05:00Z"), due)
    }

    @Test fun noTimeMeansNoDueDate() {
        assertNull(TaskRules.dueAtFromTime(null, LocalDate.of(2026, 10, 9), lagos))
    }

    @Test fun dueTimeFollowsTheZoneOffset() {
        val utc = TaskRules.dueAtFromTime(LocalTime.NOON, LocalDate.of(2026, 10, 9), ZoneId.of("UTC"))!!
        val lag = TaskRules.dueAtFromTime(LocalTime.NOON, LocalDate.of(2026, 10, 9), lagos)!!
        assertEquals(3600L, utc.epochSecond - lag.epochSecond)
    }
}
