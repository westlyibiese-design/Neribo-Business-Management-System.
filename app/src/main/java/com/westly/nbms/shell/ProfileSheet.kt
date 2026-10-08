package com.westly.nbms.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.Avatar
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.NbmsSegmentedTabs
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

/** Bottom sheet opened from the drawer's user card: who is signed in, the business code (owner only) and appearance. */
@Composable
fun ProfileSheet(
    session: SessionState.SignedIn,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenDeviceSettings: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val user = session.user
    val scheme = MaterialTheme.colorScheme
    NbmsBottomSheet(onDismiss = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Avatar(name = user.name, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    user.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground
                )
                if (!user.email.isNullOrBlank()) {
                    Text(user.email, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                if (!user.phone.isNullOrBlank()) {
                    Text(Format.phone(user.phone), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NbmsPill(user.role.label, MaterialTheme.nbms.rolePill(user.role.key))
            Text(
                session.business.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onBackground
            )
        }

        if (user.role == Role.SUPER_ADMIN) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Business code", style = MaterialTheme.typography.labelLarge, color = scheme.onBackground)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceVariant)
                        .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        session.business.code,
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color = scheme.onSurface
                    )
                    NbmsButton(
                        text = "Copy",
                        onClick = { onCopyCode(session.business.code) },
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        leadingIcon = Icons.Outlined.ContentCopy
                    )
                }
                Text(
                    "Staff on shared devices use this code with their PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = scheme.onBackground)
            NbmsSegmentedTabs(
                tabs = listOf("Light", "Dark", "System"),
                selected = themeMode.ordinal,
                onSelect = { onThemeChange(ThemeMode.entries[it]) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Device Settings",
                onClick = onOpenDeviceSettings,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                leadingIcon = NbmsIcons.Tune
            )
            NbmsButton(
                text = if (user.usesPin) "End Session" else "Sign Out",
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                leadingIcon = NbmsIcons.LogOut
            )
        }
    }
}
