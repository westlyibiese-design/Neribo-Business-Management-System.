package com.westly.nbms.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.ThemePreferenceStore
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.features.device.DeviceLockControllerImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val themeStore: ThemePreferenceStore,
    private val deviceLock: DeviceLockControllerImpl,
    navigator: ShellNavigatorImpl,
    val toast: ToastController
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeStore.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    /** True when this phone has a Device PIN for the signed-in person (the "Lock" button needs it). */
    val hasDevicePin: StateFlow<Boolean> = deviceLock.hasPin

    /** Routes asked for by `ShellNavigator.open`. */
    val linkRequests: Flow<String> = navigator.requests

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { themeStore.set(mode) }
    }

    fun lockNow() = deviceLock.lockNow()

    fun signOut() {
        // The shell leaves the screen as soon as the session ends, so let the sign-out finish regardless.
        viewModelScope.launch { withContext(NonCancellable) { sessionManager.signOut() } }
    }
}
