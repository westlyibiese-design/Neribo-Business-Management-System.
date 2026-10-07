package com.westly.nbms.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The PIN keypad's own state: the digits typed so far and whether a sign-in is running. */
data class PinPadState(val pin: String = "", val submitting: Boolean = false) {

    /** OK is usable once 4 digits are in. */
    val canSubmit: Boolean get() = !submitting && pin.length >= MIN_LENGTH

    /** Six digits are submitted automatically. */
    val shouldAutoSubmit: Boolean get() = !submitting && pin.length == MAX_LENGTH

    fun press(digit: Char): PinPadState =
        if (submitting || digit !in '0'..'9' || pin.length >= MAX_LENGTH) this else copy(pin = pin + digit)

    fun delete(): PinPadState =
        if (submitting || pin.isEmpty()) this else copy(pin = pin.dropLast(1))

    fun cleared(): PinPadState = copy(pin = "")

    fun withSubmitting(value: Boolean): PinPadState = copy(submitting = value)

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 6
    }
}

/** Capital letters and digits only, at most 8 characters. */
internal fun normalizeBusinessCode(raw: String): String =
    raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)

/** A business code is 6 to 8 capital letters or digits. */
internal fun isValidBusinessCode(code: String): Boolean =
    code.length in 6..8 && code.all { it in 'A'..'Z' || it in '0'..'9' }

data class PinLoginUiState(
    val pad: PinPadState = PinPadState(),
    /** The code saved on this device, or null when there is none. */
    val rememberedCode: String? = null,
    /** True after the person taps "Change" on the remembered-code pill. */
    val editingCode: Boolean = false,
    val codeInput: String = "",
    val codeError: String? = null,
    /** Goes up by one on every wrong PIN so the dots shake again. */
    val shakeCount: Int = 0
) {
    val showCodeField: Boolean get() = rememberedCode == null || editingCode
}

internal const val ACCESS_DENIED_TITLE = "Access Denied"
internal const val MSG_NEED_BUSINESS_CODE = "Enter your business code."

@HiltViewModel
class PinLoginViewModel @Inject constructor(
    private val service: PinLoginService,
    remembered: RememberedBusiness,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(PinLoginUiState(rememberedCode = remembered.get()))
    val state: StateFlow<PinLoginUiState> = _state.asStateFlow()

    fun onDigit(digit: Char) {
        _state.update { it.copy(pad = it.pad.press(digit)) }
        if (_state.value.pad.shouldAutoSubmit) submit()
    }

    fun onDelete() {
        _state.update { it.copy(pad = it.pad.delete()) }
    }

    fun onChangeCode() {
        _state.update { it.copy(editingCode = true, codeInput = "", codeError = null) }
    }

    fun onCodeInput(raw: String) {
        _state.update { it.copy(codeInput = normalizeBusinessCode(raw), codeError = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.pad.submitting || current.pad.pin.length < PinPadState.MIN_LENGTH) return

        val code = if (current.showCodeField) current.codeInput else current.rememberedCode.orEmpty()
        if (!isValidBusinessCode(code)) {
            _state.update { it.copy(codeError = MSG_NEED_BUSINESS_CODE) }
            return
        }

        val pin = current.pad.pin
        _state.update { it.copy(pad = it.pad.withSubmitting(true), codeError = null) }
        viewModelScope.launch {
            val result = service.signIn(code, pin)
            result.onSuccess { user ->
                toast.show("Logged in as ${user.role.label}", ToastType.Success, "Welcome, ${user.name}")
                _state.update { it.copy(pad = PinPadState()) }
            }.onFailure { e ->
                val message = e.message?.takeIf { it.isNotBlank() } ?: MSG_PIN_GENERIC
                toast.show(message, ToastType.Error, ACCESS_DENIED_TITLE)
                _state.update { it.copy(pad = PinPadState(), shakeCount = it.shakeCount + 1) }
            }
        }
    }
}
