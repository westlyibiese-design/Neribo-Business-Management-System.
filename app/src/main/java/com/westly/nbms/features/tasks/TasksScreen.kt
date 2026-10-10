package com.westly.nbms.features.tasks

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.StatCard
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone

/** One choice of the type dropdown ("All Types" has a null [type]). */
private data class TypeOption(val type: TaskType?) {
    val label: String get() = taskTypeFilterLabel(type)
}

/**
 * Task Assignment (`tasks`): stats, filters and the live task list for the Super Admin, Manager and Operations Manager.
 * Assign Task (Super Admin and Operations Manager only) and Reassign both open [TaskAssignDialog]; Cancel is done here.
 */
@Composable
fun TasksScreen(session: SessionState.SignedIn) {
    TasksScreenContent(session)
}

@Composable
private fun TasksScreenContent(session: SessionState.SignedIn, vm: TasksViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val busyIds by vm.busyIds.collectAsStateWithLifecycle()
    val tz = remember(session.business.timezone) {
        runCatching { TimeZone.of(session.business.timezone) }.getOrElse { TimeZone.currentSystemDefault() }
    }

    var assignOpen by remember { mutableStateOf(false) }
    var reassignTarget by remember { mutableStateOf<ReassignTarget?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(title = "Task Assignment", subtitle = "Coordinate daily operations across every department.") {
            if (vm.canAssign) {
                NbmsButton(
                    text = "Assign Task",
                    onClick = { assignOpen = true },
                    size = ButtonSize.Sm,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        val ready = view as? TasksView.Ready
        StatCard(
            label = "Active Tasks",
            value = (ready?.stats?.active ?: 0).toString(),
            icon = NbmsIcons.ClipboardCheck,
            tone = BadgeTone.Info
        )
        StatCard(
            label = "Overdue",
            value = (ready?.stats?.overdue ?: 0).toString(),
            icon = NbmsIcons.AlertTriangle,
            tone = BadgeTone.Destructive
        )
        StatCard(
            label = "Completed Today",
            value = (ready?.stats?.completedToday ?: 0).toString(),
            icon = NbmsIcons.CheckCircle,
            tone = BadgeTone.Success
        )

        SearchBar(filters.search, vm::setSearch, "Search tasks or staff…", Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsDropdown(
                label = "Status",
                options = TaskStatusFilter.entries.toList(),
                selected = filters.status,
                onSelect = vm::setStatus,
                optionLabel = ::taskStatusFilterLabel,
                modifier = Modifier.weight(1f)
            )
            val typeOptions = remember { listOf(TypeOption(null)) + TaskType.entries.map { TypeOption(it) } }
            NbmsDropdown(
                label = "Type",
                options = typeOptions,
                selected = typeOptions.first { it.type == filters.type },
                onSelect = { vm.setType(it.type) },
                optionLabel = { it.label },
                modifier = Modifier.weight(1f)
            )
        }

        when (val v = view) {
            is TasksView.Loading -> LoadingState()
            is TasksView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is TasksView.Ready ->
                if (v.rows.isEmpty()) {
                    EmptyState(NbmsIcons.ClipboardCheck, "No tasks found", "")
                } else {
                    PagedList(v.rows, key = { it.task.id }) { row ->
                        TaskCard(
                            row = row,
                            tz = tz,
                            busy = row.task.id in busyIds,
                            onReassign = { reassignTarget = ReassignTarget(row.task.id, row.task.title) },
                            onCancel = { vm.cancel(row.task) }
                        )
                    }
                }
        }
    }

    TaskAssignDialog(
        open = assignOpen,
        onDismiss = { assignOpen = false }
    )
    TaskAssignDialog(
        open = reassignTarget != null,
        onDismiss = { reassignTarget = null },
        reassignTask = reassignTarget
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    row: TaskRow,
    tz: TimeZone,
    busy: Boolean,
    onReassign: () -> Unit,
    onCancel: () -> Unit
) {
    val task = row.task
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val destructive = MaterialTheme.nbms.destructive
    val cardShape = MaterialTheme.shapes.large

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (row.overdue) Modifier.border(1.dp, destructive.copy(alpha = 0.5f), cardShape) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TaskTypeChip(task.taskType())
                TaskPriorityPill(task.taskPriority())
                TaskStatusPill(task.taskStatus())
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
                Text(it, fontSize = 12.sp, color = muted)
            }
            task.relatedLabel?.takeIf { it.isNotBlank() }?.let {
                Text("Related: $it", fontSize = 12.sp, color = muted)
            }

            if (task.assignedToNames.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(NbmsIcons.User, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                    task.assignedToNames.forEach { NbmsBadge(text = it, tone = BadgeTone.Secondary) }
                }
            }

            Text(taskFooterText(task, tz), fontSize = 12.sp, color = muted)

            if (canManageTask(task)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NbmsButton(
                        text = "Reassign",
                        onClick = onReassign,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        enabled = !busy,
                        leadingIcon = NbmsIcons.Refresh
                    )
                    CancelTaskButton(busy = busy, onClick = onCancel)
                }
            }
        }
    }
}

/** Ghost button with destructive (red) text; shows a spinner while the cancel is running. */
@Composable
private fun CancelTaskButton(busy: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.nbms.destructive
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .alpha(if (busy) 0.5f else 1f)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
        }
        Text(
            "Cancel",
            modifier = Modifier.padding(start = if (busy) 8.dp else 0.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

/**
 * "Assigned by {name} · {date-time}" + " · Due {date-time}" + " · Accepted by {name}" + (completed) " · Completed {date-time}".
 */
internal fun taskFooterText(task: StaffTask, tz: TimeZone): String = buildString {
    append("Assigned by ${task.assignedByName} · ${Format.dateTime(task.createdAt.toInstant(), tz)}")
    task.dueAt?.let { append(" · Due ${Format.dateTime(kotlinx.datetime.Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()), tz)}") }
    task.acceptedByName?.takeIf { it.isNotBlank() }?.let { append(" · Accepted by $it") }
    if (task.taskStatus() == TaskStatus.COMPLETED) {
        task.completedAt?.let { append(" · Completed ${Format.dateTime(kotlinx.datetime.Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()), tz)}") }
    }
}
