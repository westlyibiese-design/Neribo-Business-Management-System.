package com.westly.nbms.features.auth

import com.westly.nbms.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.AuthNavigator

/** Staff Sign In (route `auth/login`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(nav: AuthNavigator, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // This is the start screen: the back button does nothing here.
    BackHandler(enabled = true) { }

    AuthBackground(backgroundRes = R.drawable.auth_bg_login, compact = true) {
        AuthBrandHeader(compact = true)

        AuthCard {
            AuthCardHeader("Staff Sign In", "Enter your credentials to access the portal", compact = true)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NbmsTextField(
                    value = state.email,
                    onValueChange = vm::onEmailChange,
                    label = "Email Address",
                    placeholder = "name@yourbusiness.com",
                    keyboardType = KeyboardType.Email,
                    leadingIcon = NbmsIcons.Mail,
                    error = state.emailError,
                    enabled = !state.busy
                )
                NbmsTextField(
                    value = state.password,
                    onValueChange = vm::onPasswordChange,
                    label = "Password",
                    isPassword = true,
                    leadingIcon = NbmsIcons.Lock,
                    error = state.passwordError,
                    enabled = !state.busy
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NbmsButton(
                    text = if (state.busy) "Signing in…" else "Sign In",
                    onClick = vm::submit,
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Default,
                    loading = state.busy,
                    enabled = !state.busy
                )
                if (state.pinLoginAvailable) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Shared device?",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !state.busy, role = Role.Button) { nav.go("auth/pin") }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                NbmsIcons.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                "Use PIN Login",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuthLink("Forgot your password?", onClick = { nav.go("auth/forgot") }, enabled = !state.busy)
            AuthOrDivider()
            AuthOutlineButton("Register your business", onClick = { nav.go("auth/register") }, enabled = !state.busy)
        }
    }
}
