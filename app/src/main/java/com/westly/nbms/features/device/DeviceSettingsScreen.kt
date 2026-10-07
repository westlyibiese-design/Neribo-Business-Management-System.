package com.westly.nbms.features.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

/** Device Settings: appearance, this phone's PIN, and the phones registered for this account. */
@Composable
fun DeviceSettingsScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: DeviceSettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val theme by vm.themeMode.collectAsState()
    val thisDeviceId = vm.thisDeviceId

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(title = "Device Settings", subtitle = "Appearance and security for this phone.")

        SettingsCard("Appearance", "Choose how the app looks on this phone.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeTile("Light", Icons.Outlined.LightMode, theme == ThemeMode.Light, Modifier.weight(1f)) {
                    vm.selectTheme(ThemeMode.Light)
                }
                ThemeTile("Dark", Icons.Outlined.DarkMode, theme == ThemeMode.Dark, Modifier.weight(1f)) {
                    vm.selectTheme(ThemeMode.Dark)
                }
                ThemeTile("System", Icons.Outlined.PhoneAndroid, theme == ThemeMode.System, Modifier.weight(1f)) {
                    vm.selectTheme(ThemeMode.System)
                }
            }
        }

        if (session.user.usesPin) {
            Text(
                "Device PIN isn't available when you sign in with a staff PIN on a shared device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SettingsCard(
                "Device PIN",
                "Lock the app after 5 minutes away and unlock it with a PIN instead of your password."
            ) {
                NbmsTextField(
                    value = state.newPin,
                    onValueChange = vm::onNewPin,
                    label = "",
                    placeholder = "New PIN (6–10 digits)",
                    error = state.newPinError,
                    keyboardType = KeyboardType.NumberPassword,
                    isPassword = true,
                    enabled = !state.saving
                )
                NbmsTextField(
                    value = state.confirmPin,
                    onValueChange = vm::onConfirmPin,
                    label = "",
                    placeholder = "Confirm PIN",
                    error = state.confirmError,
                    keyboardType = KeyboardType.NumberPassword,
                    isPassword = true,
                    enabled = !state.saving
                )
                NbmsButton(
                    text = "Save PIN",
                    onClick = vm::savePin,
                    loading = state.saving,
                    enabled = state.newPin.isNotEmpty() && state.confirmPin.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SettingsCard("Registered devices", "Phones that can use a Device PIN with your account.") {
            when (val d = state.devices) {
                DevicesState.Loading -> LoadingState()
                is DevicesState.Failed -> ErrorState(message = d.message, onRetry = vm::load)
                is DevicesState.Loaded -> {
                    if (d.devices.isEmpty()) {
                        Text(
                            "No devices registered yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        d.devices.forEach { device ->
                            DeviceRow(device, isThis = device.deviceId == thisDeviceId) { vm.askRemove(device) }
                        }
                    }
                }
            }
        }

        vm.sections.forEach { it.Content(session) }
    }

    state.removing?.let { target ->
        ConfirmDialog(
            title = "Remove this device?",
            message = "${target.deviceLabel} will stop locking and unlocking with a PIN. " +
                "You can set a PIN again on that phone at any time.",
            confirmText = if (state.removeBusy) "Removing…" else "Remove",
            destructive = true,
            onConfirm = vm::confirmRemove,
            onDismiss = vm::cancelRemove
        )
    }
}

@Composable
private fun SettingsCard(title: String, description: String, content: @Composable () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun ThemeTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else MaterialTheme.nbms.inputBorder),
                shape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, isThis: Boolean, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    device.deviceLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isThis) NbmsBadge(text = "This device", tone = BadgeTone.Outline)
            }
            Text(
                "Added ${Format.date(device.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                "Last used ${Format.relative(device.lastUsedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            if (!device.hasPin) {
                Text("Not configured", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        NbmsButton(
            text = "Remove device",
            onClick = onRemove,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Icon,
            leadingIcon = NbmsIcons.Trash
        )
    }
}
