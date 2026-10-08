package com.westly.nbms.core.notify

/** One document of `businesses/{bid}/notifications`. Field names are exactly those stored in Firestore. */
data class AppNotification(
    @com.google.firebase.firestore.DocumentId val id: String = "",
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val severity: String = "info",                 // "info" | "success" | "warning" | "critical"
    val link: String? = null,                      // Westly style, e.g. "/admin/bookings"
    val forRoles: List<String> = emptyList(),      // role keys
    val forUserIds: List<String> = emptyList(),
    val excludeUserId: String? = null,
    val actorId: String? = null,
    val readBy: List<String> = emptyList(),
    val deletedBy: List<String> = emptyList(),
    val createdAt: com.google.firebase.Timestamp? = null
)

enum class NotificationSeverity(val key: String) {
    INFO("info"), SUCCESS("success"), WARNING("warning"), CRITICAL("critical");

    companion object {
        /** Unknown or null keys become [INFO]. */
        fun fromKey(key: String?): NotificationSeverity = entries.firstOrNull { it.key == key } ?: INFO
    }
}

/** Every notification type string. One constant per type written by the Notifier (plus CHECKOUT_OVERDUE, written by Phase 24). */
object NotificationType {
    const val NEW_BOOKING = "new_booking"
    const val BOOKING_CANCELLED = "booking_cancelled"
    const val BOOKING_MODIFIED = "booking_modified"
    const val STAY_EXTENDED = "stay_extended"
    const val BOOKING_APPROVED = "booking_approved"
    const val BOOKING_REJECTED = "booking_rejected"
    const val CHECK_IN = "check_in"
    const val CHECK_OUT = "check_out"
    const val WALK_IN = "walk_in"
    const val NEW_SALE = "new_sale"
    const val PAYMENT_RECEIVED = "payment_received"
    const val PAYMENT_APPROVED = "payment_approved"
    const val REFUND_ISSUED = "refund_issued"
    const val EXPENSE_RECORDED = "expense_recorded"
    const val LOST_FOUND_ITEM = "lost_found_item"
    const val LOST_FOUND_CLAIMED = "lost_found_claimed"
    const val HOUSEKEEPING_TASK = "housekeeping_task"
    const val HOUSEKEEPING_TASK_DONE = "housekeeping_task_done"
    const val ROOM_ASSIGNED = "room_assigned"
    const val ROOM_REASSIGNED = "room_reassigned"
    const val ROOM_ASSIGNMENT_ENDED = "room_assignment_ended"
    const val HOUSEKEEPING_TASK_SCHEDULED = "housekeeping_task_scheduled"
    const val ROOM_STATUS_CHANGE = "room_status_change"
    const val MAINTENANCE_REQUEST = "maintenance_request"
    const val MAINTENANCE_RESOLVED = "maintenance_resolved"
    const val LAUNDRY_REQUEST = "laundry_request"
    const val LAUNDRY_READY = "laundry_ready"
    const val TASK_ASSIGNED = "task_assigned"
    const val TASK_REASSIGNED = "task_reassigned"
    const val TASK_COMPLETED = "task_completed"
    const val SHIFT_ASSIGNED = "shift_assigned"
    const val SHIFT_UPDATED = "shift_updated"
    const val SHIFT_CANCELLED = "shift_cancelled"
    const val LOW_INVENTORY = "low_inventory"
    const val NEW_REVIEW = "new_review"
    const val CONTACT_MESSAGE = "contact_message"
    const val GYM_MEMBERSHIP_REGISTERED = "gym_membership_registered"
    const val GYM_MEMBERSHIP_RENEWED = "gym_membership_renewed"
    const val GYM_MEMBERSHIP_EXPIRING = "gym_membership_expiring"
    const val GYM_MEMBERSHIP_SUSPENDED = "gym_membership_suspended"
    const val GYM_CHECK_IN = "gym_check_in"
    const val GYM_CHECK_OUT = "gym_check_out"
    const val STAFF_ALERT = "staff_alert"
    const val SYSTEM_ALERT = "system_alert"
    /** Written later by Phase 24; the notifications screen only needs it for its icon. */
    const val CHECKOUT_OVERDUE = "checkout_overdue"
}
