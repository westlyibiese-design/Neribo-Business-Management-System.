package com.westly.nbms.features.messages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsShadow
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

/** Pill colours of a reply status: gray = not replied, yellow = pending, green = replied. */
@Composable
internal fun ReplyStatus.pillColors() = MaterialTheme.nbms.statusPill(
    when (this) {
        ReplyStatus.NONE -> "out_of_service"
        ReplyStatus.PENDING -> "pending"
        ReplyStatus.REPLIED -> "available"
    }
)

/**
 * Message Inbox: search, three filters, one card per website enquiry, live updates and pull-down refresh.
 * The list scrolls inside a box about as tall as the screen so that the pull-down gesture works
 * even though the page around it already scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: MessagesViewModel = hiltViewModel()
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val unread by vm.unreadCount.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(MessageFilter.ALL) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    // First load, and a fresh fetch every time the screen comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val loaded = ui.load == InboxLoad.READY
    val visible = remember(messages, query, filter) { filterMessages(messages, query, filter) }
    val boxHeight = (LocalConfiguration.current.screenHeightDp.dp - 140.dp).coerceAtLeast(420.dp)

    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = vm::refresh,
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(NbmsIcons.Reviews, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    PageHeader(
                        title = "Message Inbox",
                        subtitle = headerSubtitle(total = messages.size, unread = unread, loaded = loaded),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item(key = "search") {
                SearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search by name, email, or subject…"
                )
            }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageFilter.entries.forEach { f ->
                        NbmsButton(
                            text = f.label,
                            onClick = { filter = f },
                            modifier = Modifier.weight(1f),
                            variant = if (filter == f) ButtonVariant.Default else ButtonVariant.Outline,
                            size = ButtonSize.Sm
                        )
                    }
                }
            }

            when {
                ui.load == InboxLoad.LOADING && messages.isEmpty() -> item(key = "loading") { LoadingState() }
                ui.load == InboxLoad.FAILED && messages.isEmpty() -> item(key = "error") {
                    ErrorState(message = "We couldn't load messages.", onRetry = vm::refresh)
                }
                visible.isEmpty() -> item(key = "empty") {
                    if (messages.isEmpty()) {
                        EmptyState(
                            icon = NbmsIcons.Mail,
                            title = "No messages yet.",
                            message = "Enquiries from the website contact form will show up here."
                        )
                    } else {
                        EmptyState(
                            icon = NbmsIcons.Search,
                            title = "No messages match your filters.",
                            message = "Try a different search or filter."
                        )
                    }
                }
                else -> items(visible, key = { it.id }) { m ->
                    MessageCard(m) {
                        openId = m.id
                        vm.opened(m)
                    }
                }
            }
        }
    }

    // The dialog reads the live message, so a status change shows at once and a removed message closes it.
    val selected = messages.firstOrNull { it.id == openId }
    if (selected != null) {
        MessageDetailDialog(
            message = selected,
            busy = ui.busy,
            onSetReplyStatus = { vm.setReplyStatus(selected.id, it) },
            onReplyByEmail = {
                val link = mailtoLink(selected.email, replySubject(selected.subject, session.business.name))
                context.openLink(Intent.ACTION_SENDTO, link) { vm.showError("No email app found on this phone.") }
            },
            onEmail = { context.openLink(Intent.ACTION_SENDTO, mailtoLink(selected.email)) { vm.showError("No email app found on this phone.") } },
            onPhone = { phone -> context.openLink(Intent.ACTION_DIAL, telLink(phone)) { vm.showError("No phone app found on this device.") } },
            onRemove = { vm.remove(selected.id) { openId = null } },
            onDismiss = { openId = null }
        )
    }
}

private fun Context.openLink(action: String, uri: String, onMissing: () -> Unit) {
    try {
        startActivity(Intent(action, Uri.parse(uri)))
    } catch (e: ActivityNotFoundException) {
        onMissing()
    }
}

/** One enquiry. Unread cards have a primary border and a 3% primary tint. */
@Composable
private fun MessageCard(m: InboxMessage, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val isNew = m.isNew

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nbmsShadow(4.dp, shape)
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, if (isNew) scheme.primary else MaterialTheme.nbms.cardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isNew) scheme.primary.copy(alpha = 0.03f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
                if (isNew) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary))
                } else {
                    Icon(NbmsIcons.CheckCircle, contentDescription = "Read", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.name.ifBlank { "Unknown sender" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Medium),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(Format.relative(m.createdAt), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Text(m.email, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!m.subject.isNullOrBlank()) {
                    Text(m.subject, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(firstLine(m.message), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NbmsPill(text = m.replyStatus.label, colors = m.replyStatus.pillColors())
                    if (!m.phone.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(NbmsIcons.Phone, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                            Text(m.phone, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
