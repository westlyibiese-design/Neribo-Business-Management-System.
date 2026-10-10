package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.AdaptiveTwoColumn
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField

/** One entry of the package drop-down. [id] null is the "Custom…" entry. */
internal data class PackageChoice(val id: String?, val label: String)

/** Every listed package, then "Custom…". */
internal fun packageChoices(packages: List<GymPackage>, currencySymbol: String): List<PackageChoice> =
    packages.map { PackageChoice(it.id, packageOptionLabel(it, currencySymbol)) } + PackageChoice(null, "Custom…")

/** "Register New Gym Member". The sheet keeps what was typed until it closes; the saving, toasts and closing are done by the caller. */
@Composable
internal fun RegisterMemberSheet(
    packages: List<GymPackage>,
    currencySymbol: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RegisterMemberForm) -> Unit
) {
    var form by remember { mutableStateOf(RegisterMemberForm()) }
    var tried by remember { mutableStateOf(false) }
    val errors = if (tried) validateRegisterForm(form) else RegisterErrors()
    val choices = remember(packages, currencySymbol) { packageChoices(packages, currencySymbol) }
    val custom = form.packageId == null || choices.none { it.id == form.packageId }

    NbmsBottomSheet(onDismiss = { if (!saving) onDismiss() }, title = "Register New Gym Member") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                label = "Full Name *",
                modifier = Modifier.fillMaxWidth(),
                error = errors.name
            )
            AdaptiveTwoColumn {
                NbmsTextField(
                    value = form.phone,
                    onValueChange = { form = form.copy(phone = it) },
                    label = "Phone",
                    keyboardType = KeyboardType.Phone
                )
                NbmsTextField(
                    value = form.email,
                    onValueChange = { form = form.copy(email = it) },
                    label = "Email",
                    keyboardType = KeyboardType.Email
                )
            }
            NbmsTextField(
                value = form.roomNumber,
                onValueChange = { form = form.copy(roomNumber = it) },
                label = "Room Number (if a current hotel guest)",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Optional"
            )
            NbmsDropdown(
                label = "Membership Package",
                options = choices,
                selected = choices.firstOrNull { it.id == form.packageId } ?: choices.last(),
                onSelect = { form = form.copy(packageId = it.id) },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Custom…"
            )
            if (custom) {
                NbmsTextField(
                    value = form.customName,
                    onValueChange = { form = form.copy(customName = it) },
                    label = "Custom Package Name",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "e.g. Weekly Pass"
                )
                AdaptiveTwoColumn {
                    NbmsTextField(
                        value = form.durationText,
                        onValueChange = { form = form.copy(durationText = gymFilterWholeInput(it)) },
                        label = "Duration (days)",
                        keyboardType = KeyboardType.Number,
                        error = errors.duration
                    )
                    NbmsTextField(
                        value = form.priceText,
                        onValueChange = { form = form.copy(priceText = gymFilterMoneyInput(it)) },
                        label = "Price ($currencySymbol)",
                        keyboardType = KeyboardType.Decimal,
                        error = errors.price
                    )
                }
            }
            NbmsTextField(
                value = form.notes,
                onValueChange = { form = form.copy(notes = it) },
                label = "Notes",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NbmsButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Outline,
                    enabled = !saving
                )
                NbmsButton(
                    text = "Register Member",
                    onClick = {
                        tried = true
                        // A package that disappeared from the list since it was picked counts as custom.
                        val toSend = if (custom) form.copy(packageId = null) else form
                        if (!validateRegisterForm(toSend).any) onSubmit(toSend)
                    },
                    modifier = Modifier.weight(1f),
                    loading = saving
                )
            }
        }
    }
}
