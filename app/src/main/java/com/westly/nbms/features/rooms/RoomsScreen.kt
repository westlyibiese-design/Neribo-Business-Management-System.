package com.westly.nbms.features.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.AdaptiveSideBySide
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role as StaffRole
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

/** Grid columns: 1 on phones, 2 from 600dp, 3 from 840dp. */
internal fun gridColumns(widthDp: Float): Int = when {
    widthDp >= 840f -> 3
    widthDp >= 600f -> 2
    else -> 1
}

/** Rooms: a card grid with status filter chips. Only the Super Admin can add, edit, delete or change status. */
@Composable
fun RoomsScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: RoomsViewModel = hiltViewModel()
) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val isSuperAdmin = session.user.role == StaffRole.SUPER_ADMIN
    val symbol = session.business.currencySymbol

    var filter by rememberSaveable { mutableStateOf("all") }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val loaded: List<Room> = when (val r = rooms) {
        is Resource.Success -> r.data
        else -> emptyList()
    }
    val subtitle = if (rooms is Resource.Success) "${loaded.size} rooms total" else null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(title = "Rooms", subtitle = subtitle) {
            if (isSuperAdmin) {
                NbmsButton(
                    text = "Add Room",
                    onClick = { editingId = null; showForm = true },
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        when (val r = rooms) {
            is Resource.Loading -> LoadingState()
            is Resource.Error -> ErrorState("We couldn't load rooms.", onRetry = vm::retry)
            is Resource.Success -> {
                val counts = remember(r.data) { roomCounts(r.data) }
                val chips = remember(counts) {
                    listOf("all" to "All") + RoomStatus.entries.map { it.key to it.label }
                }.map { (key, label) -> FilterChipItem(key, "$label (${counts[key] ?: 0})") }
                FilterChips(chips, filter, onSelect = { filter = it })

                val visible = remember(r.data, filter) { filterRoomsByStatus(r.data, filter) }
                if (visible.isEmpty()) {
                    EmptyState(
                        icon = NbmsIcons.Bed,
                        title = "No rooms found",
                        message = when {
                            r.data.isEmpty() && isSuperAdmin -> "Tap Add Room to create the first one."
                            r.data.isEmpty() -> "Rooms will appear here once they are added."
                            else -> "No rooms have this status."
                        }
                    )
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = gridColumns(maxWidth.value)
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            visible.chunked(columns).forEach { rowRooms ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    rowRooms.forEach { room ->
                                        Box(Modifier.weight(1f)) {
                                            RoomCard(
                                                room = room,
                                                display = vm.displayStatus(room),
                                                symbol = symbol,
                                                canManage = isSuperAdmin,
                                                busy = room.id in state.busyIds,
                                                onStatus = { vm.changeStatus(room, it, session.user.name) },
                                                onEdit = { editingId = room.id; showForm = true },
                                                onDelete = { deleteId = room.id }
                                            )
                                        }
                                    }
                                    repeat(columns - rowRooms.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm && isSuperAdmin) {
        val editing = loaded.firstOrNull { it.id == editingId }
        if (editingId == null || editing != null) {
            RoomFormDialog(
                editing = editing,
                existingRooms = loaded,
                currencySymbol = symbol,
                saving = state.saving,
                imageProvider = vm.imageProvider,
                onDismiss = { showForm = false },
                onSave = { input -> vm.saveRoom(editing, input) { showForm = false } }
            )
        }
    }

    val toDelete = loaded.firstOrNull { it.id == deleteId }
    if (isSuperAdmin && toDelete != null) {
        ConfirmDialog(
            title = "Delete Room ${toDelete.number}?",
            message = "This action cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                deleteId = null
                vm.deleteRoom(toDelete)
            },
            onDismiss = { deleteId = null }
        )
    }
}

@Composable
private fun RoomCard(
    room: Room,
    display: RoomDisplayStatus,
    symbol: String,
    canManage: Boolean,
    busy: Boolean,
    onStatus: (RoomStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        val image = room.images.firstOrNull { it.isNotBlank() }
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = "Room ${room.number}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(144.dp)
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(room.displayName()) }
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = scheme.onSurfaceVariant)) {
                                append(" · Room ${room.number}")
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface
                    )
                    Text(
                        "${room.type} · Floor ${room.floor}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                NbmsPill(text = display.label, colors = roomPillColors(display))
            }
            AdaptiveSideBySide {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)) {
                            append(Format.currency(room.price, symbol))
                        }
                        withStyle(SpanStyle(fontSize = 12.sp, color = scheme.onSurfaceVariant)) { append("/night") }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${room.capacity} guests",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
            AmenityChips(room.amenities)
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NbmsDropdown(
                        label = "",
                        options = RoomStatus.entries.toList(),
                        selected = RoomStatus.fromKey(room.status),
                        onSelect = onStatus,
                        optionLabel = { it.label },
                        placeholder = room.status,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    IconAction(NbmsIcons.Pencil, "Edit room ${room.number}", enabled = !busy, onClick = onEdit)
                    IconAction(
                        NbmsIcons.Trash, "Delete room ${room.number}",
                        enabled = !busy, busy = busy, destructive = true, onClick = onDelete
                    )
                }
            }
        }
    }
}

/** Pill colours for the room status labels of Appendix B section 9.3 (light and dark). */
@Composable
internal fun roomPillColors(display: RoomDisplayStatus): PillColors {
    val n = MaterialTheme.nbms
    return when (display.label) {
        RoomLabels.MAINTENANCE -> n.statusPill("maintenance")
        RoomLabels.OUT_OF_SERVICE -> n.statusPill("out_of_service")
        RoomLabels.RESERVED -> n.statusPill("reserved")
        RoomLabels.OCCUPIED_OVERDUE -> n.statusPill("occupied")
        RoomLabels.OCCUPIED_CLEANING_IN_PROGRESS, RoomLabels.CLEANING_IN_PROGRESS -> n.rolePill("super_admin") // purple
        RoomLabels.OCCUPIED_CLEANING_DUE -> n.statusPill("awaiting_approval") // amber
        RoomLabels.OCCUPIED_CLEAN -> n.statusPill("reserved") // blue
        RoomLabels.DIRTY -> n.statusPill("cleaning") // yellow
        else -> n.statusPill("available") // Clean · Available
    }
}

// ---- Small pieces shared with the Venues page -------------------------------------------------

internal data class FilterChipItem(val key: String, val label: String)

/** A row of rounded filter chips; the chosen one is filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterChips(
    items: List<FilterChipItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val on = item.key == selected
            Text(
                item.label,
                color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(if (on) scheme.primary else scheme.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(item.key) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** First four amenities as small chips, then "+N" for the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmenityChips(amenities: List<String>) {
    if (amenities.isEmpty()) return
    val shown = amenities.take(4)
    val extra = amenities.size - shown.size
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { AmenityChip(it) }
        if (extra > 0) AmenityChip("+$extra")
    }
}

@Composable
private fun AmenityChip(text: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        color = scheme.onSecondary,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.secondary)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** A small round icon button (edit / delete). Shows a spinner while [busy]. */
@Composable
internal fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (destructive) scheme.error else scheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
