package com.westly.nbms.features.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AssignmentLate
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.firebase.Timestamp
import com.westly.nbms.core.notify.NotificationSeverity
import com.westly.nbms.core.notify.NotificationType
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** The round icon of a notification row, chosen by its type string. Unknown types get the plain bell. */
fun notificationIcon(type: String): ImageVector = when (type) {
    NotificationType.NEW_BOOKING, NotificationType.BOOKING_APPROVED -> Icons.Outlined.CalendarMonth
    NotificationType.BOOKING_CANCELLED, NotificationType.BOOKING_REJECTED -> Icons.Outlined.EventBusy
    NotificationType.BOOKING_MODIFIED, NotificationType.STAY_EXTENDED -> Icons.Outlined.EventRepeat
    NotificationType.CHECK_IN -> Icons.AutoMirrored.Outlined.Login
    NotificationType.CHECK_OUT -> Icons.AutoMirrored.Outlined.Logout
    NotificationType.WALK_IN -> Icons.Outlined.MeetingRoom
    NotificationType.PAYMENT_RECEIVED -> Icons.Outlined.Payments
    NotificationType.PAYMENT_APPROVED -> Icons.Outlined.Receipt
    NotificationType.NEW_SALE -> Icons.Outlined.ReceiptLong
    NotificationType.REFUND_ISSUED -> Icons.AutoMirrored.Outlined.Undo
    NotificationType.EXPENSE_RECORDED -> Icons.Outlined.Receipt
    NotificationType.LOST_FOUND_ITEM -> Icons.Outlined.Inventory
    NotificationType.LOST_FOUND_CLAIMED -> Icons.Outlined.Inventory2
    NotificationType.HOUSEKEEPING_TASK,
    NotificationType.HOUSEKEEPING_TASK_DONE,
    NotificationType.HOUSEKEEPING_TASK_SCHEDULED -> Icons.Outlined.CleaningServices
    NotificationType.MAINTENANCE_REQUEST -> Icons.Outlined.Build
    NotificationType.MAINTENANCE_RESOLVED -> Icons.Outlined.VerifiedUser
    NotificationType.LAUNDRY_REQUEST, NotificationType.LAUNDRY_READY -> Icons.Outlined.LocalLaundryService
    NotificationType.LOW_INVENTORY, NotificationType.CHECKOUT_OVERDUE -> Icons.Outlined.Warning
    NotificationType.STAFF_ALERT -> Icons.Outlined.Shield
    NotificationType.NEW_REVIEW -> Icons.Outlined.Star
    NotificationType.CONTACT_MESSAGE -> Icons.Outlined.Mail
    NotificationType.SYSTEM_ALERT -> Icons.Outlined.Campaign
    NotificationType.TASK_ASSIGNED -> Icons.Outlined.Checklist
    NotificationType.TASK_COMPLETED -> Icons.Outlined.AssignmentTurnedIn
    NotificationType.TASK_REASSIGNED -> Icons.Outlined.AssignmentLate
    NotificationType.ROOM_ASSIGNED,
    NotificationType.ROOM_REASSIGNED,
    NotificationType.ROOM_STATUS_CHANGE -> Icons.Outlined.MeetingRoom
    NotificationType.ROOM_ASSIGNMENT_ENDED -> Icons.AutoMirrored.Outlined.Undo
    NotificationType.SHIFT_ASSIGNED -> Icons.Outlined.Event
    NotificationType.SHIFT_UPDATED -> Icons.Outlined.EventRepeat
    NotificationType.SHIFT_CANCELLED -> Icons.Outlined.EventBusy
    else -> if (type.startsWith("gym_")) Icons.Outlined.FitnessCenter else Icons.Outlined.Notifications
}

/** The small dot on a row's icon: info blue, success green, warning amber, critical red. */
fun severityColorArgb(severity: String?): Long = when (NotificationSeverity.fromKey(severity)) {
    NotificationSeverity.INFO -> 0xFF3B82F6
    NotificationSeverity.SUCCESS -> 0xFF10B981
    NotificationSeverity.WARNING -> 0xFFF59E0B
    NotificationSeverity.CRITICAL -> 0xFFEF4444
}

fun severityColor(severity: String?): Color = Color(severityColorArgb(severity))

/** "5m ago". A notification with no server time yet was just written, so it says "just now". */
fun timeAgo(createdAt: Timestamp?, now: Instant = Clock.System.now()): String =
    createdAt?.let { Format.relative(it.toInstant(), now) } ?: "just now"

/** The red badge text: the number, or "99+" above 99. */
fun badgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

/** Spoken label of the bell button. */
fun bellDescription(count: Int): String = if (count > 0) "$count unread notifications" else "Notifications"

/** Header line under "Notifications". */
fun unreadSubtitle(count: Int): String = if (count > 0) "$count unread" else "You're all caught up."
