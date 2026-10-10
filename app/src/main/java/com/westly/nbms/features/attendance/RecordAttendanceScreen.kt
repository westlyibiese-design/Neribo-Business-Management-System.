package com.westly.nbms.features.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate

private val WIDE_MIN_WIDTH = 600.dp

/** Cards below this width (phones), a table at or above it (tablets). */
private val TABLE_MIN_WIDTH = 840.dp

private const val ACCESS_TITLE = "Access Restricted"
private const val ACCESS_MESSAGE = "You don't have permission to record attendance."
private const val RECORD_TITLE = "Record Attendance"
private const val RECORD_SUBTITLE =
    "One record per staff member per day — check them in now, and check them out later without losing the entry."
private const val NO_STAFF_MESSAGE = "No active staff found"
private const val NO_MATCH_MESSAGE = "No staff match your search"

/**
 * Record Attendance (`attendance/record`): Super Admin and Receptionist check staff in and out, or set a status,
 * one record per person per day. Everyone else is turned away by the route guard (and again here).
 */
@Composable
fun RecordAttendanceScreen(session: SessionState.SignedIn) {
    if (!attendanceCanRecord(session.user.role)) {
        AccessRestricted()
    } else {
        RecordAttendanceContent()
    }
}

@Composable
private fun AccessRestricted() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.Shield, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp).alpha(0.5f))
        Text(ACCESS_TITLE, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, textAlign = TextAlign.Center)
        Text(ACCESS_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecordAttendanceContent() {
    val vm: RecordAttendanceViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pinEnding by vm.pinEnding.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RecordHeader(ui = ui, onDate = vm::setDate)
        RecordToolbar(ui = ui, onSearch = vm::setSearch, onSaveAll = vm::saveAllEdited)
        if (ui.loadFailed) LoadErrorBox(onRetry = vm::retry)

        when {
            ui.loading -> LoadingState()
            ui.rows.isEmpty() -> if (!ui.loadFailed) NoStaff(searching = ui.search.isNotBlank())
            else -> StaffRows(ui = ui, vm = vm)
        }

        NbmsButton(
            text = "View Attendance Register",
            onClick = vm::openRegister,
            variant = ButtonVariant.Outline,
            leadingIcon = NbmsIcons.ClipboardCheck
        )
    }

    if (pinEnding) PinSessionEndingOverlay()
}

// ---- header: title and the date ----

@Composable
private fun RecordHeader(ui: RecordUiState, onDate: (java.time.LocalDate) -> Unit) {
    val dateField: @Composable (Modifier) -> Unit = { m ->
        NbmsDatePickerField(
            label = "Date",
            value = ui.date.toKotlinLocalDate(),
            onChange = { onDate(it.toJavaLocalDate()) },
            modifier = m
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_MIN_WIDTH) {
            PageHeader(title = RECORD_TITLE, subtitle = RECORD_SUBTITLE) { dateField(Modifier.width(200.dp)) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageHeader(title = RECORD_TITLE, subtitle = RECORD_SUBTITLE)
                dateField(Modifier.fillMaxWidth())
            }
        }
    }
}

// ---- search and Save All Edited Rows ----

@Composable
private fun RecordToolbar(ui: RecordUiState, onSearch: (String) -> Unit, onSaveAll: () -> Unit) {
    val search: @Composable (Modifier) -> Unit = { m ->
        SearchBar(value = ui.search, onValueChange = onSearch, placeholder = "Search staff…", modifier = m)
    }
    val saveAll: @Composable (Modifier) -> Unit = { m ->
        NbmsButton(
            text = "Save All Edited Rows",
            onClick = onSaveAll,
            modifier = m,
            variant = ButtonVariant.Outline,
            loading = ui.savingAll,
            leadingIcon = Icons.Outlined.Save
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_MIN_WIDTH) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                search(Modifier.weight(1f))
                saveAll(Modifier)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                search(Modifier.fillMaxWidth())
                saveAll(Modifier.fillMaxWidth())
            }
        }
    }
}

// ---- messages ----

@Composable
private fun LoadErrorBox(onRetry: () -> Unit) {
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(nbms.destructiveContainer)
            .border(1.dp, nbms.destructiveBorder, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = nbms.onDestructiveContainer, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(MSG_RECORD_LOAD_FAILED, fontSize = 14.sp, lineHeight = 20.sp, color = nbms.onDestructiveContainer)
            NbmsButton(
                text = "Retry",
                onClick = onRetry,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                leadingIcon = NbmsIcons.Refresh
            )
        }
    }
}

@Composable
private fun NoStaff(searching: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.Users, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp).alpha(0.3f))
        Text(
            if (searching) NO_MATCH_MESSAGE else NO_STAFF_MESSAGE,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ---- the rows ----

@Composable
private fun StaffRows(ui: RecordUiState, vm: RecordAttendanceViewModel) {
    val actions = RowActions(
        onStatus = vm::setStatus,
        onClockIn = vm::setClockIn,
        onClockOut = vm::setClockOut,
        onNotes = vm::setNotes,
        onCheckInNow = vm::checkInNow,
        onCheckOutNow = vm::checkOutNow,
        onSave = vm::save
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= TABLE_MIN_WIDTH) {
            StaffTable(ui.rows, actions)
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.rows.forEach { StaffCard(it, actions) }
            }
        }
    }
}

/** Everything a row can do, handed down so the cards and the table share it. */
private class RowActions(
    val onStatus: (String, AttendanceStatus) -> Unit,
    val onClockIn: (String, String) -> Unit,
    val onClockOut: (String, String) -> Unit,
    val onNotes: (String, String) -> Unit,
    val onCheckInNow: (String) -> Unit,
    val onCheckOutNow: (String) -> Unit,
    val onSave: (String) -> Unit
)

/** The name, with a green check when this person already has a record for the chosen day, and the role under it. */
@Composable
private fun StaffName(item: RecordRowUi, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.staff.name,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (item.existing != null) {
                Icon(
                    NbmsIcons.CheckCircle,
                    contentDescription = "Already recorded for this day",
                    tint = MaterialTheme.nbms.success,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(attendanceRoleLabel(item.staff.role), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusField(item: RecordRowUi, actions: RowActions, label: String, modifier: Modifier = Modifier) {
    NbmsDropdown(
        label = label,
        options = AttendanceStatus.entries,
        selected = item.row.status,
        onSelect = { actions.onStatus(item.staff.id, it) },
        optionLabel = { it.label },
        modifier = modifier
    )
}

@Composable
private fun NotesField(item: RecordRowUi, actions: RowActions, label: String, modifier: Modifier = Modifier) {
    NbmsTextField(
        value = item.row.notes,
        onValueChange = { actions.onNotes(item.staff.id, it) },
        label = label,
        modifier = modifier,
        placeholder = "Optional"
    )
}

/**
 * A time field with its small "now" button (and a clear button once a time is set). [label] is empty inside the table,
 * where the column title says what it is.
 */
@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    nowIcon: androidx.compose.ui.graphics.vector.ImageVector,
    nowLabel: String,
    clearLabel: String,
    onChange: (String) -> Unit,
    onNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        NbmsTimePickerField(
            label = label,
            value = attendanceParseTime(value),
            onChange = { onChange(attendanceFormatTime(it)) },
            modifier = Modifier.weight(1f),
            placeholder = "--:--"
        )
        if (value.isNotBlank()) {
            NbmsButton(
                text = clearLabel,
                onClick = { onChange("") },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Icon,
                leadingIcon = NbmsIcons.Close
            )
        }
        NbmsButton(
            text = nowLabel,
            onClick = onNow,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Icon,
            enabled = enabled,
            leadingIcon = nowIcon
        )
    }
}

@Composable
private fun CheckInField(item: RecordRowUi, actions: RowActions, label: String, modifier: Modifier = Modifier) {
    TimeField(
        label = label,
        value = item.row.clockIn,
        enabled = !item.saving,
        nowIcon = Icons.AutoMirrored.Outlined.Login,
        nowLabel = "Check in now",
        clearLabel = "Clear check-in time",
        onChange = { actions.onClockIn(item.staff.id, it) },
        onNow = { actions.onCheckInNow(item.staff.id) },
        modifier = modifier
    )
}

@Composable
private fun CheckOutField(item: RecordRowUi, actions: RowActions, label: String, modifier: Modifier = Modifier) {
    TimeField(
        label = label,
        value = item.row.clockOut,
        enabled = !item.saving,
        nowIcon = NbmsIcons.LogOut,
        nowLabel = "Check out now",
        clearLabel = "Clear check-out time",
        onChange = { actions.onClockOut(item.staff.id, it) },
        onNow = { actions.onCheckOutNow(item.staff.id) },
        modifier = modifier
    )
}

/** The row's Save button: a spinner while saving, a green check for two seconds after. */
@Composable
private fun RowSaveButton(item: RecordRowUi, actions: RowActions) {
    if (item.justSaved && !item.saving) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(NbmsIcons.Check, contentDescription = "Saved", tint = MaterialTheme.nbms.success, modifier = Modifier.size(20.dp))
        }
    } else {
        NbmsButton(
            text = "Save",
            onClick = { actions.onSave(item.staff.id) },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Icon,
            loading = item.saving,
            leadingIcon = NbmsIcons.ClipboardCheck
        )
    }
}

// ---- phone: one card per person ----

@Composable
private fun StaffCard(item: RecordRowUi, actions: RowActions) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StaffName(item, Modifier.weight(1f))
                RowSaveButton(item, actions)
            }
            StatusField(item, actions, label = "Status", modifier = Modifier.fillMaxWidth())
            CheckInField(item, actions, label = "Check In", modifier = Modifier.fillMaxWidth())
            CheckOutField(item, actions, label = "Check Out", modifier = Modifier.fillMaxWidth())
            NotesField(item, actions, label = "Notes", modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- tablet: a table ----

private val COLUMN_WEIGHTS = listOf(1.8f, 1.5f, 2.6f, 2.6f, 1.8f, 0.7f)
private val COLUMN_TITLES = listOf("Staff", "Status", "Check In", "Check Out", "Notes", "")

@Composable
private fun StaffTable(rows: List<RecordRowUi>, actions: RowActions) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                COLUMN_TITLES.forEachIndexed { i, title ->
                    Text(
                        title, modifier = Modifier.weight(COLUMN_WEIGHTS[i]),
                        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant
                    )
                }
            }
            rows.forEach { item ->
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StaffName(item, Modifier.weight(COLUMN_WEIGHTS[0]))
                    StatusField(item, actions, label = "", modifier = Modifier.weight(COLUMN_WEIGHTS[1]))
                    CheckInField(item, actions, label = "", modifier = Modifier.weight(COLUMN_WEIGHTS[2]))
                    CheckOutField(item, actions, label = "", modifier = Modifier.weight(COLUMN_WEIGHTS[3]))
                    NotesField(item, actions, label = "", modifier = Modifier.weight(COLUMN_WEIGHTS[4]))
                    Box(Modifier.weight(COLUMN_WEIGHTS[5]), contentAlignment = Alignment.CenterEnd) { RowSaveButton(item, actions) }
                }
            }
        }
    }
}

// ---- shared-device sign-out overlay (private to Record Attendance) ----

/** Full-screen dim layer shown for 2.5 seconds after a PIN user checks someone in or out, just before the session ends. */
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
