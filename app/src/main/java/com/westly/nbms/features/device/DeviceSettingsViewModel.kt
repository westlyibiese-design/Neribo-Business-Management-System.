package com.westly.nbms.features.device

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.ThemePreferenceStore
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DevicesState {
    data object Loading : DevicesState
    data class Loaded(val devices: List<DeviceInfo>) : DevicesState
    data class Failed(val message: String) : DevicesState
}

data class DeviceSettingsUiState(
    val devices: DevicesState = DevicesState.Loading,
    val newPin: String = "",
    val confirmPin: String = "",
    val newPinError: String? = null,
    val confirmError: String? = null,
    val saving: Boolean = false,
    val removing: DeviceInfo? = null,
    val removeBusy: Boolean = false
)

/** Returns an error for the "New PIN" field, or null when it is a valid 6-10 digit PIN. */
internal fun validateNewPin(pin: String): String? =
    if (pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it.isDigit() }) null else "PIN must be 6–10 digits."

internal fun validateConfirm(pin: String, confirm: String): String? =
    if (pin == confirm) null else "PINs don't match."

@HiltViewModel
class DeviceSettingsViewModel @Inject constructor(
    private val api: DeviceApi,
    private val deviceIds: DeviceIdProvider,
    private val controller: DeviceLockControllerImpl,
    private val biometric: BiometricUnlock,
    private val themeStore: ThemePreferenceStore,
    private val toast: ToastController,
    sections: Set<@JvmSuppressWildcards DeviceSettingsSection>
) : ViewModel() {

    val sections: List<DeviceSettingsSection> = sections.sortedBy { it.order }

    private val _state = MutableStateFlow(DeviceSettingsUiState())
    val state: StateFlow<DeviceSettingsUiState> = _state.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        themeStore.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    val thisDeviceId: String get() = deviceIds.get()

    /** This phone has a Device PIN for the signed-in person. Biometric unlock is only offered then. */
    val hasPin: StateFlow<Boolean> = controller.hasPin

    val biometricEnabled: StateFlow<Boolean> = controller.biometricEnabled

    private val _biometricBusy = MutableStateFlow(false)
    val biometricBusy: StateFlow<Boolean> = _biometricBusy.asStateFlow()

    /** True when the phone has a strong biometric set up right now. */
    fun biometricAvailable(): Boolean = biometric.isAvailable()

    /** Switch on or off. Turning on asks for the fingerprint once, so only someone who can unlock the phone can enable it. */
    fun setBiometric(activity: FragmentActivity?, enable: Boolean) {
        if (_biometricBusy.value) return
        if (!enable) {
            controller.disableBiometric()
            toast.show("You'll use your PIN to unlock this phone.", ToastType.Success, "Biometric unlock off")
            return
        }
        val uid = controller.currentUserId()
        if (activity == null || uid == null || !controller.hasPin.value) {
            toast.show("Set a Device PIN on this phone first.", ToastType.Error, "Couldn't turn on")
            return
        }
        _biometricBusy.value = true
        viewModelScope.launch {
            try {
                when (
                    val result = biometric.authenticate(
                        activity = activity,
                        uid = uid,
                        title = BIOMETRIC_PROMPT_TITLE,
                        subtitle = "Confirm it's you to turn on biometric unlock"
                    )
                ) {
                    BiometricResult.Success -> {
                        if (controller.enableBiometric()) {
                            toast.show("You can now unlock with your fingerprint. Your PIN still works.", ToastType.Success, "Biometric unlock on")
                        }
                    }
                    BiometricResult.Cancelled -> Unit
                    BiometricResult.KeyInvalidated -> toast.show(MSG_BIOMETRIC_KEY_CHANGED, ToastType.Error, "Couldn't turn on")
                    is BiometricResult.Failed -> toast.show(result.message, ToastType.Error, "Couldn't turn on")
                }
            } finally {
                _biometricBusy.value = false
            }
        }
    }

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(devices = DevicesState.Loading) }
        viewModelScope.launch {
            api.list().fold(
                onSuccess = { list -> _state.update { it.copy(devices = DevicesState.Loaded(list)) } },
                onFailure = { e ->
                    _state.update { it.copy(devices = DevicesState.Failed(e.message ?: MSG_DEVICE_GENERIC)) }
                }
            )
        }
    }

    fun selectTheme(mode: ThemeMode) {
        viewModelScope.launch { themeStore.set(mode) }
    }

    fun onNewPin(value: String) {
        _state.update { it.copy(newPin = value.filter(Char::isDigit).take(PIN_MAX_LENGTH), newPinError = null, confirmError = null) }
    }

    fun onConfirmPin(value: String) {
        _state.update { it.copy(confirmPin = value.filter(Char::isDigit).take(PIN_MAX_LENGTH), confirmError = null) }
    }

    fun savePin() {
        val s = _state.value
        if (s.saving) return
        val pinError = validateNewPin(s.newPin)
        val confirmError = if (pinError == null) validateConfirm(s.newPin, s.confirmPin) else null
        if (pinError != null || confirmError != null) {
            _state.update { it.copy(newPinError = pinError, confirmError = confirmError) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = api.setPin(deviceIds.get(), deviceLabel(), s.newPin)
            val failure = result.exceptionOrNull()
            if (failure == null) {
                controller.onPinSaved()
                _state.update { it.copy(saving = false, newPin = "", confirmPin = "", newPinError = null, confirmError = null) }
                toast.show(
                    "This device will now lock automatically after 5 minutes away, and ask for this PIN to continue.",
                    ToastType.Success,
                    "Device PIN saved"
                )
                load()
            } else {
                _state.update { it.copy(saving = false) }
                toast.show(failure.message ?: MSG_DEVICE_GENERIC, ToastType.Error, "Couldn't save PIN")
            }
        }
    }

    fun askRemove(device: DeviceInfo) = _state.update { it.copy(removing = device) }

    fun cancelRemove() = _state.update { if (it.removeBusy) it else it.copy(removing = null) }

    fun confirmRemove() {
        val target = _state.value.removing ?: return
        if (_state.value.removeBusy) return
        _state.update { it.copy(removeBusy = true) }
        viewModelScope.launch {
            val failure = api.revoke(target.registrationId).exceptionOrNull()
            _state.update { it.copy(removeBusy = false, removing = null) }
            if (failure == null) {
                if (target.deviceId == deviceIds.get()) controller.onDeviceRemoved()
                toast.show("${target.deviceLabel} was removed.", ToastType.Success, "Device removed")
                load()
            } else {
                toast.show(failure.message ?: MSG_DEVICE_GENERIC, ToastType.Error, "Couldn't remove device")
            }
        }
    }
}
