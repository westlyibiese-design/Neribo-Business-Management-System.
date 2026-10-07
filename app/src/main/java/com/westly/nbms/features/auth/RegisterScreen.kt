package com.westly.nbms.features.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCheckbox
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.AuthNavigator

private val STEP_TITLES = listOf("Owner", "Hotel", "Roles")

/** Register your business (route `auth/register`): a 3-step wizard and a success screen. */
@Composable
fun RegisterScreen(nav: AuthNavigator, vm: RegisterViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val created = state.createdBusinessCode != null

    BackHandler(enabled = created || state.submitting || state.step > 1) { vm.back() }

    AuthBackground {
        AuthBrandHeader()
        AuthCard {
            if (created) {
                SuccessContent(
                    code = state.createdBusinessCode.orEmpty(),
                    signingIn = state.signingIn,
                    onCopied = vm::copied,
                    onContinue = { vm.continueToApp(onFailure = { nav.back() }) }
                )
            } else {
                AuthCardHeader(
                    title = "Register your business",
                    description = "Step ${state.step} of 3 · ${STEP_TITLES[state.step - 1]}"
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    state.serverError?.let { AuthErrorBanner(it) }
                    when (state.step) {
                        1 -> OwnerStep(state, vm)
                        2 -> HotelStep(state, vm)
                        else -> RolesStep(state, vm)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NbmsButton(
                        text = "Back",
                        onClick = { if (!vm.back()) nav.back() },
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                        enabled = !state.submitting
                    )
                    if (state.step < 3) {
                        NbmsButton(text = "Next", onClick = vm::next, modifier = Modifier.weight(1f))
                    } else {
                        NbmsButton(
                            text = "Create my business",
                            onClick = vm::submit,
                            modifier = Modifier.weight(1f),
                            loading = state.submitting,
                            enabled = !state.submitting
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerStep(state: RegisterUiState, vm: RegisterViewModel) {
    NbmsTextField(
        value = state.fullName, onValueChange = vm::onFullName, label = "Full name *",
        placeholder = "Jane Doe", error = state.fullNameError
    )
    NbmsTextField(
        value = state.email, onValueChange = vm::onEmail, label = "Email *",
        placeholder = "name@yourbusiness.com", keyboardType = KeyboardType.Email, error = state.emailError
    )
    NbmsTextField(
        value = state.phone, onValueChange = vm::onPhone, label = "Phone",
        placeholder = "Optional", keyboardType = KeyboardType.Phone, error = state.phoneError
    )
    NbmsTextField(
        value = state.password, onValueChange = vm::onPassword, label = "Password *",
        placeholder = "At least 8 characters", isPassword = true, error = state.passwordError
    )
    NbmsTextField(
        value = state.confirmPassword, onValueChange = vm::onConfirmPassword, label = "Confirm password *",
        isPassword = true, error = state.confirmError
    )
}

@Composable
private fun HotelStep(state: RegisterUiState, vm: RegisterViewModel) {
    NbmsTextField(
        value = state.businessName, onValueChange = vm::onBusinessName, label = "Business name *",
        placeholder = "Your hotel's name", error = state.businessNameError
    )
    NbmsDropdown(
        label = "Business type",
        options = listOf("Hotel"),
        selected = "Hotel",
        onSelect = { },
        optionLabel = { it },
        enabled = false
    )
    NbmsTextField(
        value = "₦ Nigerian Naira", onValueChange = { }, label = "Currency", enabled = false
    )
    NbmsTextField(
        value = "Africa/Lagos", onValueChange = { }, label = "Timezone", enabled = false
    )
}

@Composable
private fun RolesStep(state: RegisterUiState, vm: RegisterViewModel) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Which roles does your hotel use?",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface
        )
        Text(
            "You can change this later. You are the Super Admin and can add staff for these roles.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )
    }
    RegisterRoles.groups.forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                group.title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            group.options.forEach { option ->
                val checked = option.role in state.selectedRoles
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.onToggleRole(option.role, !checked) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NbmsCheckbox(checked = checked, onCheckedChange = { vm.onToggleRole(option.role, it) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.role.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurface
                        )
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    Text(
        "Restaurant, Bar, Laundry, Gym, Housekeeping and Sales sections appear only if you tick a role that uses them.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(scheme.surfaceVariant)
            .padding(12.dp)
    )
}

@Composable
private fun SuccessContent(
    code: String,
    signingIn: Boolean,
    onCopied: () -> Unit,
    onContinue: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.nbms.successContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                NbmsIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.nbms.onSuccessContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            "Business created",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            "Your business code is",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )
        SelectionContainer {
            Text(
                code,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                ),
                color = scheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
        NbmsButton(
            text = "Copy",
            onClick = {
                clipboard.setText(AnnotatedString(code))
                onCopied()
            },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            leadingIcon = NbmsIcons.Check
        )
        Text(
            "Staff on shared devices will need this code to sign in with a PIN. You can find it later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        NbmsButton(
            text = "Continue",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            loading = signingIn,
            enabled = !signingIn
        )
    }
}
