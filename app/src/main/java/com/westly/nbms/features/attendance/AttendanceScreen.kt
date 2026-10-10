package com.westly.nbms.features.attendance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.StatCard
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate

private val WIDE_MIN_WIDTH = 600.dp
private val TABLE_MIN_WIDTH = 720.dp

private const val ACCESS_TITLE = "Access Restricted"
private const val ACCESS_MESSAGE = "You don't have permission to view the attendance register."
private const val REGISTER_SUBTITLE = "Complete attendance history for all staff, grouped by date"

/**
 * Attendance Register (`attendance`): today's numbers, filters and the history grouped by day. Read-only.
 * Super Admin, Manager, Receptionist and Operations Manager may open it; only Super Admin and Receptionist see
 * the Record Attendance button (that page is Phase 28B).
 */
@Composable
fun AttendanceScreen(session: SessionState.SignedIn) {
    if (!attendanceCanView(session.user.role)) {
        AccessRestricted()
    } else {
        AttendanceRegister(canRecord = attendanceCanRecord(session.user.role))
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
private fun AttendanceRegister(canRecord: Boolean) {
    val vm: AttendanceViewModel = hiltViewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val toggled by vm.toggled.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RegisterHeader(canRecord = canRecord, onRecord = vm::openRecord)
        SummaryCards(ui.summary)
        FilterRow(
            filters = filters,
            onSearch = vm::setSearch,
            onFrom = vm::setFrom,
            onTo = vm::setTo
        )
        when (val view = ui.view) {
            is AttendanceView.Loading -> LoadingState()
            is AttendanceView.Error -> ErrorState(view.message, onRetry = vm::retry)
            is AttendanceView.Ready -> {
                if (view.groups.isEmpty()) {
                    NoRecords()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        view.groups.forEachIndexed { index, group ->
                            // The most recent day starts open; a day the person has toggled keeps their choice.
                            val expanded = toggled[group.dateKey] ?: (index == 0)
                            DayCard(
                                group = group,
                                isToday = group.dateKey == ui.todayKey,
                                expanded = expanded,
                                onToggle = { vm.toggleDay(group.dateKey, expanded) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- header ----

@Composable
private fun RegisterHeader(canRecord: Boolean, onRecord: () -> Unit) {
    val button: @Composable () -> Unit = {
        NbmsButton(text = "Record Attendance", onClick = onRecord, leadingIcon = NbmsIcons.Plus)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_MIN_WIDTH) {
            PageHeader(title = "Attendance Register", subtitle = REGISTER_SUBTITLE) {
                if (canRecord) button()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageHeader(title = "Attendance Register", subtitle = REGISTER_SUBTITLE)
                if (canRecord) button()
            }
        }
    }
}

// ---- today's summary ----

@Composable
private fun SummaryCards(summary: TodaySummary) {
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> StatCard("Present Today", summary.present.toString(), NbmsIcons.CheckCircle, BadgeTone.Success, modifier = m) },
        { m -> StatCard("Absent Today", summary.absent.toString(), NbmsIcons.XCircle, BadgeTone.Destructive, modifier = m) },
        { m -> StatCard("Late Today", summary.late.toString(), NbmsIcons.Clock, BadgeTone.Warning, modifier = m) },
        { m -> StatCard("Total Staff", summary.totalStaff.toString(), NbmsIcons.Users, BadgeTone.Default, modifier = m) }
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Two columns on phones, four on tablets.
        val perRow = if (maxWidth >= TABLE_MIN_WIDTH) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.chunked(perRow).forEach { rowCards ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCards.forEach { card -> card(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---- filters ----

@Composable
private fun FilterRow(
    filters: AttendanceFilters,
    onSearch: (String) -> Unit,
    onFrom: (java.time.LocalDate) -> Unit,
    onTo: (java.time.LocalDate) -> Unit
) {
    val search: @Composable (Modifier) -> Unit = { m ->
        SearchBar(value = filters.search, onValueChange = onSearch, placeholder = "Search staff…", modifier = m)
    }
    val fromField: @Composable (Modifier) -> Unit = { m ->
        NbmsDatePickerField(label = "From", value = filters.from.toKotlinLocalDate(), onChange = { onFrom(it.toJavaLocalDate()) }, modifier = m)
    }
    val toField: @Composable (Modifier) -> Unit = { m ->
        NbmsDatePickerField(label = "To", value = filters.to.toKotlinLocalDate(), onChange = { onTo(it.toJavaLocalDate()) }, modifier = m)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_MIN_WIDTH) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                search(Modifier.weight(1f))
                fromField(Modifier.weight(0.6f))
                toField(Modifier.weight(0.6f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                search(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    fromField(Modifier.weight(1f))
                    toField(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---- empty state ----

@Composable
private fun NoRecords() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.ClipboardCheck, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp).alpha(0.3f))
        Text(MSG_ATTENDANCE_EMPTY, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ---- one day ----

@Composable
private fun DayCard(group: DayGroup, isToday: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val presentCount = group.records.count { AttendanceStatus.fromKey(it.status) == AttendanceStatus.PRESENT }
    val absentCount = group.records.count { AttendanceStatus.fromKey(it.status) == AttendanceStatus.ABSENT }

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        attendanceDayLabel(group.dateKey),
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface
                    )
                    if (isToday) NbmsBadge("TODAY")
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) NbmsIcons.ChevronUp else NbmsIcons.ChevronDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                    )
                }
                FlowChips(horizontalSpacing = 12.dp, verticalSpacing = 2.dp) {
                    Text(attendanceRecordCount(group.records.size), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                    Text("$presentCount present", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = nbms.success)
                    if (absentCount > 0) {
                        Text("$absentCount absent", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = nbms.destructive)
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(color = nbms.cardBorder)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= TABLE_MIN_WIDTH) {
                        RecordsTable(group.records)
                    } else {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.records.forEach { RecordCard(it) }
                        }
                    }
                }
            }
        }
    }
}

/** The status as a coloured pill: green, red, yellow, blue, orange. */
@Composable
private fun StatusPill(statusKey: String) {
    val status = AttendanceStatus.fromKey(statusKey)
    val colorKey = when (status) {
        AttendanceStatus.PRESENT -> "available"
        AttendanceStatus.ABSENT -> "occupied"
        AttendanceStatus.LATE -> "cleaning"
        AttendanceStatus.LEAVE -> "reserved"
        AttendanceStatus.HALF_DAY -> "maintenance"
    }
    NbmsPill(status.label, MaterialTheme.nbms.statusPill(colorKey))
}

// ---- phone: one card per person ----

@Composable
private fun RecordCard(record: AttendanceRecord) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.staffName, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(attendanceRoleLabel(record.staffRole), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                }
                StatusPill(record.status)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TimeBlock("Check In", record.clockIn)
                TimeBlock("Check Out", record.clockOut)
            }
            Text(
                "Notes: ${attendanceOrDash(record.notes)}",
                fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimeBlock(label: String, value: String?) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
        Text(attendanceOrDash(value), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
    }
}

// ---- tablet: a table ----

private val COLUMN_WEIGHTS = listOf(2f, 1.5f, 1f, 1f, 1f, 2.5f)
private val COLUMN_TITLES = listOf("Staff", "Role", "Status", "Check In", "Check Out", "Notes")

@Composable
private fun RecordsTable(records: List<AttendanceRecord>) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            COLUMN_TITLES.forEachIndexed { i, title ->
                Text(
                    title, modifier = Modifier.weight(COLUMN_WEIGHTS[i]),
                    fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant
                )
            }
        }
        records.forEach { r ->
            HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(r.staffName, Modifier.weight(COLUMN_WEIGHTS[0]), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                Text(attendanceRoleLabel(r.staffRole), Modifier.weight(COLUMN_WEIGHTS[1]), fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface)
                Row(Modifier.weight(COLUMN_WEIGHTS[2])) { StatusPill(r.status) }
                Text(attendanceOrDash(r.clockIn), Modifier.weight(COLUMN_WEIGHTS[3]), fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface)
                Text(attendanceOrDash(r.clockOut), Modifier.weight(COLUMN_WEIGHTS[4]), fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface)
                Text(attendanceOrDash(r.notes), Modifier.weight(COLUMN_WEIGHTS[5]), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}
