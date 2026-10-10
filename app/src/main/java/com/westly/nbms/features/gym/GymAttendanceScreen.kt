package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState

private val ATTENDANCE_TABLET_WIDTH = 600.dp
private val ATT_WEIGHTS = listOf(1.2f, 2.0f, 1.0f, 1.0f, 1.0f, 1.5f)
private val ATT_TITLES = listOf("Date", "Member", "Check-In", "Check-Out", "Duration", "Staff")

/** The Attendance page (`gym/attendance`): the full check-in / check-out log, filtered by day and member name. */
@Composable
fun GymAttendanceScreen(session: SessionState.SignedIn) {
    val vm: GymAttendanceViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val timezone = session.business.timezone

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= ATTENDANCE_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GymPageHeader(
                icon = NbmsIcons.CalendarClock,
                title = "Gym Attendance History",
                subtitle = "Full check-in / check-out log across all members."
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    NbmsDatePickerField(
                        label = "Date",
                        value = filters.date,
                        onChange = { vm.setDate(it) },
                        modifier = Modifier.weight(1f)
                    )
                    if (filters.date != null) {
                        NbmsButton(
                            text = "Clear date",
                            onClick = { vm.setDate(null) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            leadingIcon = NbmsIcons.Close
                        )
                    }
                }
                SearchBar(value = filters.search, onValueChange = vm::setSearch, placeholder = "Search by member name…")
            }
            when (val v = view) {
                is AttendanceView.Loading -> LoadingState()
                is AttendanceView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is AttendanceView.Ready -> {
                    if (v.rows.isEmpty()) {
                        EmptyState(icon = NbmsIcons.CalendarClock, title = "No visits match your filters.", message = "")
                    } else {
                        PagedRows(v.rows) { visible ->
                            NbmsCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    if (wide) {
                                        HeaderRow()
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                    }
                                    visible.forEachIndexed { i, visit ->
                                        if (wide) TableRow(visit, timezone) else CardRow(visit, timezone)
                                        if (i < visible.lastIndex) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StillIn() {
    Text(
        "Still in",
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.nbms.success
    )
}

private fun durationOf(visit: GymVisit): String =
    GymLogic.visitDurationLabel(visit.checkInAt.toGymInstant(), visit.checkOutAt.toGymInstant())

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ATT_WEIGHTS.forEachIndexed { i, w ->
            Text(
                ATT_TITLES[i],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(w).padding(horizontal = 4.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TableRow(visit: GymVisit, timezone: String) {
    val body = MaterialTheme.typography.bodyMedium
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(visit.dateKey.ifBlank { "—" }, style = body, color = onSurface, modifier = Modifier.weight(ATT_WEIGHTS[0]).padding(horizontal = 4.dp))
        Text(
            visit.memberName.trim().ifEmpty { "—" },
            style = body.copy(fontWeight = FontWeight.Medium),
            color = onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(ATT_WEIGHTS[1]).padding(horizontal = 4.dp)
        )
        Text(
            GymLogic.timeLabel(visit.checkInAt.toGymInstant(), timezone),
            style = body, color = onSurface, modifier = Modifier.weight(ATT_WEIGHTS[2]).padding(horizontal = 4.dp)
        )
        Row(Modifier.weight(ATT_WEIGHTS[3]).padding(horizontal = 4.dp)) {
            if (visit.checkOutAt == null) StillIn()
            else Text(GymLogic.timeLabel(visit.checkOutAt.toGymInstant(), timezone), style = body, color = onSurface)
        }
        Text(durationOf(visit), style = body, color = onSurface, modifier = Modifier.weight(ATT_WEIGHTS[4]).padding(horizontal = 4.dp))
        Text(
            visit.checkedInByName.ifBlank { "—" },
            style = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(ATT_WEIGHTS[5]).padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun CardRow(visit: GymVisit, timezone: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                visit.memberName.trim().ifEmpty { "—" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(visit.dateKey.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("In ${GymLogic.timeLabel(visit.checkInAt.toGymInstant(), timezone)} ·", style = MaterialTheme.typography.bodySmall, color = muted)
            if (visit.checkOutAt == null) StillIn()
            else Text("Out ${GymLogic.timeLabel(visit.checkOutAt.toGymInstant(), timezone)}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text(
            "${durationOf(visit)} · ${visit.checkedInByName.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
    }
}
