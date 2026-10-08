package com.westly.nbms.features.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.rbac.isPinEligible

/** "Create New User". [roles] holds only the roles this business has turned on (never Super Admin). */
@Composable
fun CreateUserDialog(
    roles: List<Role>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, email: String, phone: String, password: String, role: Role, pin: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var roleKey by rememberSaveable { mutableStateOf(roles.firstOrNull()?.key) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val role = roles.firstOrNull { it.key == roleKey } ?: roles.firstOrNull()

    NbmsDialog(
        title = "Create New User",
        onDismiss = { if (!saving) onDismiss() },
        confirmText = "Create User",
        loading = saving,
        onConfirm = {
            val problem = validateNewUser(name, email, password, role, pin)
            if (problem != null || role == null) {
                error = problem
            } else {
                error = null
                onCreate(name, email, phone, password, role, pin)
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = "Full Name *",
                enabled = !saving
            )
            NbmsTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = "Email *",
                keyboardType = KeyboardType.Email,
                enabled = !saving
            )
            NbmsTextField(
                value = phone,
                onValueChange = { phone = it },
                label = "Phone",
                keyboardType = KeyboardType.Phone,
                enabled = !saving
            )
            NbmsTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "Password * (min 8 chars)",
                isPassword = true,
                enabled = !saving
            )
            NbmsDropdown(
                label = "Role *",
                options = roles,
                selected = role,
                onSelect = { roleKey = it.key; error = null },
                optionLabel = { it.label },
                enabled = !saving && roles.isNotEmpty()
            )
            if (role != null && role.isPinEligible()) {
                NbmsTextField(
                    value = pin,
                    onValueChange = { pin = cleanPinInput(it); error = null },
                    label = "PIN (4-6 digits, for shared devices)",
                    placeholder = "Optional",
                    keyboardType = KeyboardType.NumberPassword,
                    enabled = !saving
                )
            }
            if (roles.isEmpty()) {
                Text(MSG_NO_ROLE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** "Reset Password — {name}" or "Reset PIN — {name}". */
@Composable
fun ResetDialog(
    target: ResetTarget,
    saving: Boolean,
    onDismiss: () -> Unit,
    onReset: (value: String) -> Unit
) {
    val isPassword = target.kind == ResetKind.PASSWORD
    var value by rememberSaveable(target.user.id, target.kind) { mutableStateOf("") }
    var error by rememberSaveable(target.user.id, target.kind) { mutableStateOf<String?>(null) }

    NbmsDialog(
        title = if (isPassword) "Reset Password — ${target.user.name}" else "Reset PIN — ${target.user.name}",
        onDismiss = { if (!saving) onDismiss() },
        confirmText = "Reset",
        loading = saving,
        onConfirm = {
            val problem = if (isPassword) validateNewPassword(value) else validateNewPin(value)
            if (problem != null) {
                error = problem
            } else {
                error = null
                onReset(value)
            }
        }
    ) {
        NbmsTextField(
            value = value,
            onValueChange = { value = if (isPassword) it else cleanPinInput(it); error = null },
            label = if (isPassword) "New Password * (min 8 chars)" else "New PIN * (4-6 digits)",
            isPassword = isPassword,
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.NumberPassword,
            error = error,
            enabled = !saving,
            modifier = Modifier
        )
    }
}
