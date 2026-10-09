package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate
import com.westly.nbms.core.data.Resource

/**
 * Room Assignments (`housekeeping/assignments`): long-term ownership of rooms by housekeepers.
 * Super Admin, Manager and Operations Manager; the drawer and the route guard keep everyone else out.
 */
@Composable
fun RoomAssignmentsScreen(session: SessionState.SignedIn, vm: RoomAssignmentsViewModel = hiltViewModel()) {
    val state by vm.groups.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val ending by vm.ending.collectAsStateWithLifecycle()

    var showEnded by remember { mutableStateOf(false) }
    var assignOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoomAssignmentGroup?>(null) }
    var reassigning by remember { mutableStateOf<RoomAssignmentGroup?>(null) }
    var endingGroup by remember { mutableStateOf<RoomAssignmentGroup?>(null) }

    val all = (state as? Resource.Success)?.data.orEmpty()
    val active = groupsWithStatus(all, "active")
    val ended = groupsWithStatus(all, "ended")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                NbmsIcons.Users,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).size(24.dp)
            )
            PageHeader(
                title = "Room Assignments",
                subtitle = "Give housekeepers long-term ownership of specific rooms — e.g. an entire floor for a month.",
                modifier = Modifier.weight(1f)
            ) {
                NbmsButton(text = "Assign Rooms", onClick = { assignOpen = true }, leadingIcon = NbmsIcons.Plus)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPill("Active (${active.size})", selected = !showEnded) { showEnded = false }
            TabPill("Ended (${ended.size})", selected = showEnded) { showEnded = true }
        }

        when (val s = state) {
            is Resource.Loading -> {
                LoadingState()
                Text(
                    "Loading assignments…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is Resource.Error -> ErrorState(message = s.message, onRetry = vm::retry)
            is Resource.Success -> {
                val shown = if (showEnded) ended else active
                if (shown.isEmpty()) {
                    NbmsCard(Modifier.fillMaxWidth()) {
                        Text(
                            "No ${if (showEnded) "ended" else "active"} room assignments.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shown.forEach { group ->
                            GroupCard(
                                group = group,
                                onEdit = { editing = group },
                                onReassign = { reassigning = group },
                                onEnd = { endingGroup = group }
                            )
                        }
                    }
                }
            }
        }
    }

    RoomAssignDialog(open = assignOpen, onDismiss = { assignOpen = false })

    reassigning?.let { group ->
        RoomAssignDialog(
            open = true,
            onDismiss = { reassigning = null },
            preselectedRoomIds = group.roomIds
        )
    }

    editing?.let { group ->
        EditAssignmentDialog(
            group = group,
            today = vm.today(),
            saving = saving,
            onDismiss = { editing = null },
            onSave = { start, end, ongoing, notes -> vm.saveEdit(group, start, end, ongoing, notes) { editing = null } }
        )
    }

    endingGroup?.let { group ->
        NbmsDialog(
            title = "End this assignment?",
            description = "${group.housekeeperName} will lose access to ${group.roomIds.size} room(s) immediately. " +
                "They'll be unassigned until reassigned to someone else.",
            onDismiss = { if (!ending) endingGroup = null },
            confirmText = "End Assignment",
            onConfirm = { vm.endGroup(group) { endingGroup = null } },
            destructive = true,
            loading = ending
        ) {}
    }
}

/** Active / Ended pill button (selected = primary fill, like the Bookings status chips). */
@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(group: RoomAssignmentGroup, onEdit: () -> Unit, onReassign: () -> Unit, onEnd: () -> Unit) {
    val isActive = group.status == "active"
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth().alpha(if (isActive) 1f else 0.7f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    group.housekeeperName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                NbmsBadge(text = group.status, tone = if (isActive) BadgeTone.Default else BadgeTone.Secondary)
            }
            Text(
                assignmentRangeText(group.startDate, group.endDate),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )

            val chips = roomChipsOf(group.roomNumbers)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    NbmsIcons.Bed,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp)
                )
                if (chips.shown.isEmpty()) {
                    Text(
                        "No rooms remaining in this group",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = scheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        chips.shown.forEach { NbmsBadge(text = "Rm $it", tone = BadgeTone.Outline) }
                        if (chips.more > 0) NbmsBadge(text = "+${chips.more} more", tone = BadgeTone.Outline)
                    }
                }
            }

            if (!group.notes.isNullOrBlank()) {
                Text(
                    "\"${group.notes}\"",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = scheme.onSurfaceVariant
                )
            }

            if (isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton(text = "Edit", onClick = onEdit, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Pencil)
                    NbmsButton(text = "Reassign", onClick = onReassign, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Refresh)
                    DestructiveOutlineButton(text = "End", onClick = onEnd)
                }
            }
        }
    }
}

/** Small outlined button with destructive (red) text and icon: the design system has no such variant. */
@Composable
private fun DestructiveOutlineButton(text: String, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    val red = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.nbms.buttonOutline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.XCircle, contentDescription = null, tint = red, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = red, maxLines = 1)
    }
}

/** "Edit Assignment — {name}": dates (or ongoing) and special instructions. */
@Composable
private fun EditAssignmentDialog(
    group: RoomAssignmentGroup,
    today: LocalDate,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (start: LocalDate, end: LocalDate, ongoing: Boolean, notes: String) -> Unit
) {
    val initialStart = calendarDayOf(group.startDate) ?: today
    var start by remember(group.id) { mutableStateOf(initialStart) }
    var end by remember(group.id) { mutableStateOf(calendarDayOf(group.endDate) ?: initialStart.plusMonths(1)) }
    var ongoing by remember(group.id) { mutableStateOf(group.endDate == null) }
    var notes by remember(group.id) { mutableStateOf(group.notes.orEmpty()) }

    NbmsDialog(
        title = "Edit Assignment — ${group.housekeeperName}",
        onDismiss = { if (!saving) onDismiss() },
        confirmText = "Save",
        onConfirm = { onSave(start, end, ongoing, notes) },
        loading = saving
    ) {
        NbmsDatePickerField(
            label = "Start Date",
            value = start.toKotlinLocalDate(),
            onChange = { start = it.toJavaLocalDate() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        NbmsDatePickerField(
            label = "End Date",
            value = end.toKotlinLocalDate(),
            onChange = { end = it.toJavaLocalDate() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && !ongoing
        )
        NbmsButton(
            text = if (ongoing) "Set end date" else "Make ongoing",
            onClick = { ongoing = !ongoing },
            variant = ButtonVariant.Link,
            size = ButtonSize.Sm,
            enabled = !saving
        )
        NbmsTextField(
            value = notes,
            onValueChange = { notes = it },
            label = "Special Instructions",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Optional notes",
            singleLine = false,
            enabled = !saving
        )
    }
}
