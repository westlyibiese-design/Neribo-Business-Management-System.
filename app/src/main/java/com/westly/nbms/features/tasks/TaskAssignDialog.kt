package com.westly.nbms.features.tasks

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCheckbox
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime

/**
 * "Assign New Task" (or "Reassign: {task title}" when [reassignTask] is given).
 * Public and reusable: the Task Assignment page and later dashboards open it. It does its own saving, audit entry,
 * notification and toasts. Everything the person typed is thrown away each time it opens.
 */
@Composable
fun TaskAssignDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    defaults: TaskDefaults? = null,
    reassignTask: ReassignTarget? = null,
    onDone: () -> Unit = {}
) {
    if (!open) return
    TaskAssignDialogContent(onDismiss, defaults, reassignTask, onDone)
}

@Composable
private fun TaskAssignDialogContent(
    onDismiss: () -> Unit,
    defaults: TaskDefaults?,
    reassignTask: ReassignTarget?,
    onDone: () -> Unit,
    vm: TaskAssignViewModel = hiltViewModel()
) {
    val staffState by vm.staff.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    // Leaving composition when the dialog closes is what resets this state for the next opening.
    var form by remember { mutableStateOf(newTaskAssignForm(defaults)) }

    val reassign = reassignTask != null
    val allStaff = (staffState as? Resource.Success)?.data.orEmpty()
    val pool = TaskRules.assignablePool(allStaff, form.type, form.suggestedOnly, form.search)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    NbmsDialog(
        title = if (reassignTask != null) "Reassign: ${reassignTask.title}" else "Assign New Task",
        onDismiss = { if (!saving) onDismiss() },
        confirmText = if (reassign) "Reassign" else "Assign Task",
        onConfirm = {
            vm.submit(form, defaults, reassignTask, allStaff) {
                onDismiss()
                onDone()
            }
        },
        loading = saving
    ) {
        if (!reassign) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsDropdown(
                    label = "Task Type",
                    options = TaskType.entries.toList(),
                    selected = form.type,
                    onSelect = { form = form.withType(it) },
                    optionLabel = { it.label },
                    modifier = Modifier.weight(1f),
                    enabled = !saving
                )
                NbmsDropdown(
                    label = "Priority",
                    options = TaskPriority.entries.toList(),
                    selected = form.priority,
                    onSelect = { form = form.copy(priority = it) },
                    optionLabel = { it.label },
                    modifier = Modifier.weight(1f),
                    enabled = !saving
                )
            }
            NbmsTextField(
                value = form.title,
                onValueChange = { form = form.copy(title = it) },
                label = "Title *",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "e.g. Clean Room 204 after checkout",
                enabled = !saving
            )
            NbmsTextField(
                value = form.description,
                onValueChange = { form = form.copy(description = it) },
                label = "Instructions / Notes",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Optional details for the assigned staff",
                singleLine = false,
                enabled = !saving
            )
            NbmsTimePickerField(
                label = "Due Time (optional, today)",
                value = form.dueTime?.toKotlinLocalTime(),
                onChange = { form = form.copy(dueTime = it.toJavaLocalTime()) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "No due time",
                enabled = !saving
            )
            if (form.dueTime != null) {
                NbmsButton(
                    text = "Clear due time",
                    onClick = { form = form.copy(dueTime = null) },
                    variant = ButtonVariant.Link,
                    size = ButtonSize.Sm,
                    enabled = !saving
                )
            }
        }

        // Assign To
        Text("Assign To *", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Suggested",
                onClick = { form = form.copy(suggestedOnly = true) },
                variant = if (form.suggestedOnly) ButtonVariant.Default else ButtonVariant.Outline,
                size = ButtonSize.Sm,
                enabled = !saving
            )
            NbmsButton(
                text = "All Staff",
                onClick = { form = form.copy(suggestedOnly = false) },
                variant = if (!form.suggestedOnly) ButtonVariant.Default else ButtonVariant.Outline,
                size = ButtonSize.Sm,
                enabled = !saving
            )
        }
        SearchBar(
            value = form.search,
            onValueChange = { form = form.copy(search = it) },
            placeholder = "Search staff…"
        )
        val listShape = MaterialTheme.shapes.small
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.nbms.cardBorder, listShape)
        ) {
            when {
                staffState is Resource.Loading ->
                    Text("Loading staff…", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(12.dp))
                staffState is Resource.Error ->
                    Text("We couldn't load staff.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                pool.isEmpty() ->
                    Text("No matching staff found.", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(12.dp))
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(pool, key = { it.id }) { person ->
                        val checked = person.id in form.selectedIds
                        val toggle = {
                            form = form.copy(selectedIds = if (checked) form.selectedIds - person.id else form.selectedIds + person.id)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !saving, onClick = toggle)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NbmsCheckbox(checked = checked, onCheckedChange = { toggle() }, enabled = !saving)
                            Text(
                                person.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            NbmsBadge(text = Role.fromKey(person.role)?.label ?: person.role, tone = BadgeTone.Outline)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Text(
            "${form.selectedIds.size} staff member(s) selected",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
    }
}
