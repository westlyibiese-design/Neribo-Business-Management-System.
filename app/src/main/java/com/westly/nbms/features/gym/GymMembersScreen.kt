package com.westly.nbms.features.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.design.NbmsPill

private val MEMBERS_TABLET_WIDTH = 600.dp

// Tailwind orange for the "{n}d left" note and the Suspend action; green for Reactivate.
private val Orange400 = Color(0xFFFB923C)
private val Orange600 = Color(0xFFEA580C)
private val Green400 = Color(0xFF4ADE80)
private val Green600 = Color(0xFF16A34A)

/** One entry of the status filter. [status] null is "All Statuses". */
private data class StatusOption(val status: MembershipStatus?, val label: String)

private val STATUS_OPTIONS: List<StatusOption> =
    listOf(StatusOption(null, "All Statuses")) + MembershipStatus.entries.map { StatusOption(it, it.label) }

/** Pill colours: active green, expired slate, suspended orange, cancelled red (dark variants at 30% like the other status pills). */
@Composable
private fun membershipStatusColors(status: MembershipStatus): PillColors {
    val dark = MaterialTheme.nbms.isDark
    return when (status) {
        MembershipStatus.ACTIVE ->
            if (dark) PillColors(Color(0xFF14532D).copy(alpha = 0.30f), Color(0xFF4ADE80)) else PillColors(Color(0xFFDCFCE7), Color(0xFF166534))
        MembershipStatus.EXPIRED ->
            if (dark) PillColors(Color(0xFF1E293B).copy(alpha = 0.60f), Color(0xFF94A3B8)) else PillColors(Color(0xFFF1F5F9), Color(0xFF334155))
        MembershipStatus.SUSPENDED ->
            if (dark) PillColors(Color(0xFF7C2D12).copy(alpha = 0.30f), Color(0xFFFB923C)) else PillColors(Color(0xFFFFEDD5), Color(0xFF9A3412))
        MembershipStatus.CANCELLED ->
            if (dark) PillColors(Color(0xFF7F1D1D).copy(alpha = 0.30f), Color(0xFFF87171)) else PillColors(Color(0xFFFEE2E2), Color(0xFF991B1B))
    }
}

/** The coloured status pill ("Active", "Expired", "Suspended", "Cancelled"). */
@Composable
internal fun MembershipStatusPill(status: MembershipStatus, modifier: Modifier = Modifier) {
    NbmsPill(text = status.label, colors = membershipStatusColors(status), modifier = modifier)
}

/** The Members page (`gym/members`): register, renew, edit, suspend / reactivate and remove gym members. */
@Composable
fun GymMembersScreen(session: SessionState.SignedIn) {
    val vm: GymMembersViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val packages by vm.packages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val symbol = session.business.currencySymbol
    val timezone = session.business.timezone

    var showRegister by rememberSaveable { mutableStateOf(false) }
    var renewId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var suspendId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }

    val ready = view as? MembersView.Ready
    fun memberOf(id: String?): GymMember? = id?.let { wanted -> ready?.all?.firstOrNull { it.id == wanted } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= MEMBERS_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GymPageHeader(
                icon = NbmsIcons.Dumbbell,
                title = "Gym Members",
                subtitle = ready?.let { "${it.total} total members" }
            ) {
                NbmsButton(text = "Register Member", onClick = { showRegister = true }, leadingIcon = NbmsIcons.Plus)
            }

            MembersFilterBar(
                wide = wide,
                search = filters.search,
                onSearch = vm::setSearch,
                status = filters.status,
                onStatus = vm::setStatus
            )

            when (val v = view) {
                is MembersView.Loading -> LoadingState()
                is MembersView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is MembersView.Ready -> {
                    if (v.rows.isEmpty()) {
                        EmptyState(icon = NbmsIcons.Dumbbell, title = "No members match your search.", message = "")
                    } else {
                        val actions = MemberActions(
                            busyOf = { id -> busy.any { it.endsWith(":$id") } },
                            onRenew = { renewId = it.id },
                            onEdit = { editId = it.id },
                            onSuspend = { suspendId = it.id },
                            onReactivate = vm::reactivateMember,
                            onRemove = { removeId = it.id }
                        )
                        if (wide) {
                            MembersTable(v.rows, timezone, actions)
                        } else {
                            PagedList(items = v.rows, key = { it.member.id }) { row -> MemberCard(row, timezone, actions) }
                        }
                    }
                }
            }
        }
    }

    if (showRegister) {
        RegisterMemberSheet(
            packages = packages,
            currencySymbol = symbol,
            saving = "register" in busy,
            onDismiss = { showRegister = false },
            onSubmit = { form -> vm.register(form) { showRegister = false } }
        )
    }

    memberOf(renewId)?.let { member ->
        RenewSheet(
            member = member,
            packages = packages,
            currencySymbol = symbol,
            saving = "renew:${member.id}" in busy,
            onDismiss = { renewId = null },
            onSubmit = { pkg -> vm.renew(member, pkg) { renewId = null } }
        )
    }

    memberOf(editId)?.let { member ->
        EditMemberSheet(
            member = member,
            saving = "edit:${member.id}" in busy,
            onDismiss = { editId = null },
            onSubmit = { form -> vm.edit(member, form) { editId = null } }
        )
    }

    memberOf(suspendId)?.let { member ->
        ConfirmDialog(
            title = "Suspend ${member.name}'s membership?",
            message = "They won't be able to check in until reactivated.",
            confirmText = "Suspend",
            destructive = true,
            onConfirm = {
                suspendId = null
                vm.suspendMember(member)
            },
            onDismiss = { suspendId = null }
        )
    }

    memberOf(removeId)?.let { member ->
        ConfirmDialog(
            title = "Remove ${member.name}?",
            message = "This removes them from the active members list. Their attendance history is kept for reporting.",
            confirmText = "Remove",
            destructive = true,
            onConfirm = {
                removeId = null
                vm.removeMember(member)
            },
            onDismiss = { removeId = null }
        )
    }
}

/** What the row buttons do. [busyOf] says whether any action on that member is waiting for the database. */
private class MemberActions(
    val busyOf: (String) -> Boolean,
    val onRenew: (GymMember) -> Unit,
    val onEdit: (GymMember) -> Unit,
    val onSuspend: (GymMember) -> Unit,
    val onReactivate: (GymMember) -> Unit,
    val onRemove: (GymMember) -> Unit
)

// ── filters ──

@Composable
private fun MembersFilterBar(
    wide: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    status: MembershipStatus?,
    onStatus: (MembershipStatus?) -> Unit
) {
    val dropdown: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "",
            options = STATUS_OPTIONS,
            selected = STATUS_OPTIONS.firstOrNull { it.status == status },
            onSelect = { onStatus(it.status) },
            optionLabel = { it.label },
            modifier = m,
            placeholder = "All Statuses"
        )
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search by name, phone, or room…", modifier = Modifier.weight(1f))
            dropdown(Modifier.width(200.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search by name, phone, or room…")
            dropdown(Modifier.fillMaxWidth())
        }
    }
}

// ── pieces shared by the card and the table row ──

@Composable
private fun MemberNameAndContact(member: GymMember, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            member.name.trim().ifEmpty { "—" },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            memberSubline(member),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Expiry date, and for an active membership with 7 days or fewer left an orange "{n}d left" / "expires today". */
@Composable
private fun ExpiryText(row: MemberRow, timezone: String) {
    val note = expiryNote(row)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            GymLogic.dateLabel(row.member.endDate.toGymInstant(), timezone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (MaterialTheme.nbms.isDark) Orange400 else Orange600
            )
        }
    }
}

/** Small ghost action: icon + 12sp label in [tint], 28dp high. A spinner replaces the icon while [busy]. */
@Composable
private fun ActionChip(text: String, icon: ImageVector, tint: Color, busy: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .alpha(if (busy) 0.5f else 1f)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(member: GymMember, actions: MemberActions) {
    val busy = actions.busyOf(member.id)
    val neutral = MaterialTheme.colorScheme.onBackground
    val dark = MaterialTheme.nbms.isDark
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionChip("Renew", NbmsIcons.Refresh, neutral, busy) { actions.onRenew(member) }
        ActionChip("Edit", NbmsIcons.Pencil, neutral, busy) { actions.onEdit(member) }
        if (member.status == MembershipStatus.SUSPENDED.key) {
            ActionChip("Reactivate", NbmsIcons.CheckCircle, if (dark) Green400 else Green600, busy) { actions.onReactivate(member) }
        } else {
            ActionChip("Suspend", Icons.Outlined.Block, if (dark) Orange400 else Orange600, busy) { actions.onSuspend(member) }
        }
        ActionChip("Remove", NbmsIcons.Trash, MaterialTheme.colorScheme.error, busy) { actions.onRemove(member) }
    }
}

// ── phone card ──

@Composable
private fun LabeledValue(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

@Composable
private fun MemberCard(row: MemberRow, timezone: String, actions: MemberActions) {
    val member = row.member
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                MemberNameAndContact(member, Modifier.weight(1f))
                MembershipStatusPill(row.status)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Package", Modifier.weight(1f)) {
                    Text(
                        member.packageName.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LabeledValue("Expires", Modifier.weight(1f)) { ExpiryText(row, timezone) }
                LabeledValue("Visits", Modifier.weight(0.6f)) {
                    Text(member.visitCount.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            ActionRow(member, actions)
        }
    }
}

// ── tablet table ──

// Columns: Member, Package, Status, Expires, Visits, Actions.
private val MEMBER_COLUMN_WEIGHTS = listOf(2.0f, 1.5f, 1.0f, 1.3f, 0.7f, 3.0f)
private val MEMBER_COLUMN_TITLES = listOf("Member", "Package", "Status", "Expires", "Visits", "Actions")

@Composable
private fun MembersTable(rows: List<MemberRow>, timezone: String, actions: MemberActions) {
    PagedRows(rows) { visible ->
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MEMBER_COLUMN_WEIGHTS.forEachIndexed { i, w ->
                        Text(
                            MEMBER_COLUMN_TITLES[i],
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(w).padding(horizontal = 4.dp),
                            maxLines = 1
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                visible.forEachIndexed { index, row ->
                    MemberTableRow(row, timezone, actions)
                    if (index < visible.lastIndex) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                }
            }
        }
    }
}

@Composable
private fun MemberTableRow(row: MemberRow, timezone: String, actions: MemberActions) {
    val member = row.member
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[0]).padding(horizontal = 4.dp)) { MemberNameAndContact(member) }
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[1]).padding(horizontal = 4.dp)) {
            Text(
                member.packageName.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[2]).padding(horizontal = 4.dp)) { MembershipStatusPill(row.status) }
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[3]).padding(horizontal = 4.dp)) { ExpiryText(row, timezone) }
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[4]).padding(horizontal = 4.dp)) {
            Text(member.visitCount.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.weight(MEMBER_COLUMN_WEIGHTS[5]).padding(horizontal = 4.dp)) { ActionRow(member, actions) }
    }
}
