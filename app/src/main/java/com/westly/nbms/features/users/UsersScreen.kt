package com.westly.nbms.features.users

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.Avatar
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.rbac.isPinEligible
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import com.westly.nbms.features.users.models.StaffUser
import com.westly.nbms.features.users.models.isActive
import com.westly.nbms.features.users.models.roleLabel
import com.westly.nbms.features.users.models.roleOrNull

private val TABLET_WIDTH = 600.dp

/** Users & Roles: every staff account of this business, with create / reset / suspend actions. */
@Composable
fun UsersScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: UsersViewModel = hiltViewModel()
) {
    val users by vm.users.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<ResetTarget?>(null) }

    val loaded = users
    val subtitle = if (loaded is Resource.Success) "${loaded.data.size} staff accounts" else null
    val enabledRoles = remember(session.business.enabledRoles) {
        Role.entries.filter { it != Role.SUPER_ADMIN && it in session.business.enabledRoles }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                PageHeader(title = "Users", subtitle = subtitle) {
                    NbmsButton(
                        text = "Roles & Permissions",
                        onClick = vm::openRoles,
                        variant = ButtonVariant.Outline,
                        leadingIcon = NbmsIcons.Shield
                    )
                    NbmsButton(text = "Add User", onClick = { showCreate = true }, leadingIcon = NbmsIcons.Plus)
                }
            } else {
                PageHeader(title = "Users", subtitle = subtitle)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton(
                        text = "Roles & Permissions",
                        onClick = vm::openRoles,
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                        leadingIcon = NbmsIcons.Shield
                    )
                    NbmsButton(
                        text = "Add User",
                        onClick = { showCreate = true },
                        modifier = Modifier.weight(1f),
                        leadingIcon = NbmsIcons.Plus
                    )
                }
            }

            when (val r = users) {
                is Resource.Loading -> LoadingState()
                is Resource.Error -> ErrorState("We couldn't load staff accounts.", onRetry = vm::retry)
                is Resource.Success -> {
                    if (r.data.isEmpty()) {
                        EmptyState(
                            icon = NbmsIcons.Users,
                            title = "No staff accounts yet",
                            message = "Tap Add User to create the first one."
                        )
                    } else if (wide) {
                        UsersTable(
                            users = r.data,
                            ownerId = session.user.uid,
                            busyIds = state.busyIds,
                            onResetPassword = { resetTarget = ResetTarget(it, ResetKind.PASSWORD) },
                            onResetPin = { resetTarget = ResetTarget(it, ResetKind.PIN) },
                            onToggleStatus = vm::toggleStatus
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            r.data.forEach { user ->
                                UserCard(
                                    user = user,
                                    isOwnRow = user.id == session.user.uid,
                                    busy = user.id in state.busyIds,
                                    onResetPassword = { resetTarget = ResetTarget(user, ResetKind.PASSWORD) },
                                    onResetPin = { resetTarget = ResetTarget(user, ResetKind.PIN) },
                                    onToggleStatus = { vm.toggleStatus(user) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateUserDialog(
            roles = enabledRoles,
            saving = state.creating,
            onDismiss = { showCreate = false },
            onCreate = { name, email, phone, password, role, pin ->
                vm.createUser(name, email, phone, password, role, pin) { showCreate = false }
            }
        )
    }

    resetTarget?.let { target ->
        ResetDialog(
            target = target,
            saving = state.resetting,
            onDismiss = { resetTarget = null },
            onReset = { value -> vm.reset(target, value) { resetTarget = null } }
        )
    }
}

// ---- Phone: one card per person ---------------------------------------------------------------

@Composable
private fun UserCard(
    user: StaffUser,
    isOwnRow: Boolean,
    busy: Boolean,
    onResetPassword: () -> Unit,
    onResetPin: () -> Unit,
    onToggleStatus: () -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(name = user.name, size = 40.dp)
                NameAndEmail(user, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RolePill(user)
                StatusBadge(user)
                PinBadge(user)
            }
            Text(
                "Last login: ${lastLoginText(user)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isOwnRow) {
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                RowActions(user, busy, onResetPassword, onResetPin, onToggleStatus)
            }
        }
    }
}

// ---- Tablet: a table ---------------------------------------------------------------------------

@Composable
private fun UsersTable(
    users: List<StaffUser>,
    ownerId: String,
    busyIds: Set<String>,
    onResetPassword: (StaffUser) -> Unit,
    onResetPin: (StaffUser) -> Unit,
    onToggleStatus: (StaffUser) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("Name", Modifier.weight(2.4f))
                HeaderCell("Role", Modifier.weight(1.4f))
                HeaderCell("Status", Modifier.weight(1f))
                HeaderCell("PIN Login", Modifier.weight(1f))
                HeaderCell("Last Login", Modifier.weight(1.2f))
                HeaderCell("Actions", Modifier.weight(1.8f))
            }
            users.forEach { user ->
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(2.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(name = user.name, size = 36.dp)
                        NameAndEmail(user, Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1.4f)) { RolePill(user) }
                    Row(Modifier.weight(1f)) { StatusBadge(user) }
                    Row(Modifier.weight(1f)) { PinBadge(user) }
                    Text(
                        lastLoginText(user),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.2f)
                    )
                    Row(Modifier.weight(1.8f)) {
                        if (user.id != ownerId) {
                            RowActions(
                                user = user,
                                busy = user.id in busyIds,
                                onResetPassword = { onResetPassword(user) },
                                onResetPin = { onResetPin(user) },
                                onToggleStatus = { onToggleStatus(user) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---- Shared pieces -----------------------------------------------------------------------------

@Composable
private fun NameAndEmail(user: StaffUser, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            user.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!user.email.isNullOrBlank()) {
            Text(
                user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RolePill(user: StaffUser) {
    NbmsPill(user.roleLabel(), MaterialTheme.nbms.rolePill(user.role))
}

@Composable
private fun StatusBadge(user: StaffUser) {
    NbmsBadge(
        text = user.status,
        tone = if (user.isActive()) BadgeTone.Default else BadgeTone.Destructive
    )
}

@Composable
private fun PinBadge(user: StaffUser) {
    if (user.usesPin) {
        NbmsBadge(text = "Set", tone = BadgeTone.Outline, leadingIcon = NbmsIcons.Key)
    } else {
        Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun lastLoginText(user: StaffUser): String =
    user.lastLogin?.let { Format.date(it.toInstant()) } ?: "Never"

/** Reset password, Reset PIN (PIN roles only) and Suspend / Restore. Disabled with a spinner while the call runs. */
@Composable
private fun RowActions(
    user: StaffUser,
    busy: Boolean,
    onResetPassword: () -> Unit,
    onResetPin: () -> Unit,
    onToggleStatus: () -> Unit
) {
    val role = user.roleOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        NbmsButton(
            text = "Reset password",
            onClick = onResetPassword,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Icon,
            enabled = !busy,
            leadingIcon = NbmsIcons.Key
        )
        if (role != null && role.isPinEligible()) {
            NbmsButton(
                text = "Reset PIN",
                onClick = onResetPin,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Icon,
                enabled = !busy,
                leadingIcon = NbmsIcons.Lock
            )
        }
        if (user.isActive()) {
            NbmsButton(
                text = "Suspend",
                onClick = onToggleStatus,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Icon,
                loading = busy,
                enabled = !busy,
                leadingIcon = NbmsIcons.UserX
            )
        } else {
            NbmsButton(
                text = "Restore",
                onClick = onToggleStatus,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Icon,
                loading = busy,
                enabled = !busy,
                leadingIcon = NbmsIcons.UserCheck
            )
        }
    }
}
