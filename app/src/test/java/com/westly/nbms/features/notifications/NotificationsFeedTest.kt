package com.westly.nbms.features.notifications

import com.google.firebase.Timestamp
import com.westly.nbms.core.notify.AppNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsFeedTest {

    private fun n(
        id: String,
        seconds: Long? = 100,
        readBy: List<String> = emptyList(),
        deletedBy: List<String> = emptyList(),
        excludeUserId: String? = null,
        forUserIds: List<String> = emptyList()
    ) = AppNotification(
        id = id,
        title = id,
        createdAt = seconds?.let { Timestamp(it, 0) },
        readBy = readBy,
        deletedBy = deletedBy,
        excludeUserId = excludeUserId,
        forUserIds = forUserIds
    )

    @Test fun merge_removes_duplicates_by_id() {
        val merged = mergeFeed(listOf(n("a"), n("b", 90)), listOf(n("a"), n("c", 80)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test fun merge_sorts_newest_first() {
        val merged = mergeFeed(listOf(n("old", 10), n("new", 300)), listOf(n("mid", 200)))
        assertEquals(listOf("new", "mid", "old"), merged.map { it.id })
    }

    @Test fun merge_puts_notifications_without_server_time_first() {
        val merged = mergeFeed(listOf(n("saved", 500), n("pending", null)))
        assertEquals(listOf("pending", "saved"), merged.map { it.id })
    }

    @Test fun merge_of_nothing_is_empty() {
        assertTrue(mergeFeed(emptyList(), emptyList()).isEmpty())
    }

    @Test fun removed_for_me_is_hidden() {
        val shown = visibleFor(listOf(n("a", deletedBy = listOf("me")), n("b", deletedBy = listOf("other"))), "me", false)
        assertEquals(listOf("b"), shown.map { it.id })
    }

    @Test fun excluded_actor_does_not_see_their_own_notification() {
        val shown = visibleFor(listOf(n("a", excludeUserId = "me"), n("b", excludeUserId = "other")), "me", false)
        assertEquals(listOf("b"), shown.map { it.id })
    }

    @Test fun person_targeted_notification_needs_my_id() {
        val items = listOf(n("mine", forUserIds = listOf("me", "x")), n("theirs", forUserIds = listOf("x")), n("everyone"))
        assertEquals(listOf("mine", "everyone"), visibleFor(items, "me", false).map { it.id })
    }

    @Test fun super_admin_sees_person_targeted_notifications() {
        val items = listOf(n("theirs", forUserIds = listOf("x")))
        assertEquals(listOf("theirs"), visibleFor(items, "boss", true).map { it.id })
    }

    @Test fun super_admin_still_loses_removed_and_excluded() {
        val items = listOf(n("a", deletedBy = listOf("boss")), n("b", excludeUserId = "boss"))
        assertTrue(visibleFor(items, "boss", true).isEmpty())
    }

    @Test fun unread_means_my_id_is_not_in_readBy() {
        assertTrue(isUnread(n("a", readBy = listOf("other")), "me"))
        assertFalse(isUnread(n("a", readBy = listOf("me")), "me"))
    }

    @Test fun unread_count_counts_only_unread() {
        val items = listOf(n("a"), n("b", readBy = listOf("me")), n("c", readBy = listOf("x")))
        assertEquals(2, unreadCountOf(items, "me"))
    }

    @Test fun unread_count_of_nothing_is_zero() {
        assertEquals(0, unreadCountOf(emptyList(), "me"))
    }
}
