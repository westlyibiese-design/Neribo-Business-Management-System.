package com.westly.nbms.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.AuthNavigator

/** Forgot password (route `auth/forgot`): ask for a code, then enter the code and a new password. */
@Composable
fun ForgotPasswordScreen(nav: AuthNavigator, vm: ForgotPasswordViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    AuthBackground {
        AuthBrandHeader()
        AuthCard {
            AuthCardHeader(
                title = "Reset your password",
                description = if (state.step == 1) "Enter your email and we will send you a 6-digit code."
                else "Enter the code from your email and choose a new password."
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                state.errorBanner?.let { AuthErrorBanner(it) }
                if (state.step == 1) {
                    NbmsTextField(
                        value = state.email,
                        onValueChange = vm::onEmail,
                        label = "Email Address",
                        placeholder = "name@yourbusiness.com",
                        keyboardType = KeyboardType.Email,
                        leadingIcon = NbmsIcons.Mail,
                        error = state.emailError,
                        enabled = !state.busy
                    )
                } else {
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    NbmsTextField(
                        value = state.code,
                        onValueChange = vm::onCode,
                        label = "Code",
                        placeholder = "6-digit code",
                        keyboardType = KeyboardType.NumberPassword,
                        error = state.codeError,
                        enabled = !state.busy
                    )
                    NbmsTextField(
                        value = state.newPassword,
                        onValueChange = vm::onNewPassword,
                        label = "New password",
                        isPassword = true,
                        leadingIcon = NbmsIcons.Lock,
                        error = state.passwordError,
                        enabled = !state.busy
                    )
                    NbmsTextField(
                        value = state.confirmPassword,
                        onValueChange = vm::onConfirmPassword,
                        label = "Confirm password",
                        isPassword = true,
                        leadingIcon = NbmsIcons.Lock,
                        error = state.confirmError,
                        enabled = !state.busy
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.step == 1) {
                    NbmsButton(
                        text = "Send reset code",
                        onClick = vm::sendCode,
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.busy,
                        enabled = !state.busy
                    )
                } else {
                    NbmsButton(
                        text = "Reset password",
                        onClick = { vm.resetPassword(onDone = { nav.back() }) },
                        modifier = Modifier.fillMaxWidth(),
                        loading = state.busy,
                        enabled = !state.busy
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canResend = state.resendSeconds == 0 && !state.busy
                        NbmsButton(
                            text = if (state.resendSeconds > 0) "Resend code in ${state.resendSeconds}s" else "Resend code",
                            onClick = vm::sendCode,
                            variant = ButtonVariant.Link,
                            size = ButtonSize.Sm,
                            enabled = canResend
                        )
                        NbmsButton(
                            text = "Change email",
                            onClick = vm::editEmail,
                            variant = ButtonVariant.Link,
                            size = ButtonSize.Sm,
                            enabled = !state.busy
                        )
                    }
                }
            }
        }
        AuthLink("Back to sign in", onClick = { nav.back() }, enabled = !state.busy)
    }
}
