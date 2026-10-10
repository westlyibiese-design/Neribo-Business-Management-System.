package com.westly.nbms.features.tasks

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelsTest {
    @Test fun typeKeysAndLabelsMatchTheSpec() {
        val expected = mapOf(
            "booking" to "Room Booking", "housekeeping" to "Housekeeping", "laundry" to "Laundry",
            "food_order" to "Food Order", "drink_order" to "Drink Order", "maintenance" to "Maintenance",
            "guest_request" to "Guest Request", "security" to "Security", "transport" to "Transport / Driver", "other" to "Other"
        )
        assertEquals(expected, TaskType.entries.associate { it.key to it.label })
    }

    @Test fun suggestedRolesMatchTheSpec() {
        assertEquals(listOf(Role.RECEPTIONIST), TaskType.BOOKING.suggestedRoles)
        assertEquals(listOf(Role.HOUSEKEEPING), TaskType.HOUSEKEEPING.suggestedRoles)
        assertEquals(listOf(Role.LAUNDRY_VALET), TaskType.LAUNDRY.suggestedRoles)
        assertEquals(listOf(Role.WAITER, Role.RESTAURANT_ATTENDANT, Role.STAFF), TaskType.FOOD_ORDER.suggestedRoles)
        assertEquals(listOf(Role.BAR_ATTENDANT), TaskType.DRINK_ORDER.suggestedRoles)
        assertEquals(listOf(Role.MAINTENANCE_TECHNICIAN, Role.HOUSEKEEPING), TaskType.MAINTENANCE.suggestedRoles)
        assertEquals(listOf(Role.RECEPTIONIST, Role.STAFF), TaskType.GUEST_REQUEST.suggestedRoles)
        assertEquals(listOf(Role.SECURITY_GUARD), TaskType.SECURITY.suggestedRoles)
        assertEquals(listOf(Role.DRIVER), TaskType.TRANSPORT.suggestedRoles)
        assertTrue(TaskType.OTHER.suggestedRoles.isEmpty())
    }

    @Test fun fromKeyFallsBackTolerantly() {
        assertEquals(TaskType.OTHER, TaskType.fromKey(null))
        assertEquals(TaskType.OTHER, TaskType.fromKey("nonsense"))
        assertEquals(TaskType.SECURITY, TaskType.fromKey("security"))
        assertEquals(TaskPriority.MEDIUM, TaskPriority.fromKey(null))
        assertEquals(TaskPriority.MEDIUM, TaskPriority.fromKey("???"))
        assertEquals(TaskPriority.URGENT, TaskPriority.fromKey("urgent"))
        assertEquals(TaskStatus.PENDING, TaskStatus.fromKey(null))
        assertEquals(TaskStatus.PENDING, TaskStatus.fromKey("???"))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.fromKey("in_progress"))
    }

    @Test fun priorityAndStatusLabels() {
        assertEquals(listOf("Low", "Medium", "High", "Urgent"), TaskPriority.entries.map { it.label })
        assertEquals(listOf("low", "medium", "high", "urgent"), TaskPriority.entries.map { it.key })
        assertEquals(
            listOf("Pending", "Accepted", "In Progress", "Completed", "Cancelled"),
            TaskStatus.entries.map { it.label }
        )
        assertEquals(listOf("pending", "accepted", "in_progress", "completed", "cancelled"), TaskStatus.entries.map { it.key })
    }

    @Test fun aBareTaskReadsWithSafeDefaults() {
        val t = StaffTask()
        assertEquals(TaskType.OTHER, t.taskType())
        assertEquals(TaskPriority.MEDIUM, t.taskPriority())
        assertEquals(TaskStatus.PENDING, t.taskStatus())
        assertTrue(t.assignedToIds.isEmpty() && t.assignedToNames.isEmpty())
        assertFalse(t.isDeleted)
    }

    @Test fun pillColoursFollowTheSpec() {
        assertEquals(taskPrioritySpec(TaskPriority.LOW).lightBg, taskStatusSpec(TaskStatus.PENDING).lightBg)      // both slate
        assertEquals(taskPrioritySpec(TaskPriority.MEDIUM).lightBg, taskStatusSpec(TaskStatus.ACCEPTED).lightBg)  // both blue
        assertEquals(taskPrioritySpec(TaskPriority.URGENT).lightBg, taskStatusSpec(TaskStatus.CANCELLED).lightBg) // both red
        assertNotEquals(taskPrioritySpec(TaskPriority.HIGH).lightBg, taskPrioritySpec(TaskPriority.URGENT).lightBg)
        assertNotEquals(taskStatusSpec(TaskStatus.IN_PROGRESS).lightBg, taskStatusSpec(TaskStatus.COMPLETED).lightBg)
        TaskStatus.entries.forEach {
            assertNotEquals(taskStatusSpec(it).colors(false).container, taskStatusSpec(it).colors(true).container)
        }
    }
}
