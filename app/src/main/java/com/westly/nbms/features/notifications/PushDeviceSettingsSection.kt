package com.westly.nbms.features.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.device.DeviceSettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@HiltViewModel
class PushSettingsViewModel @Inject constructor(
    private val registrar: PushRegistrar,
    private val toast: ToastController
) : ViewModel() {

    val enabled: StateFlow<Boolean> =
        registrar.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun turnOn() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            registrar.enable().fold(
                onSuccess = { toast.show("This device will receive push notifications.", ToastType.Success, "Notifications enabled") },
                onFailure = { toast.show(it.message ?: "Please try again.", ToastType.Error, "Couldn't update notifications") }
            )
            _busy.value = false
        }
    }

    fun turnOff() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            registrar.disable().fold(
                onSuccess = { toast.show("This device will no longer receive push notifications.", ToastType.Success, "Notifications disabled") },
                onFailure = { toast.show(it.message ?: "Please try again.", ToastType.Error, "Couldn't update notifications") }
            )
            _busy.value = false
        }
    }

    fun permissionBlocked() {
        toast.show(
            "This device's notification permission was previously denied. Enable it in your phone's app settings, then try again.",
            ToastType.Error,
            "Notifications blocked"
        )
    }
}

/** The "Push notifications" card on Device Settings (order 30). */
@Singleton
class PushDeviceSettingsSection @Inject constructor() : DeviceSettingsSection {

    override val order: Int = 30

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        PushSettingsCard()
    }
}

@Composable
private fun PushSettingsCard(vm: PushSettingsViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.turnOn() else vm.permissionBlocked()
    }

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Push notifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Push notifications for this device. Turning this off stops delivery to this device specifically — " +
                        "other devices you're signed in on are unaffected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NbmsSwitch(
                checked = enabled,
                enabled = !busy,
                label = if (enabled) "On" else "Off",
                onCheckedChange = { on ->
                    if (!on) {
                        vm.turnOff()
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.turnOn()
                    } else {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }
    }
}
