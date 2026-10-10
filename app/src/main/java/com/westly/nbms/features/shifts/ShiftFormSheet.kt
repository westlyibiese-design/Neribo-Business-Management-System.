package com.westly.nbms.features.shifts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCheckbox
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate

/** What the sheet opens for: a new shift on a date, or an existing shift to edit. */
internal sealed interface ShiftFormTarget {
    data class New(val date: LocalDate) : ShiftFormTarget
    data class Edit(val shift: Shift) : ShiftFormTarget
}

private fun String.toPickerTime(): LocalTime? = runCatching {
    val parts = split(":")
    LocalTime(parts[0].toInt(), parts[1].toInt())
}.getOrNull()

private fun LocalTime.toHhmm(): String = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

/**
 * "Schedule Shift · {Role}" (new) or "Edit Shift — {label}" (edit). Keeps what the person typed until it closes.
 * [staff] is the live list of active staff in [role]; the saving, toasts and closing are done through [vm].
 */
@Composable
internal fun ShiftFormSheet(
    target: ShiftFormTarget,
    role: Role,
    staff: List<ShiftStaff>,
    vm: ShiftSchedulingViewModel,
    onDismiss: () -> Unit
) {
    val editing = (target as? ShiftFormTarget.Edit)?.shift
    val saving by vm.saving.collectAsStateWithLifecycle()
    val conflict by vm.conflicts.collectAsStateWithLifecycle()
    val seriesOfferFor by vm.seriesOfferFor.collectAsStateWithLifecycle()

    // The shift's current person may have been suspended since; keep them selectable so the form still works.
    val staffOptions = remember(staff, editing) {
        if (editing != null && editing.staffId.isNotBlank() && staff.none { it.id == editing.staffId }) {
            listOf(ShiftStaff(editing.staffId, editing.staffName)) + staff
        } else {
            staff
        }
    }

    var form by remember(target) {
        mutableStateOf(
            when (target) {
                is ShiftFormTarget.New -> newShiftForm(target.date, staff)
                is ShiftFormTarget.Edit -> editShiftForm(target.shift, vm.today())
            }
        )
    }

    LaunchedEffect(target) {
        vm.clearConflicts()
        if (editing != null) vm.checkSeries(editing) else vm.clearSeriesOffer()
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.clearConflicts()
            vm.clearSeriesOffer()
        }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedStaff = resolveShiftStaff(form, staffOptions)

    NbmsBottomSheet(
        onDismiss = { if (!saving) onDismiss() },
        title = shiftSheetTitle(role, editing)
    ) {
        // Staff Member *
        if (staffOptions.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Staff Member *", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("No active staff in this role yet.", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        } else {
            NbmsDropdown(
                label = "Staff Member *",
                options = staffOptions,
                selected = selectedStaff,
                onSelect = { form = form.copy(staffId = it.id) },
                optionLabel = { it.name },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Select staff",
                enabled = !saving
            )
        }

        NbmsTextField(
            value = form.label,
            onValueChange = { form = form.copy(label = it) },
            label = "Shift Label *",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "e.g. Morning Shift",
            enabled = !saving
        )

        NbmsDatePickerField(
            label = if (editing != null) "Date" else "Start Date *",
            value = form.date.toKotlinLocalDate(),
            onChange = { form = form.copy(date = it.toJavaLocalDate()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editing == null && !saving
        )
        NbmsCheckbox(
            checked = form.endsNextDay,
            onCheckedChange = { form = form.copy(endsNextDay = it) },
            enabled = !saving,
            label = "Ends next day (overnight)"
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTimePickerField(
                label = "Start Time *",
                value = form.startTime.toPickerTime(),
                onChange = { form = form.copy(startTime = it.toHhmm()) },
                modifier = Modifier.weight(1f),
                enabled = !saving
            )
            NbmsTimePickerField(
                label = "End Time *",
                value = form.endTime.toPickerTime(),
                onChange = { form = form.copy(endTime = it.toHhmm()) },
                modifier = Modifier.weight(1f),
                enabled = !saving
            )
        }

        NbmsTextField(
            value = form.notes,
            onValueChange = { form = form.copy(notes = it) },
            label = "Notes",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Optional handover notes",
            singleLine = false,
            enabled = !saving
        )

        // Recurrence: new shifts only
        if (editing == null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NbmsDropdown(
                label = "Recurrence",
                options = RecurrenceType.entries.toList(),
                selected = form.recurrenceType,
                onSelect = { form = form.copy(recurrenceType = it) },
                optionLabel = ::recurrenceLabel,
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving
            )
            if (form.recurrenceType == RecurrenceType.WEEKLY) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEKDAY_LABELS.forEachIndexed { index, name ->
                        val on = index in form.weekdays
                        WeekdayPill(
                            text = name,
                            selected = on,
                            enabled = !saving,
                            onClick = {
                                form = form.copy(weekdays = if (on) form.weekdays - index else form.weekdays + index)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (form.recurrenceType != RecurrenceType.NONE) {
                NbmsDatePickerField(
                    label = "Repeat until (optional, max ~3 months / 60 shifts)",
                    value = form.until?.toKotlinLocalDate(),
                    onChange = { form = form.copy(until = it.toJavaLocalDate()) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "No end date",
                    enabled = !saving
                )
                if (form.until != null) {
                    NbmsButton(
                        text = "Clear end date",
                        onClick = { form = form.copy(until = null) },
                        variant = ButtonVariant.Link,
                        size = ButtonSize.Sm,
                        enabled = !saving
                    )
                }
            }
        }

        conflict?.let { ConflictBoxView(it) }

        // Buttons
        if (editing != null) {
            FlowChips(Modifier.fillMaxWidth()) {
                GhostDestructiveButton(
                    text = "Cancel This Shift",
                    enabled = !saving,
                    onClick = { vm.cancelThisShift(editing, onDismiss) }
                )
                if (seriesOfferFor == editing.id) {
                    GhostDestructiveButton(
                        text = "Cancel Series",
                        enabled = !saving,
                        onClick = { vm.cancelSeries(editing, onDismiss) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            NbmsButton(
                text = "Close",
                onClick = onDismiss,
                variant = ButtonVariant.Outline,
                enabled = !saving
            )
            NbmsButton(
                text = if (editing != null) "Save Changes" else "Schedule Shift",
                onClick = {
                    if (editing != null) vm.submitEdit(editing, form, staffOptions, onDismiss)
                    else vm.submitNew(form, role, staffOptions, onDismiss)
                },
                loading = saving
            )
        }
    }
}

@Composable
private fun ConflictBoxView(box: ConflictBox) {
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(nbms.destructiveContainer)
            .border(1.dp, nbms.destructiveBorder, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            box.heading,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = nbms.onDestructiveContainer
        )
        box.lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = nbms.onDestructiveContainer)
        }
        box.moreLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = nbms.onDestructiveContainer)
        }
        Text(
            box.footer,
            style = MaterialTheme.typography.bodySmall,
            color = nbms.onDestructiveContainer,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun WeekdayPill(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) scheme.primary else scheme.surface)
            .border(BorderStroke(1.dp, if (selected) scheme.primary else MaterialTheme.nbms.inputBorder), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = SemanticsRole.Checkbox },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) scheme.onPrimary else scheme.onSurface
        )
    }
}

/** Text-only red button for cancelling (there is no ghost-destructive variant in the design system). */
@Composable
private fun GhostDestructiveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 36.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = SemanticsRole.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.nbms.destructive
        )
    }
}
