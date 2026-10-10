package com.westly.nbms.features.opslog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState

private val LOST_FOUND_TABLE_MIN_WIDTH = 720.dp

/** Lost & Found: the live log of items housekeepers found in rooms, with search and a status filter. */
@Composable
fun LostFoundScreen(session: SessionState.SignedIn) {
    val vm: LostFoundViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val statusFilter by vm.statusFilter.collectAsStateWithLifecycle()
    val logState by vm.log.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val pinEnding by vm.pinEnding.collectAsStateWithLifecycle()

    val role = session.user.role
    val canCreate = lostFoundCanCreate(role)
    val canManage = lostFoundCanManageStatus(role)

    val ready = view as? LostFoundView.Ready
    val filtered = remember(ready, query, statusFilter) {
        ready?.let { filterLostFound(it.items, query, statusFilter) } ?: emptyList()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LostFoundHeader(count = filtered.size, canCreate = canCreate, onLog = vm::openLogSheet)
        LostFoundFilters(
            query = query,
            onQuery = vm::setQuery,
            status = statusFilter,
            onStatus = vm::setStatusFilter
        )

        when (val v = view) {
            is LostFoundView.Loading -> LoadingState()
            is LostFoundView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is LostFoundView.Ready -> {
                if (filtered.isEmpty()) {
                    EmptyState(NbmsIcons.PackageSearch, "No lost & found items recorded", "")
                } else {
                    LostFoundList(items = filtered, onView = vm::openDetail)
                }
            }
        }
    }

    if (logState.open) {
        LogFoundItemSheet(
            state = logState,
            rooms = rooms,
            imageProvider = vm.imageProvider,
            onChange = vm::updateForm,
            onCancel = vm::closeLogSheet,
            onSubmit = vm::submit
        )
    }

    // The detail sheet always shows the live item, so a status change refreshes it at once.
    val openItem = detail?.let { d -> ready?.items?.firstOrNull { it.id == d.itemId } }
    if (detail != null && openItem != null) {
        LostFoundDetailSheet(
            item = openItem,
            state = detail!!,
            canManageStatus = canManage,
            onNote = vm::setNote,
            onMark = { target -> vm.markStatus(openItem, target) },
            onClose = vm::closeDetail
        )
    }

    if (pinEnding) PinSessionEndingOverlay()
}

@Composable
private fun LostFoundHeader(count: Int, canCreate: Boolean, onLog: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("Lost & Found", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                lostFoundSubtitle(count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canCreate) {
            NbmsButton(text = "Log Found Item", onClick = onLog, leadingIcon = NbmsIcons.Plus)
        }
    }
}

@Composable
private fun LostFoundFilters(
    query: String,
    onQuery: (String) -> Unit,
    status: ItemStatus?,
    onStatus: (ItemStatus?) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val search: @Composable (Modifier) -> Unit = { m ->
            SearchBar(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search by item, room, description, housekeeper…",
                modifier = m
            )
        }
        val dropdown: @Composable (Modifier) -> Unit = { m ->
            NbmsDropdown<ItemStatus?>(
                label = "",
                options = listOf<ItemStatus?>(null) + ItemStatus.entries,
                selected = status,
                onSelect = onStatus,
                optionLabel = { it?.label ?: ALL_STATUS_LABEL },
                modifier = m,
                placeholder = ALL_STATUS_LABEL
            )
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                search(Modifier.weight(1f))
                dropdown(Modifier.width(220.dp))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                search(Modifier.fillMaxWidth())
                dropdown(Modifier.fillMaxWidth())
            }
        }
    }
}

// ── list: cards on phones, table on tablets ──

@Composable
private fun LostFoundList(items: List<LostFoundItem>, onView: (LostFoundItem) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= LOST_FOUND_TABLE_MIN_WIDTH) {
            LostFoundTable(items, onView)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { item -> LostFoundCard(item, onView = { onView(item) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LostFoundCard(item: LostFoundItem, onView: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.itemName, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    item.description?.let { DescriptionLine(it) }
                }
                LostFoundStatusPill(item.status)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconText(NbmsIcons.Bed, "Room ${item.roomNumber}")
                IconText(NbmsIcons.User, item.foundByName)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lostFoundDateTime(item.foundAt), fontSize = 12.sp, lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                ViewButton(onView)
            }
        }
    }
}

@Composable
private fun LostFoundTable(items: List<LostFoundItem>, onView: (LostFoundItem) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(scheme.surfaceVariant.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeaderCell("Item", Modifier.weight(3f))
                HeaderCell("Room", Modifier.weight(1.2f))
                HeaderCell("Found By", Modifier.weight(2f))
                HeaderCell("Found At", Modifier.weight(2.2f))
                HeaderCell("Status", Modifier.weight(1.9f))
                HeaderCell("Actions", Modifier.weight(1.2f))
            }
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            item.itemName, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                            color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        item.description?.let { DescriptionLine(it) }
                    }
                    BodyCell("Room ${item.roomNumber}", Modifier.weight(1.2f))
                    BodyCell(item.foundByName, Modifier.weight(2f))
                    BodyCell(lostFoundDateTime(item.foundAt), Modifier.weight(2.2f))
                    Box(Modifier.weight(1.9f)) { LostFoundStatusPill(item.status) }
                    Box(Modifier.weight(1.2f)) { ViewButton { onView(item) } }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text, modifier = modifier, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BodyCell(text: String, modifier: Modifier) {
    Text(
        text, modifier = modifier, fontSize = 14.sp, lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
    )
}

/** The one-line, truncated description under an item name (12sp, muted). */
@Composable
private fun DescriptionLine(text: String) {
    Text(
        text, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun IconText(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ViewButton(onClick: () -> Unit) {
    NbmsButton(text = "View", onClick = onClick, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Eye)
}

// ── status look (shared with the sheets in this package) ──

/** Light text / background and dark text / background of one status pill (Tailwind palette). */
private class StatusLook(val lightBg: Long, val lightText: Long, val darkBg: Long, val darkText: Long)

private fun ItemStatus.look(): StatusLook = when (this) {
    ItemStatus.STORED -> StatusLook(0xFFDBEAFE, 0xFF1E40AF, 0x4D1E3A8A, 0xFF60A5FA)             // blue
    ItemStatus.RETURNED_TO_GUEST -> StatusLook(0xFFDCFCE7, 0xFF166534, 0x4D14532D, 0xFF4ADE80)  // green
    ItemStatus.CLAIMED -> StatusLook(0xFFF3E8FF, 0xFF6B21A8, 0x4D581C87, 0xFFC084FC)            // purple
    ItemStatus.DISPOSED -> StatusLook(0xFFF3F4F6, 0xFF4B5563, 0xFF1F2937, 0xFF9CA3AF)           // gray
}

/** Pill colours of a status for the current theme. */
@Composable
internal fun ItemStatus.pillColors(): PillColors {
    val l = look()
    return if (MaterialTheme.nbms.isDark) PillColors(Color(l.darkBg), Color(l.darkText))
    else PillColors(Color(l.lightBg), Color(l.lightText))
}

/** package-open, archive-restore, package-check, package-x. */
internal fun ItemStatus.icon(): ImageVector = when (this) {
    ItemStatus.STORED -> NbmsIcons.Package
    ItemStatus.RETURNED_TO_GUEST -> Icons.Outlined.Unarchive
    ItemStatus.CLAIMED -> NbmsIcons.CheckCircle
    ItemStatus.DISPOSED -> NbmsIcons.XCircle
}

/** Coloured status pill, light and dark. */
@Composable
internal fun LostFoundStatusPill(status: ItemStatus, modifier: Modifier = Modifier) {
    NbmsPill(text = status.label, colors = status.pillColors(), modifier = modifier)
}

// ── shared-device sign-out overlay (private to Lost & Found) ──

/** Full-screen dim layer shown for 2.5 seconds after a PIN user logs an item, just before the session ends. */
@Composable
private fun PinSessionEndingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("Ending session for security…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}
