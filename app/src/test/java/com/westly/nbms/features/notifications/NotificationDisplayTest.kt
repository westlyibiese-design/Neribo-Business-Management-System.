package com.westly.nbms.features.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AssignmentLate
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Warning
import com.google.firebase.Timestamp
import com.westly.nbms.core.notify.NotificationType
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NotificationDisplayTest {

    @Test fun icons_follow_the_type() {
        assertSame(Icons.Outlined.CalendarMonth, notificationIcon(NotificationType.NEW_BOOKING))
        assertSame(Icons.AutoMirrored.Outlined.Login, notificationIcon(NotificationType.CHECK_IN))
        assertSame(Icons.AutoMirrored.Outlined.Logout, notificationIcon(NotificationType.CHECK_OUT))
        assertSame(Icons.Outlined.Payments, notificationIcon(NotificationType.PAYMENT_RECEIVED))
        assertSame(Icons.Outlined.Build, notificationIcon(NotificationType.MAINTENANCE_REQUEST))
        assertSame(Icons.Outlined.Checklist, notificationIcon(NotificationType.TASK_ASSIGNED))
        assertSame(Icons.Outlined.AssignmentTurnedIn, notificationIcon(NotificationType.TASK_COMPLETED))
        assertSame(Icons.Outlined.AssignmentLate, notificationIcon(NotificationType.TASK_REASSIGNED))
    }

    @Test fun overdue_and_low_stock_use_the_warning_icon() {
        assertSame(Icons.Outlined.Warning, notificationIcon(NotificationType.CHECKOUT_OVERDUE))
        assertSame(Icons.Outlined.Warning, notificationIcon(NotificationType.LOW_INVENTORY))
    }

    @Test fun every_gym_type_uses_the_gym_icon() {
        listOf(
            NotificationType.GYM_MEMBERSHIP_REGISTERED, NotificationType.GYM_MEMBERSHIP_RENEWED,
            NotificationType.GYM_MEMBERSHIP_EXPIRING, NotificationType.GYM_MEMBERSHIP_SUSPENDED,
            NotificationType.GYM_CHECK_IN, NotificationType.GYM_CHECK_OUT
        ).forEach { assertSame(it, Icons.Outlined.FitnessCenter, notificationIcon(it)) }
    }

    @Test fun unknown_type_gets_the_plain_bell() {
        assertSame(Icons.Outlined.Notifications, notificationIcon("something_new"))
        assertSame(Icons.Outlined.Notifications, notificationIcon(""))
    }

    @Test fun severity_dot_colours() {
        assertEquals(0xFF3B82F6, severityColorArgb("info"))
        assertEquals(0xFF10B981, severityColorArgb("success"))
        assertEquals(0xFFF59E0B, severityColorArgb("warning"))
        assertEquals(0xFFEF4444, severityColorArgb("critical"))
    }

    @Test fun unknown_or_missing_severity_is_info_blue() {
        assertEquals(0xFF3B82F6, severityColorArgb("loud"))
        assertEquals(0xFF3B82F6, severityColorArgb(null))
    }

    @Test fun time_ago_reads_naturally() {
        val now = Instant.fromEpochSeconds(1_000_000)
        assertEquals("just now", timeAgo(Timestamp(1_000_000 - 20, 0), now))
        assertEquals("5m ago", timeAgo(Timestamp(1_000_000 - 300, 0), now))
        assertEquals("2h ago", timeAgo(Timestamp(1_000_000 - 7_200, 0), now))
        assertEquals("3d ago", timeAgo(Timestamp(1_000_000 - 3 * 86_400L, 0), now))
    }

    @Test fun notification_without_server_time_says_just_now() {
        assertEquals("just now", timeAgo(null))
    }

    @Test fun badge_shows_99_plus_above_99() {
        assertEquals("1", badgeLabel(1))
        assertEquals("99", badgeLabel(99))
        assertEquals("99+", badgeLabel(100))
        assertEquals("99+", badgeLabel(250))
    }

    @Test fun bell_description_and_subtitle() {
        assertEquals("Notifications", bellDescription(0))
        assertEquals("3 unread notifications", bellDescription(3))
        assertEquals("You're all caught up.", unreadSubtitle(0))
        assertEquals("4 unread", unreadSubtitle(4))
    }
}
