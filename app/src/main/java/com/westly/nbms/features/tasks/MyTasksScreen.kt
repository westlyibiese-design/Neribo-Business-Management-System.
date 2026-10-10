package com.westly.nbms.features.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone

/**
 * My Tasks (`my-tasks`, every role): the tasks assigned to me, with Accept / Start / Mark Complete,
 * my upcoming shifts (card hidden while there are none) and my recently finished tasks.
 */
@Composable
fun MyTasksScreen(session: SessionState.SignedIn) {
    MyTasksScreenContent(session)
}

@Composable
private fun MyTasksScreenContent(session: SessionState.SignedIn, vm: MyTasksViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val tz = remember(session.business.timezone) {
        runCatching { TimeZone.of(session.business.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    }
    val ready = view as? MyTasksView.Ready

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "My Tasks",
            subtitle = MyTasksRules.subtitle(ready?.active?.size ?: 0)
        ) {
            Icon(
                NbmsIcons.ClipboardCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        when (val v = view) {
            is MyTasksView.Loading -> LoadingState()
            is MyTasksView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is MyTasksView.Ready -> {
                if (v.shifts.isNotEmpty()) UpcomingShiftsCard(v.shifts)

                if (v.active.isEmpty()) {
                    AllCaughtUp()
                } else {
                    v.active.forEach { row ->
                        key(row.task.id) {
                            MyTaskCard(
                                row = row,
                                tz = tz,
                                busy = busy[row.task.id],
                                onAccept = { vm.accept(row.task) },
                                onStart = { vm.start(row.task) },
                                onComplete = { vm.complete(row.task) }
                            )
                        }
                    }
                }

                if (v.finished.isNotEmpty()) {
                    Text(
                        "Recently Finished",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    v.finished.forEach { task ->
                        key(task.id) { FinishedCard(task) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingShiftsCard(shifts: List<MyShiftRow>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NbmsIcons.CalendarClock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    "My Upcoming Shifts",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            shifts.forEach { shift ->
                key(shift.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append(shift.dayLabel)
                                if (shift.label.isNotBlank()) append(" · ${shift.label}")
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(shift.timeRange, fontSize = 12.sp, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun AllCaughtUp() {
    val success = MaterialTheme.nbms.success
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(success.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = success, modifier = Modifier.size(32.dp))
        }
        Text(
            "You're all caught up",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            "No active tasks assigned to you right now.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyTaskCard(
    row: MyTaskRow,
    tz: TimeZone,
    busy: MyTaskAction?,
    onAccept: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit
) {
    val task = row.task
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val destructive = MaterialTheme.nbms.destructive
    val actions = MyTasksRules.actionsFor(task.taskStatus())

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (row.overdue) Modifier.border(1.dp, destructive.copy(alpha = 0.5f), MaterialTheme.shapes.large)
                    else Modifier
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskTypeChip(task.taskType())
                TaskPriorityPill(task.taskPriority())
                if (row.overdue) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = destructive, modifier = Modifier.size(14.dp))
                        Text(
                            "Overdue",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = destructive
                        )
                    }
                }
            }

            task.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 14.sp, color = muted)
            }
            task.relatedLabel?.takeIf { it.isNotBlank() }?.let {
                Text("Related: $it", fontSize = 12.sp, color = muted)
            }
            Text(myTaskFooterText(task, tz), fontSize = 12.sp, color = muted)

            if (actions.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (MyTaskAction.ACCEPT in actions) {
                        NbmsButton(
                            text = "Accept",
                            onClick = onAccept,
                            size = ButtonSize.Sm,
                            loading = busy == MyTaskAction.ACCEPT,
                            enabled = busy == null,
                            leadingIcon = NbmsIcons.Check
                        )
                    }
                    if (MyTaskAction.START in actions) {
                        NbmsButton(
                            text = "Start",
                            onClick = onStart,
                            size = ButtonSize.Sm,
                            loading = busy == MyTaskAction.START,
                            enabled = busy == null,
                            leadingIcon = Icons.Outlined.PlayArrow
                        )
                    }
                    if (MyTaskAction.COMPLETE in actions) {
                        NbmsButton(
                            text = "Mark Complete",
                            onClick = onComplete,
                            variant = ButtonVariant.Outline,
                            size = ButtonSize.Sm,
                            loading = busy == MyTaskAction.COMPLETE,
                            enabled = busy == null,
                            leadingIcon = NbmsIcons.CheckCircle
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishedCard(task: StaffTask) {
    val completed = task.taskStatus() == TaskStatus.COMPLETED
    NbmsCard(Modifier.fillMaxWidth().alpha(0.6f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(task.taskType().label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NbmsBadge(
                text = task.taskStatus().key,
                tone = if (completed) BadgeTone.Default else BadgeTone.Destructive
            )
        }
    }
}

/** "Assigned by {name} · {date-time}" + " · Due {date-time}" when the task has a due time. */
internal fun myTaskFooterText(task: StaffTask, tz: TimeZone): String = buildString {
    append("Assigned by ${task.assignedByName} · ${Format.dateTime(task.createdAt.toInstant(), tz)}")
    task.dueAt?.let { append(" · Due ${Format.dateTime(it.toInstant(), tz)}") }
}
