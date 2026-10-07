package com.westly.nbms.features.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val PIN_MIN_LENGTH = 6
const val PIN_MAX_LENGTH = 10

/** The digits typed so far. Pure, so it can be tested. */
data class LockPad(val pin: String = "") {
    val canSubmit: Boolean get() = pin.length >= PIN_MIN_LENGTH
    val isFull: Boolean get() = pin.length >= PIN_MAX_LENGTH
    fun add(c: Char): LockPad = if (!c.isDigit() || isFull) this else LockPad(pin + c)
    fun delete(): LockPad = if (pin.isEmpty()) this else LockPad(pin.dropLast(1))
}

data class DeviceLockUiState(
    val pad: LockPad = LockPad(),
    val error: String? = null,
    val verifying: Boolean = false,
    val shakeCount: Int = 0,
    /** Too many wrong PINs: the keypad stays disabled; the person can still log out. */
    val pausedByServer: Boolean = false
)

@HiltViewModel
class DeviceLockViewModel @Inject constructor(
    private val api: DeviceApi,
    private val deviceIds: DeviceIdProvider,
    private val controller: DeviceLockControllerImpl,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceLockUiState())
    val state: StateFlow<DeviceLockUiState> = _state.asStateFlow()

    fun onDigit(c: Char) {
        val s = _state.value
        if (s.verifying || s.pausedByServer) return
        val pad = s.pad.add(c)
        if (pad == s.pad) return
        _state.update { it.copy(pad = pad, error = null) }
        if (pad.isFull) submit()
    }

    fun onDelete() {
        val s = _state.value
        if (s.verifying || s.pausedByServer) return
        _state.update { it.copy(pad = it.pad.delete(), error = null) }
    }

    fun submit() {
        val s = _state.value
        if (!s.pad.canSubmit || s.verifying || s.pausedByServer) return
        val pin = s.pad.pin
        _state.update { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            val result = api.verifyPin(deviceIds.get(), pin)
            val failure = result.exceptionOrNull()
            if (failure == null) {
                _state.value = DeviceLockUiState()
                controller.onUnlocked()
                return@launch
            }
            val status = (failure as? DeviceApiException)?.status ?: 0
            when {
                // This phone is no longer registered (removed from another device): there is nothing to unlock.
                status == 404 -> {
                    _state.value = DeviceLockUiState()
                    controller.onDeviceRemoved()
                }
                failure is DeviceLockedException -> _state.update {
                    it.copy(
                        verifying = false,
                        pad = LockPad(),
                        pausedByServer = true,
                        error = failure.message ?: MSG_DEVICE_GENERIC,
                        shakeCount = it.shakeCount + 1
                    )
                }
                status == 401 -> _state.update {
                    it.copy(
                        verifying = false,
                        pad = LockPad(),
                        error = failure.message ?: "Incorrect PIN.",
                        shakeCount = it.shakeCount + 1
                    )
                }
                // Could not reach the server: keep the digits so they can try again.
                else -> _state.update { it.copy(verifying = false, error = failure.message ?: MSG_DEVICE_GENERIC) }
            }
        }
    }

    fun logOut() {
        viewModelScope.launch {
            _state.value = DeviceLockUiState()
            sessionManager.signOut()
        }
    }
}
