package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room

private val ORANGE = Color(0xFFF97316)

/**
 * Management Housekeeping Overview (the `housekeeping` route for every role except Housekeeping):
 * stats, the Workload Balance card and the rooms awaiting cleaning. Super Admin, Manager, Operations Manager.
 */
@Composable
fun HousekeepingOverviewScreen(session: SessionState.SignedIn) {
    HousekeepingOverviewContent(session, hiltViewModel())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HousekeepingOverviewContent(session: SessionState.SignedIn, vm: HousekeepingOverviewViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busyRoomIds.collectAsStateWithLifecycle()
    val canAct = canActOnOverview(session.user.role)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(title = OVERVIEW_TITLE, subtitle = state.subtitle.takeUnless { state.loading || state.failed })

        // Top-right on a phone: bound actions first, then the outlined Room Assignments button.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            vm.actions.forEach { action -> action.Content(session) }
            NbmsButton(
                text = "Room Assignments",
                onClick = vm::openAssignments,
                variant = ButtonVariant.Outline,
                leadingIcon = NbmsIcons.Users
            )
        }

        if (state.showUnassignedBanner) {
            UnassignedBanner(text = state.bannerText, onAssignNow = vm::openAssignments)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OverviewStat("Needs Cleaning", state.needsCleaning, NbmsIcons.Clock, MaterialTheme.nbms.warning, MaterialTheme.nbms.warningContainer, Modifier.weight(1f))
            OverviewStat("Maintenance", state.maintenance, NbmsIcons.Wrench, ORANGE, ORANGE.copy(alpha = 0.18f), Modifier.weight(1f))
            OverviewStat("Available", state.available, NbmsIcons.CheckCircle, MaterialTheme.nbms.success, MaterialTheme.nbms.successContainer, Modifier.weight(1f))
        }

        WorkloadBalanceCard()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NbmsIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("Rooms Awaiting Cleaning", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }

        when {
            state.loading -> LoadingState()
            state.failed -> ErrorState(message = MSG_ROOMS_LOAD_FAILED, detail = state.errorDetail, onRetry = vm::retry)
            state.cleaningRooms.isEmpty() -> AllCleanCard()
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.cleaningRooms.forEach { room ->
                    CleaningRoomCard(
                        room = room,
                        busy = room.id in busy,
                        canAct = canAct,
                        onMarkClean = { vm.markClean(room) },
                        onMaintenance = { vm.flagMaintenance(room) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UnassignedBanner(text: String, onAssignNow: () -> Unit) {
    val nbms = MaterialTheme.nbms
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(nbms.destructiveContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = nbms.destructive, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = nbms.onDestructiveContainer, modifier = Modifier.weight(1f))
        }
        NbmsButton(text = "Assign Now", onClick = onAssignNow, variant = ButtonVariant.Destructive, size = ButtonSize.Sm)
    }
}

/** Compact stat tile: three of them share one phone row. */
@Composable
private fun OverviewStat(label: String, value: Int, icon: ImageVector, iconColor: Color, tileColor: Color, modifier: Modifier = Modifier) {
    NbmsCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AllCleanCard() {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.nbms.success, modifier = Modifier.size(40.dp))
            Text(OVERVIEW_ALL_CLEAN_TITLE, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
            Text(
                OVERVIEW_ALL_CLEAN_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CleaningRoomCard(room: Room, busy: Boolean, canAct: Boolean, onMarkClean: () -> Unit, onMaintenance: () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Room ${room.number}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(overviewRoomSubtitle(room), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NbmsBadge("Cleaning", BadgeTone.Warning)
            }
            if (canAct) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkCleanButton(busy = busy, onClick = onMarkClean, modifier = Modifier.weight(1f))
                    MaintenanceButton(enabled = !busy, onClick = onMaintenance)
                }
            }
        }
    }
}

/** Green action button ("Updating…" while busy). */
@Composable
private fun MarkCleanButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        Icon(NbmsIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(if (busy) "Updating…" else "Mark Clean", modifier = Modifier.padding(start = 6.dp))
    }
}

/** Orange triangle: flag the room for maintenance. */
@Composable
private fun MaintenanceButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = ORANGE.copy(alpha = 0.15f), contentColor = ORANGE, disabledContainerColor = ORANGE.copy(alpha = 0.08f), disabledContentColor = ORANGE.copy(alpha = 0.4f))
    ) {
        Icon(NbmsIcons.AlertTriangle, contentDescription = "Flag for maintenance", modifier = Modifier.size(20.dp))
    }
}
