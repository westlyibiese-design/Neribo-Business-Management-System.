package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.westly.nbms.core.rbac.Role
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Pure task rules shared by every tasks page and the assign dialog. */
object TaskRules {

    /** Roles that manage tasks; they are never offered as assignees. */
    internal val NON_ASSIGNABLE_ROLE_KEYS: Set<String> = setOf(
        Role.SUPER_ADMIN.key, Role.MANAGER.key, Role.OPERATIONS_MANAGER.key, Role.ACCOUNTANT.key
    )

    internal fun Timestamp.toJavaInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

    fun isActive(task: StaffTask): Boolean {
        val s = task.taskStatus()
        return s != TaskStatus.COMPLETED && s != TaskStatus.CANCELLED
    }

    /** Has a due time in the past and is not completed or cancelled. */
    fun isOverdue(task: StaffTask, now: Instant): Boolean {
        val due = task.dueAt ?: return false
        return isActive(task) && due.toJavaInstant().isBefore(now)
    }

    fun visibleTasks(tasks: List<StaffTask>): List<StaffTask> = tasks.filter { !it.isDeleted }

    fun stats(tasks: List<StaffTask>, now: Instant, zone: ZoneId): TaskStats {
        val visible = visibleTasks(tasks)
        val today = now.atZone(zone).toLocalDate()
        return TaskStats(
            active = visible.count { isActive(it) },
            overdue = visible.count { isOverdue(it, now) },
            completedToday = visible.count {
                it.taskStatus() == TaskStatus.COMPLETED &&
                    it.completedAt?.toJavaInstant()?.atZone(zone)?.toLocalDate() == today
            }
        )
    }

    /** [search] matches the title or any assigned name, ignoring case. [type] null means every type. */
    fun filter(tasks: List<StaffTask>, search: String, status: TaskStatusFilter, type: TaskType?): List<StaffTask> {
        val q = search.trim().lowercase()
        return tasks.filter { t ->
            val statusOk = when (status) {
                TaskStatusFilter.ALL -> true
                TaskStatusFilter.ACTIVE -> isActive(t)
                TaskStatusFilter.PENDING -> t.taskStatus() == TaskStatus.PENDING
                TaskStatusFilter.ACCEPTED -> t.taskStatus() == TaskStatus.ACCEPTED
                TaskStatusFilter.IN_PROGRESS -> t.taskStatus() == TaskStatus.IN_PROGRESS
                TaskStatusFilter.COMPLETED -> t.taskStatus() == TaskStatus.COMPLETED
                TaskStatusFilter.CANCELLED -> t.taskStatus() == TaskStatus.CANCELLED
            }
            val typeOk = type == null || t.taskType() == type
            val searchOk = q.isEmpty() ||
                t.title.lowercase().contains(q) ||
                t.assignedToNames.any { it.lowercase().contains(q) }
            statusOk && typeOk && searchOk
        }
    }

    /** Overdue tasks first, then newest first (a task with no createdAt counts as oldest). */
    fun orderForAssignment(tasks: List<StaffTask>, now: Instant): List<StaffTask> =
        tasks.sortedWith(
            compareBy<StaffTask> { if (isOverdue(it, now)) 0 else 1 }
                .thenByDescending { it.createdAt?.toJavaInstant() ?: Instant.MIN }
        )

    /** pending→accepted|cancelled, accepted→in_progress|completed|cancelled, in_progress→completed|cancelled; completed and cancelled are final. */
    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = when (from) {
        TaskStatus.PENDING -> to == TaskStatus.ACCEPTED || to == TaskStatus.CANCELLED
        TaskStatus.ACCEPTED -> to == TaskStatus.IN_PROGRESS || to == TaskStatus.COMPLETED || to == TaskStatus.CANCELLED
        TaskStatus.IN_PROGRESS -> to == TaskStatus.COMPLETED || to == TaskStatus.CANCELLED
        TaskStatus.COMPLETED, TaskStatus.CANCELLED -> false
    }

    /**
     * Staff who may be given a task: managers, accountants and the Super Admin are left out.
     * With [suggestedOnly] and a type that has suggested roles, only those roles stay. [search] filters by name. A to Z.
     */
    fun assignablePool(users: List<TaskStaffUser>, type: TaskType, suggestedOnly: Boolean, search: String): List<TaskStaffUser> {
        val q = search.trim().lowercase()
        val suggestedKeys = type.suggestedRoles.map { it.key }.toSet()
        return users
            .filter { it.role !in NON_ASSIGNABLE_ROLE_KEYS }
            .filter { !suggestedOnly || suggestedKeys.isEmpty() || it.role in suggestedKeys }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedBy { it.name.lowercase() }
    }

    /** Today's date at [time] (seconds 0) in [zone]; null when no time was chosen. */
    fun dueAtFromTime(time: LocalTime?, today: LocalDate, zone: ZoneId): Instant? =
        time?.let { today.atTime(it.withSecond(0).withNano(0)).atZone(zone).toInstant() }
}
