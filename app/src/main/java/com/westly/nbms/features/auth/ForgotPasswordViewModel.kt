package com.westly.nbms.features.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.util.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ForgotUiState(
    val step: Int = 1,                 // 1 = ask for a code, 2 = enter code + new password
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val emailError: String? = null,
    val codeError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    val message: String? = null,       // neutral note, e.g. "If an account exists..."
    val errorBanner: String? = null,
    val busy: Boolean = false,
    val resendSeconds: Int = 0
)

internal const val MSG_CODE_SENT = "If an account exists for that email, a 6-digit code has been sent."
internal const val MSG_BAD_CODE = "That code is invalid or has expired. Please check it or request a new one."
internal const val MSG_RESET_FAILED = "Could not update your password. Please try again."
internal const val MSG_RECOVERY_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val RESEND_COOLDOWN_SECONDS = 60

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val recovery: RecoveryClient,
    private val toast: ToastController,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(
        ForgotUiState(
            step = saved.get<Int>(K_STEP) ?: 1,
            email = saved.get<String>(K_EMAIL).orEmpty(),
            message = if ((saved.get<Int>(K_STEP) ?: 1) == 2) MSG_CODE_SENT else null
        )
    )
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    private var cooldownJob: Job? = null

    fun onEmail(v: String) {
        saved[K_EMAIL] = v
        _state.update { it.copy(email = v, emailError = null) }
    }

    fun onCode(v: String) {
        _state.update { it.copy(code = v.filter { c -> c.isDigit() }.take(6), codeError = null, errorBanner = null) }
    }

    fun onNewPassword(v: String) = _state.update { it.copy(newPassword = v, passwordError = null) }
    fun onConfirmPassword(v: String) = _state.update { it.copy(confirmPassword = v, confirmError = null) }

    /** Step 1 button, and the "Resend code" link on step 2. */
    fun sendCode() {
        val s = _state.value
        if (s.busy || (s.step == 2 && s.resendSeconds > 0)) return
        val email = s.email.trim()
        val err = when {
            email.isEmpty() -> "Enter your email address."
            !Validators.email(email) -> "Enter a valid email address."
            else -> null
        }
        if (err != null) {
            _state.update { it.copy(emailError = err) }
            return
        }
        _state.update { it.copy(busy = true, errorBanner = null) }
        viewModelScope.launch {
            try {
                recovery.sendResetCode(email)
                goToStep2()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestException) {
                // Never reveal whether the account exists: answer the same way.
                goToStep2()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorBanner = MSG_RECOVERY_NETWORK) }
            }
        }
    }

    private fun goToStep2() {
        saved[K_STEP] = 2
        _state.update { it.copy(busy = false, step = 2, message = MSG_CODE_SENT, errorBanner = null) }
        startCooldown()
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (left in RESEND_COOLDOWN_SECONDS downTo 1) {
                _state.update { it.copy(resendSeconds = left) }
                delay(1_000)
            }
            _state.update { it.copy(resendSeconds = 0) }
        }
    }

    /** Back from step 2 to step 1 (to correct the email). */
    fun editEmail() {
        saved[K_STEP] = 1
        cooldownJob?.cancel()
        _state.update {
            it.copy(
                step = 1, code = "", newPassword = "", confirmPassword = "", message = null,
                errorBanner = null, resendSeconds = 0, codeError = null, passwordError = null, confirmError = null
            )
        }
    }

    /** Step 2 button. [onDone] runs after the password was changed. */
    fun resetPassword(onDone: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        val codeErr = if (s.code.length == 6) null else "Enter the 6-digit code from your email."
        val pwErr = Validators.password(s.newPassword)
        val confirmErr = when {
            s.confirmPassword.isEmpty() -> "Confirm your new password."
            s.confirmPassword != s.newPassword -> "Passwords do not match."
            else -> null
        }
        if (codeErr != null || pwErr != null || confirmErr != null) {
            _state.update { it.copy(codeError = codeErr, passwordError = pwErr, confirmError = confirmErr) }
            return
        }
        _state.update { it.copy(busy = true, errorBanner = null) }
        viewModelScope.launch {
            try {
                recovery.resetPassword(s.email, s.code, s.newPassword)
                _state.update { it.copy(busy = false) }
                toast.show("Password updated. Please sign in.", ToastType.Success)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidRecoveryCodeException) {
                _state.update { it.copy(busy = false, errorBanner = MSG_BAD_CODE) }
            } catch (e: RestException) {
                _state.update { it.copy(busy = false, errorBanner = MSG_RESET_FAILED) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorBanner = MSG_RECOVERY_NETWORK) }
            }
        }
    }

    private companion object {
        const val K_STEP = "forgot_step"
        const val K_EMAIL = "forgot_email"
    }
}
