package com.westly.nbms.features.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.Pagination
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState

private val GYM_TABLET_WIDTH = 600.dp
private val GYM_THREE_COLUMN_WIDTH = 900.dp
private const val GYM_LOG_PAGE_SIZE = 15

/** A page title row with a tinted icon tile on the left (the Gym pages' version of `PageHeader`). */
@Composable
internal fun GymPageHeader(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Small centred spinner for a section that is still loading. */
@Composable
internal fun GymMiniSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
    }
}

/** A section title with a 16dp icon: "Currently In the Gym (3)". */
@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The Check-In/Out page (`gym/checkin`): search a member and check them in, check out people who are in, and today's log. */
@Composable
fun GymCheckInScreen(session: SessionState.SignedIn) {
    val vm: GymCheckInViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val timezone = session.business.timezone

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= GYM_TABLET_WIDTH
        val columns = when {
            maxWidth >= GYM_THREE_COLUMN_WIDTH -> 3
            wide -> 2
            else -> 1
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GymPageHeader(
                icon = NbmsIcons.Dumbbell,
                title = "Gym Check-In / Check-Out",
                subtitle = "Search for a member to check them in, or check out someone currently in the gym."
            )
            if (ui.failed) {
                ErrorState(MSG_GYM_DATA_LOAD_FAILED, onRetry = vm::retry)
            } else {
                SearchCard(
                    query = query,
                    onQuery = vm::setQuery,
                    ui = ui,
                    busy = busy,
                    onCheckIn = vm::checkIn
                )
                InGymSection(ui = ui, timezone = timezone, columns = columns, busy = busy, onCheckOut = vm::checkOut)
                TodayLogSection(ui = ui, timezone = timezone, wide = wide)
            }
        }
    }
}

// ── search ──

@Composable
private fun SearchCard(
    query: String,
    onQuery: (String) -> Unit,
    ui: CheckInUi,
    busy: Set<String>,
    onCheckIn: (GymMember) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = query, onValueChange = onQuery, placeholder = "Search member by name, phone, or room…")
            if (ui.searching) {
                when {
                    ui.membersLoading -> Text(
                        "Searching…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ui.matches.isEmpty() -> Text(
                        "No members found. Register them from the Members page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Column(Modifier.fillMaxWidth()) {
                        ui.matches.forEachIndexed { index, match ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                            MatchRow(match, busy = "in:${match.member.id}" in busy, onCheckIn = { onCheckIn(match.member) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: MemberMatch, busy: Boolean, onCheckIn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                match.member.name.trim().ifEmpty { "—" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    match.member.packageName.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                MembershipStatusPill(match.status)
            }
        }
        if (match.inGym) {
            Text(
                "Already checked in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            NbmsButton(
                text = "Check In",
                onClick = onCheckIn,
                size = ButtonSize.Sm,
                leadingIcon = Icons.AutoMirrored.Outlined.Login,
                loading = busy,
                enabled = match.status == MembershipStatus.ACTIVE
            )
        }
    }
}

// ── currently in the gym ──

@Composable
private fun InGymSection(
    ui: CheckInUi,
    timezone: String,
    columns: Int,
    busy: Set<String>,
    onCheckOut: (GymVisit) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(NbmsIcons.Users, "Currently In the Gym (${ui.inGym.size})")
        when {
            ui.visitsLoading -> GymMiniSpinner()
            ui.inGym.isEmpty() -> NbmsCard(Modifier.fillMaxWidth()) {
                Text(
                    "Nobody is currently checked in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.inGym.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { visit ->
                            Box(Modifier.weight(1f)) {
                                InGymCard(visit, timezone, busy = "out:${visit.id}" in busy, onCheckOut = { onCheckOut(visit) })
                            }
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InGymCard(visit: GymVisit, timezone: String, busy: Boolean, onCheckOut: () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    visit.memberName.trim().ifEmpty { "—" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "In since ${GymLogic.timeLabel(visit.checkInAt.toGymInstant(), timezone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NbmsButton(
                text = "Check Out",
                onClick = onCheckOut,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                leadingIcon = NbmsIcons.LogOut,
                loading = busy
            )
        }
    }
}

// ── today's visitor log ──

@Composable
private fun TodayLogSection(ui: CheckInUi, timezone: String, wide: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(NbmsIcons.History, "Today's Visitor Log (${ui.today.size})")
        when {
            ui.visitsLoading -> GymMiniSpinner()
            ui.today.isEmpty() -> NbmsCard(Modifier.fillMaxWidth()) {
                Text(
                    "No visits recorded yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
            else -> PagedRows(ui.today) { visits ->
                NbmsCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        if (wide) {
                            LogHeaderRow()
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                        visits.forEachIndexed { index, visit ->
                            if (wide) LogTableRow(visit, timezone) else LogCardRow(visit, timezone)
                            if (index < visits.lastIndex) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                        }
                    }
                }
            }
        }
    }
}

/** Shows [items] [GYM_LOG_PAGE_SIZE] at a time with page controls underneath (nothing extra for a single page). */
@Composable
internal fun <T> PagedRows(items: List<T>, content: @Composable (List<T>) -> Unit) {
    var page by remember(items.size) { mutableIntStateOf(1) }
    val pageCount = if (items.isEmpty()) 1 else (items.size + GYM_LOG_PAGE_SIZE - 1) / GYM_LOG_PAGE_SIZE
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * GYM_LOG_PAGE_SIZE
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content(items.subList(start, minOf(items.size, start + GYM_LOG_PAGE_SIZE)))
        Pagination(page = safePage, pageCount = pageCount, onPage = { page = it.coerceIn(1, pageCount) })
    }
}

private val LOG_WEIGHTS = listOf(2.0f, 1.0f, 1.0f, 1.6f)
private val LOG_TITLES = listOf("Member", "Check-In", "Check-Out", "By")

@Composable
private fun LogHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LOG_WEIGHTS.forEachIndexed { i, w ->
            Text(
                LOG_TITLES[i],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(w).padding(horizontal = 4.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LogTableRow(visit: GymVisit, timezone: String) {
    val body = MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            visit.memberName.trim().ifEmpty { "—" },
            style = body.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(LOG_WEIGHTS[0]).padding(horizontal = 4.dp)
        )
        Text(
            GymLogic.timeLabel(visit.checkInAt.toGymInstant(), timezone),
            style = body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(LOG_WEIGHTS[1]).padding(horizontal = 4.dp)
        )
        Text(
            GymLogic.timeLabel(visit.checkOutAt.toGymInstant(), timezone),
            style = body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(LOG_WEIGHTS[2]).padding(horizontal = 4.dp)
        )
        Text(
            visit.checkedInByName.ifBlank { "—" },
            style = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(LOG_WEIGHTS[3]).padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun LogCardRow(visit: GymVisit, timezone: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            visit.memberName.trim().ifEmpty { "—" },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "In ${GymLogic.timeLabel(visit.checkInAt.toGymInstant(), timezone)} · Out ${GymLogic.timeLabel(visit.checkOutAt.toGymInstant(), timezone)}",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
        Text("By ${visit.checkedInByName.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
