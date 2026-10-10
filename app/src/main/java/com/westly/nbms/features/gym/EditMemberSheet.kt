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
import com.westly.nbms.core.design.NbmsTextField

/** "Edit Member": name, phone, email, room number and notes. The package and dates are not edited here. */
@Composable
internal fun EditMemberSheet(
    member: GymMember,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (EditMemberForm) -> Unit
) {
    var form by remember(member.id) { mutableStateOf(editFormOf(member)) }
    var tried by remember { mutableStateOf(false) }
    val nameError = if (tried) validateEditForm(form) else null

    NbmsBottomSheet(onDismiss = { if (!saving) onDismiss() }, title = "Edit Member") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                label = "Name *",
                modifier = Modifier.fillMaxWidth(),
                error = nameError
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
                label = "Room Number",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Optional"
            )
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
                    text = "Save Changes",
                    onClick = {
                        tried = true
                        if (validateEditForm(form) == null) onSubmit(form)
                    },
                    modifier = Modifier.weight(1f),
                    loading = saving
                )
            }
        }
    }
}
