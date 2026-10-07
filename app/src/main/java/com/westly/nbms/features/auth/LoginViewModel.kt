package com.westly.nbms.features.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.AuthFeature
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.util.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val busy: Boolean = false,
    /** True only when a screen with route "auth/pin" has been registered (Phase 5). */
    val pinLoginAvailable: Boolean = false
)

internal const val LOGIN_FAILED_TITLE = "Login failed"
internal const val LOGIN_FAILED_MESSAGE = "Invalid email or password. Please try again."

/** Decides which text to show under the "Login failed" title. */
internal fun loginFailureMessage(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("Invalid email or password")) return LOGIN_FAILED_MESSAGE
    return text
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val toast: ToastController,
    authFeatures: Set<@JvmSuppressWildcards AuthFeature>,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            email = savedState.get<String>(KEY_EMAIL).orEmpty(),
            pinLoginAvailable = authFeatures.any { f -> f.screens.any { it.route == "auth/pin" } }
        )
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) {
        savedState[KEY_EMAIL] = value
        _state.update { it.copy(email = value, emailError = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.busy) return
        val email = current.email.trim()
        val emailError = when {
            email.isEmpty() -> "Enter your email address."
            !Validators.email(email) -> "Enter a valid email address."
            else -> null
        }
        val passwordError = if (current.password.isEmpty()) "Enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(busy = true, emailError = null, passwordError = null) }
        viewModelScope.launch {
            val result = sessionManager.signInWithPassword(email, current.password)
            _state.update { it.copy(busy = false) }
            result.onFailure { e ->
                toast.show(loginFailureMessage(e.message), ToastType.Error, LOGIN_FAILED_TITLE)
            }
            // On success nothing else to do: SessionState flips and the root shows the shell.
        }
    }

    private companion object {
        const val KEY_EMAIL = "login_email"
    }
}
