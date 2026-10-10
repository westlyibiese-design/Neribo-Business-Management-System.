package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.rbac.Role

/** What kind of job a task is. Only the type's [suggestedRoles] pre-filter the staff picker. */
enum class TaskType(val key: String, val label: String, val suggestedRoles: List<Role>) {
    BOOKING("booking", "Room Booking", listOf(Role.RECEPTIONIST)),
    HOUSEKEEPING("housekeeping", "Housekeeping", listOf(Role.HOUSEKEEPING)),
    LAUNDRY("laundry", "Laundry", listOf(Role.LAUNDRY_VALET)),
    FOOD_ORDER("food_order", "Food Order", listOf(Role.WAITER, Role.RESTAURANT_ATTENDANT, Role.STAFF)),
    DRINK_ORDER("drink_order", "Drink Order", listOf(Role.BAR_ATTENDANT)),
    MAINTENANCE("maintenance", "Maintenance", listOf(Role.MAINTENANCE_TECHNICIAN, Role.HOUSEKEEPING)),
    GUEST_REQUEST("guest_request", "Guest Request", listOf(Role.RECEPTIONIST, Role.STAFF)),
    SECURITY("security", "Security", listOf(Role.SECURITY_GUARD)),
    TRANSPORT("transport", "Transport / Driver", listOf(Role.DRIVER)),
    OTHER("other", "Other", emptyList());

    companion object {
        /** Unknown or missing keys become [OTHER]. */
        fun fromKey(key: String?): TaskType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

enum class TaskPriority(val key: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    URGENT("urgent", "Urgent");

    companion object {
        /** Unknown or missing keys become [MEDIUM]. */
        fun fromKey(key: String?): TaskPriority = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

enum class TaskStatus(val key: String, val label: String) {
    PENDING("pending", "Pending"),
    ACCEPTED("accepted", "Accepted"),
    IN_PROGRESS("in_progress", "In Progress"),
    COMPLETED("completed", "Completed"),
    CANCELLED("cancelled", "Cancelled");

    companion object {
        /** Unknown or missing keys become [PENDING]. */
        fun fromKey(key: String?): TaskStatus = entries.firstOrNull { it.key == key } ?: PENDING
    }
}

/** `businesses/{bid}/tasks/{id}`. Every field has a default so older or partial documents still read. */
data class StaffTask(
    @DocumentId val id: String = "",
    val title: String = "",
    /** A [TaskType] key. */
    val type: String = TaskType.OTHER.key,
    val description: String? = null,
    /** A [TaskPriority] key. */
    val priority: String = TaskPriority.MEDIUM.key,
    val assignedToIds: List<String> = emptyList(),
    val assignedToNames: List<String> = emptyList(),
    val assignedBy: String = "",
    val assignedByName: String = "",
    val dueAt: Timestamp? = null,
    /** A [TaskStatus] key. */
    val status: String = TaskStatus.PENDING.key,
    val relatedCollection: String? = null,
    val relatedId: String? = null,
    val relatedLabel: String? = null,
    val acceptedBy: String? = null,
    val acceptedByName: String? = null,
    val acceptedAt: Timestamp? = null,
    val completedAt: Timestamp? = null,
    val reassignedAt: Timestamp? = null,
    val reassignedBy: String? = null,
    val reassignedByName: String? = null,
    val updatedAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    // Firestore would call this property "deleted" (Java bean rule for Boolean "is…" getters);
    // the annotation keeps the stored field name "isDeleted" that every phase uses.
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

// These are extensions (not members) so Firestore never tries to store them as fields.
fun StaffTask.taskType(): TaskType = TaskType.fromKey(type)
fun StaffTask.taskPriority(): TaskPriority = TaskPriority.fromKey(priority)
fun StaffTask.taskStatus(): TaskStatus = TaskStatus.fromKey(status)

/** An active staff member from the `users` mirror, as the assign picker needs them. */
data class TaskStaffUser(val id: String, val name: String, val role: String)

/** Read-only shape of a `users/{uid}` mirror document (the app never writes it). */
data class TaskUserDoc(
    @DocumentId val id: String = "",
    val name: String = "",
    val role: String = "",
    val status: String = "active",
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

enum class TaskStatusFilter { ACTIVE, ALL, PENDING, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED }

data class TaskStats(val active: Int, val overdue: Int, val completedToday: Int)
