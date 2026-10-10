package com.westly.nbms.features.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSegmentedTabs
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.StatCard
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import java.time.LocalDate

private val VIEW_TABS = listOf("Day", "Week", "Month")

/**
 * Shift Scheduling (`shifts`): a day / week / month roster for one role, with the live "on duty" banner and a form to
 * schedule, edit and cancel shifts. For Super Admin, Manager and Operations Manager.
 */
@Composable
fun ShiftSchedulingScreen(session: SessionState.SignedIn) {
    ShiftSchedulingContent()
}

@Composable
private fun ShiftSchedulingContent(vm: ShiftSchedulingViewModel = hiltViewModel()) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<ShiftFormTarget?>(null) }

    val ready = (view as? ShiftsView.Ready)?.board
    // A board belongs to one role and range; never show an old one while the new one is loading.
    val board = ready?.takeIf { it.role == selection.role && it.mode == selection.mode && it.anchor == selection.anchor }
    val roleLabel = selection.role.label

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                NbmsIcons.CalendarClock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).size(24.dp)
            )
            PageHeader(
                title = "Shift Scheduling",
                subtitle = "Build and manage rosters for every multi-staff role, in real time.",
                modifier = Modifier.weight(1f)
            ) {
                NbmsButton(
                    text = "Schedule Shift",
                    onClick = { target = ShiftFormTarget.New(vm.today()) },
                    size = ButtonSize.Sm,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        // Controls
        NbmsDropdown(
            label = "Role",
            options = shiftRoleOptions(),
            selected = selection.role,
            onSelect = vm::setRole,
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth()
        )
        NbmsSegmentedTabs(
            tabs = VIEW_TABS,
            selected = ShiftViewMode.entries.indexOf(selection.mode),
            onSelect = { vm.setMode(ShiftViewMode.entries[it]) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NbmsButton(
                text = "Previous",
                onClick = vm::previous,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Icon,
                leadingIcon = Icons.Outlined.ChevronLeft
            )
            Text(
                rangeLabel(selection.mode, selection.anchor),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            NbmsButton(
                text = "Next",
                onClick = vm::next,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Icon,
                leadingIcon = NbmsIcons.ChevronRight
            )
            NbmsButton(text = "Today", onClick = vm::goToToday, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
        }

        // Stat cards
        StatCard(
            label = "$roleLabel on Roster",
            value = (board?.stats?.onRoster ?: 0).toString(),
            icon = NbmsIcons.Users,
            tone = BadgeTone.Default
        )
        StatCard(
            label = "Shifts in View",
            value = (board?.stats?.inView ?: 0).toString(),
            icon = NbmsIcons.CalendarClock,
            tone = BadgeTone.Gold
        )
        StatCard(
            label = "On Duty Right Now",
            value = (board?.stats?.onDutyNow ?: 0).toString(),
            icon = NbmsIcons.UserCheck,
            tone = BadgeTone.Success
        )

        when {
            view is ShiftsView.Error -> ErrorState(MSG_SHIFT_LOAD_ERROR, onRetry = vm::retry)
            board == null -> LoadingState()
            else -> {
                if (board.onDuty.isNotEmpty()) OnDutyBanner(roleLabel, board.onDuty)
                Calendar(board, onAdd = { target = ShiftFormTarget.New(it) }, onOpen = { target = ShiftFormTarget.Edit(it) })
            }
        }
    }

    val open = target
    if (open != null) {
        ShiftFormSheet(
            target = open,
            role = selection.role,
            staff = board?.staff.orEmpty(),
            vm = vm,
            onDismiss = { target = null }
        )
    }
}

@Composable
private fun OnDutyBanner(roleLabel: String, onDuty: List<Shift>) {
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(nbms.successContainer)
            .border(1.dp, nbms.success.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Currently On Duty — $roleLabel",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = nbms.onSuccessContainer
        )
        FlowChips(Modifier.fillMaxWidth(), horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            onDuty.forEach { shift ->
                NbmsBadge(text = onDutyChipText(shift), tone = BadgeTone.Success)
            }
        }
    }
}

@Composable
private fun Calendar(board: ShiftBoard, onAdd: (LocalDate) -> Unit, onOpen: (Shift) -> Unit) {
    val compact = board.mode == ShiftViewMode.MONTH
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Month view is a compact grid: 2 columns on phones, 3 on tablets, 7 on wide screens. Day and week are one column.
        val columns = when {
            !compact -> 1
            maxWidth < 600.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 7
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            board.days.chunked(columns).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    week.forEach { day ->
                        Box(Modifier.weight(1f)) {
                            DayCard(
                                day = day,
                                isToday = day.date == board.today,
                                compact = compact,
                                onAdd = { onAdd(day.date) },
                                onOpen = onOpen
                            )
                        }
                    }
                    repeat(columns - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: DayShifts, isToday: Boolean, compact: Boolean, onAdd: () -> Unit, onOpen: (Shift) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isToday) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    dayTitle(day.date, compact),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isToday) NbmsBadge(text = "Today", tone = BadgeTone.Default)
                NbmsButton(
                    text = "Add shift on ${dayTitle(day.date, compact = true)}",
                    onClick = onAdd,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leadingIcon = NbmsIcons.Plus
                )
            }
            if (day.shifts.isEmpty()) {
                Text("No shifts scheduled", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                day.shifts.forEach { shift -> ShiftRow(shift, compact, onClick = { onOpen(shift) }) }
            }
        }
    }
}

@Composable
private fun ShiftRow(shift: Shift, compact: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            shiftRowTitle(shift, compact),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            shiftTimeRangeLabel(shift),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
