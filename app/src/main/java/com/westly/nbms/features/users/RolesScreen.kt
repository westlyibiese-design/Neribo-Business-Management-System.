package com.westly.nbms.features.users

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState

/** Roles & Permissions: which roles the business uses, plus a read-only list of what each role may do. */
@Composable
fun RolesScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: RolesViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val counts by vm.activeCounts.collectAsStateWithLifecycle()
    val enabled = session.business.enabledRoles

    // Once the live business data shows the new switch position, stop overriding it.
    LaunchedEffect(enabled) { vm.syncWith(enabled) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(NbmsIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            PageHeader(
                title = "Roles & Permissions",
                subtitle = "Read-only reference of role permissions.",
                modifier = Modifier.weight(1f)
            )
        }

        // ---- Section 1: roles used by this business ----
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Roles used by your business",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Rbac.assignableRoles.forEachIndexed { index, role ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                    val isOn = state.pending[role] ?: (role in enabled)
                    val active = counts[role] ?: 0
                    val allowed = canToggleRole(isEnabled = isOn, activeCount = active)
                    RoleSwitchRow(
                        role = role,
                        checked = isOn,
                        activeCount = active,
                        blocked = !allowed,
                        switchEnabled = allowed && state.busyRole == null,
                        onChange = { vm.setRole(role, it, enabled) }
                    )
                }
                Text(
                    "Restaurant, Bar, Laundry, Gym, Housekeeping and Sales appear in the menu only when a role that uses them is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // ---- Section 2: every role and its permissions ----
        Role.entries.forEach { role -> RolePermissionsCard(role) }
    }
}

@Composable
private fun RoleSwitchRow(
    role: Role,
    checked: Boolean,
    activeCount: Int,
    blocked: Boolean,
    switchEnabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(role.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(roleDescription(role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (activeCount == 1) "1 active member" else "$activeCount active members",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (blocked) {
                Text(roleBlockedCaption(activeCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nbms.onWarningContainer)
            }
        }
        NbmsSwitch(checked = checked, onCheckedChange = onChange, enabled = switchEnabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RolePermissionsCard(role: Role) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(role.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                NbmsPill(role.key, MaterialTheme.nbms.rolePill(role.key))
            }
            if (role == Role.SUPER_ADMIN) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(NbmsIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("Full access to all features", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Rbac.permissionsOf(role).forEach { permission ->
                        Text(
                            permission,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
