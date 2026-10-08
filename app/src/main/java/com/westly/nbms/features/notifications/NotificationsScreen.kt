package com.westly.nbms.features.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.notify.AppNotification
import com.westly.nbms.core.session.SessionState

/** The Notifications page (route `notifications`): open to every role, no drawer item. */
@Composable
fun NotificationsScreen(session: SessionState.SignedIn, vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uid = session.user.uid
    val scheme = MaterialTheme.colorScheme

    val items = (state.feed as? FeedState.Ready)?.items ?: emptyList()
    val unread = unreadCountOf(items, uid)
    val shown = if (state.tab == NotificationsTab.Unread) items.filter { isUnread(it, uid) } else items

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 672.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Notifications",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onBackground
                    )
                    if (state.feed is FeedState.Ready) {
                        Text(unreadSubtitle(unread), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (unread > 0) {
                    NbmsButton(
                        text = "Mark all read",
                        onClick = vm::markAllRead,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        leadingIcon = Icons.Outlined.DoneAll,
                        loading = state.markingAll
                    )
                }
            }

            Tabs(
                selected = state.tab,
                unread = unread,
                onSelect = vm::selectTab
            )

            when (val feed = state.feed) {
                FeedState.Loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                is FeedState.Error -> ErrorState(message = feed.message, onRetry = vm::retry)
                is FeedState.Ready -> if (shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp).alpha(0.4f)
                        )
                        Text(
                            if (state.tab == NotificationsTab.Unread) "No unread notifications." else "No notifications yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant
                        )
                    }
                } else {
                    NbmsCard(Modifier.fillMaxWidth()) {
                        shown.forEachIndexed { index, n ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                            NotificationRow(
                                n = n,
                                unread = isUnread(n, uid),
                                onOpen = { vm.open(n, uid) },
                                onRemove = { vm.removeForMe(n) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(selected: NotificationsTab, unread: Int, onSelect: (NotificationsTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NotificationsTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val label = when (tab) {
                    NotificationsTab.All -> "All"
                    NotificationsTab.Unread -> if (unread > 0) "Unread ($unread)" else "Unread"
                }
                Column(
                    Modifier
                        .height(44.dp)
                        .semantics { role = Role.Tab }
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) scheme.onBackground else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .fillMaxWidth()
                            .background(if (isSelected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
    }
}

@Composable
private fun NotificationRow(n: AppNotification, unread: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (unread) scheme.primary.copy(alpha = 0.05f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(36.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(notificationIcon(n.type), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(scheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(n.severity)))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
            }
            Text(n.message, fontSize = 14.sp, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Text(
                timeAgo(n.createdAt),
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Remove notification",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
