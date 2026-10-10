package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown

internal const val MSG_NO_PACKAGES = "No packages configured yet — add one under Gym Management → Membership Packages."

/** "Renew — {name}". With no packages the sheet says so and Renew stays disabled. */
@Composable
internal fun RenewSheet(
    member: GymMember,
    packages: List<GymPackage>,
    currencySymbol: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (GymPackage?) -> Unit
) {
    var pickedId by remember { mutableStateOf<String?>(null) }
    // Starts on the member's current package if it is still listed, else the first one.
    val selected = packages.firstOrNull { it.id == pickedId } ?: defaultRenewPackage(member, packages)

    NbmsBottomSheet(onDismiss = { if (!saving) onDismiss() }, title = "Renew — ${member.name}") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (packages.isEmpty()) {
                Text(
                    MSG_NO_PACKAGES,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                NbmsDropdown(
                    label = "Package",
                    options = packages,
                    selected = selected,
                    onSelect = { pickedId = it.id },
                    optionLabel = { packageOptionLabel(it, currencySymbol) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Select a package"
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NbmsButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Outline,
                    enabled = !saving
                )
                NbmsButton(
                    text = "Renew",
                    onClick = { onSubmit(selected) },
                    modifier = Modifier.weight(1f),
                    loading = saving,
                    enabled = packages.isNotEmpty()
                )
            }
        }
    }
}
