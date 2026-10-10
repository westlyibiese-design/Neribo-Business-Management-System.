package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room

private val ORANGE = Color(0xFFF97316)

/**
 * The Housekeeping role's view of the `housekeeping` route: my queue, my rooms and their cleaning history.
 * Everything here is only the person's own data; management sees the Overview instead.
 */
@Composable
fun MyHousekeepingScreen(session: SessionState.SignedIn) {
    MyHousekeepingContent(session.business.timezone, hiltViewModel())
}

@Composable
internal fun MyHousekeepingContent(zoneId: String, vm: MyHousekeepingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busyTasks by vm.busyTaskIds.collectAsStateWithLifecycle()
    val busyRooms by vm.busyRoomIds.collectAsStateWithLifecycle()
    val expanded by vm.expandedRoomIds.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val pinEnding by vm.pinEnding.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(title = MY_TITLE, subtitle = state.subtitle.takeUnless { state.failed || (state.queueLoading && state.roomsLoading) })

        if (state.failed) {
            ErrorState(message = MSG_MY_LOAD_FAILED, detail = state.errorDetail, onRetry = vm::retry)
        } else {
            MyTabs(
                labels = listOf(myQueueTabLabel(state.queue.size), myRoomsTabLabel(state.assignedRoomCount)),
                icons = listOf(NbmsIcons.ClipboardCheck, NbmsIcons.Bed),
                selected = tab,
                onSelect = { tab = it }
            )
            if (tab == 0) {
                QueueTab(state, busyTasks, zoneId, onStart = vm::start, onComplete = vm::complete)
            } else {
                RoomsTab(state, busyRooms, expanded, history, zoneId, vm)
            }
        }
    }

    if (pinEnding) PinSessionEndingOverlay()
}

// ---- Tabs ------------------------------------------------------------------------------------------

@Composable
private fun MyTabs(labels: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) scheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icons[index], contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant
                )
                Text(
                    label,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 1 column on phones, 2 to 3 on tablets. The page already scrolls, so this is plain rows. */
@Composable
private fun <T> CardGrid(items: List<T>, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = gridColumns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun gridColumns(width: Dp): Int = when {
    width >= 900.dp -> 3
    width >= 600.dp -> 2
    else -> 1
}

// ---- My Queue ------------------------------------------------------------------------------------

@Composable
private fun QueueTab(
    state: MyHousekeepingState,
    busyTasks: Set<String>,
    zoneId: String,
    onStart: (HousekeepingTask) -> Unit,
    onComplete: (HousekeepingTask) -> Unit
) {
    when {
        state.queueLoading -> LoadingState()
        state.queue.isEmpty() -> EmptyQueueCard()
        else -> CardGrid(state.queue) { task ->
            QueueCard(task, busy = task.id in busyTasks, zoneId = zoneId, onStart = { onStart(task) }, onComplete = { onComplete(task) })
        }
    }
}

@Composable
private fun EmptyQueueCard() {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.nbms.success, modifier = Modifier.size(48.dp))
            Text(MY_QUEUE_EMPTY_TITLE, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
            Text(
                MY_QUEUE_EMPTY_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QueueCard(task: HousekeepingTask, busy: Boolean, zoneId: String, onStart: () -> Unit, onComplete: () -> Unit) {
    val nbms = MaterialTheme.nbms
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(1.dp)
                .border(1.dp, nbms.warning.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Room ${task.roomNumber}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Text(task.type.label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PriorityPill(task.priority)
            }

            if (!task.instructions.isNullOrBlank()) {
                Text(
                    "\"${task.instructions}\"",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                )
            }

            if (task.scheduledFor != null || task.status == TaskStatus.IN_PROGRESS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task.scheduledFor != null) {
                        Icon(NbmsIcons.Clock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Scheduled ${myDateTime(task.scheduledFor, zoneId)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (task.status == TaskStatus.IN_PROGRESS) NbmsBadge("In Progress", BadgeTone.Info)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.status == TaskStatus.PENDING) {
                    NbmsButton(
                        text = "Start",
                        onClick = onStart,
                        variant = ButtonVariant.Outline,
                        enabled = !busy,
                        leadingIcon = Icons.Outlined.PlayArrow,
                        modifier = Modifier.weight(1f)
                    )
                }
                GreenButton(text = "Complete", icon = NbmsIcons.Check, busy = busy, onClick = onComplete, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---- My Rooms ----------------------------------------------------------------------------------------

@Composable
private fun RoomsTab(
    state: MyHousekeepingState,
    busyRooms: Set<String>,
    expanded: Set<String>,
    history: Map<String, MyHistoryState>,
    zoneId: String,
    vm: MyHousekeepingViewModel
) {
    when {
        state.roomsLoading -> LoadingState()
        state.rooms.isEmpty() -> NbmsCard(Modifier.fillMaxWidth()) {
            Text(
                MY_ROOMS_EMPTY_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp, horizontal = 16.dp)
            )
        }
        else -> CardGrid(state.rooms) { room ->
            RoomCard(
                room = room,
                pendingTask = state.pendingTaskFor(room.id),
                busy = room.id in busyRooms,
                expanded = room.id in expanded,
                history = history[room.id],
                zoneId = zoneId,
                vm = vm
            )
        }
    }
}

@Composable
private fun RoomCard(
    room: Room,
    pendingTask: HousekeepingTask?,
    busy: Boolean,
    expanded: Boolean,
    history: MyHistoryState?,
    zoneId: String,
    vm: MyHousekeepingViewModel
) {
    val display = vm.displayStatus(room)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Room ${room.number}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text("${room.type} · Floor ${room.floor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NbmsBadge(display.label, display.tone)
            }

            if (pendingTask != null) {
                NbmsBadge("${pendingTask.priority.label} priority task pending", priorityTone(pendingTask.priority), leadingIcon = NbmsIcons.Clock)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GreenButton(
                    text = "Mark Clean",
                    icon = NbmsIcons.Sparkles,
                    busy = busy,
                    onClick = { vm.markClean(room) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { vm.flagMaintenance(room) },
                    enabled = !busy,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = ORANGE,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = ORANGE.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(NbmsIcons.AlertTriangle, contentDescription = "Send to maintenance", modifier = Modifier.size(20.dp))
                }
            }

            CleaningHistory(expanded = expanded, history = history, zoneId = zoneId, onToggle = { vm.toggleHistory(room.id) })
        }
    }
}

private fun priorityTone(priority: TaskPriority): BadgeTone = when (priority) {
    TaskPriority.URGENT -> BadgeTone.Destructive
    TaskPriority.HIGH -> BadgeTone.Warning
    TaskPriority.MEDIUM -> BadgeTone.Info
    TaskPriority.LOW -> BadgeTone.Secondary
}

/** Collapsible: nothing is loaded until the person opens it. */
@Composable
private fun CleaningHistory(expanded: Boolean, history: MyHistoryState?, zoneId: String, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(NbmsIcons.History, contentDescription = null, modifier = Modifier.size(16.dp), tint = muted)
            Text(
                "Cleaning history",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = muted
            )
            Icon(if (expanded) NbmsIcons.ChevronUp else NbmsIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = muted)
        }
        if (expanded) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (history) {
                    null, MyHistoryState.Loading -> Text(MY_HISTORY_LOADING, style = MaterialTheme.typography.bodySmall, color = muted)
                    is MyHistoryState.Failed -> Text(history.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is MyHistoryState.Loaded ->
                        if (history.rows.isEmpty()) {
                            Text(MY_HISTORY_EMPTY, style = MaterialTheme.typography.bodySmall, color = muted)
                        } else {
                            history.rows.forEach { row ->
                                Text(historyRowText(row, zoneId), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                }
            }
        }
    }
}

// ---- Shared pieces -------------------------------------------------------------------------------------

/** Green action button; shows a spinner while busy and is disabled then. */
@Composable
private fun GreenButton(text: String, icon: ImageVector, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val nbms = MaterialTheme.nbms
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.heightIn(min = 44.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = nbms.success,
            contentColor = nbms.onSuccess,
            disabledContainerColor = nbms.success.copy(alpha = 0.5f),
            disabledContentColor = nbms.onSuccess.copy(alpha = 0.8f)
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = nbms.onSuccess, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(text, modifier = Modifier.padding(start = 6.dp))
    }
}

// ---- Shared-device sign-out overlay (private helper of this screen) ------------------------------

/** Full-screen dim layer shown for 2.5 seconds after a PIN user finishes work, just before the session ends. */
@Composable
private fun PinSessionEndingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(MY_PIN_ENDING_TEXT, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}
