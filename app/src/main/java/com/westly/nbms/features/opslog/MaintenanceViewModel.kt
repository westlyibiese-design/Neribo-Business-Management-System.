package com.westly.nbms.features.opslog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

sealed interface MaintenanceView {
    data object Loading : MaintenanceView
    data class Error(val message: String) : MaintenanceView
    /** [open] is sorted most urgent first; [recentlyClosed] holds at most five, newest closed first. */
    data class Ready(val open: List<MaintenanceRequest>, val recentlyClosed: List<MaintenanceRequest>) : MaintenanceView
}

internal fun maintenanceViewOf(resource: Resource<List<MaintenanceRequest>>): MaintenanceView = when (resource) {
    is Resource.Loading -> MaintenanceView.Loading
    is Resource.Error -> MaintenanceView.Error(MSG_MAINTENANCE_LOAD_FAILED)
    is Resource.Success -> MaintenanceView.Ready(openMaintenance(resource.data), recentlyClosedMaintenance(resource.data))
}

/** The Log Maintenance Request sheet: whether it is open, what was typed, and whether it is saving. */
data class LogMaintenanceUiState(
    val open: Boolean = false,
    val form: MaintenanceForm = MaintenanceForm(),
    val saving: Boolean = false
)

@HiltViewModel
class MaintenanceViewModel @Inject internal constructor(
    private val repository: MaintenanceRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<MaintenanceRequest>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_MAINTENANCE_LOAD_FAILED, it)) }

    val view: StateFlow<MaintenanceView> = live
        .map { maintenanceViewOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceView.Loading)

    /** Rooms for the Room dropdown. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rooms: StateFlow<List<MaintenanceRoomOption>> = retryTick
        .flatMapLatest { repository.observeRooms() }
        .map { (it as? Resource.Success)?.data ?: emptyList() }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _log = MutableStateFlow(LogMaintenanceUiState())
    val log: StateFlow<LogMaintenanceUiState> = _log.asStateFlow()

    /** The ids of the requests being closed right now (their Close button shows "Closing…"). */
    private val _closing = MutableStateFlow<Set<String>>(emptySet())
    val closing: StateFlow<Set<String>> = _closing.asStateFlow()

    /** True while the "Ending session for security…" screen is up (shared-device PIN sessions only). */
    private val _pinEnding = MutableStateFlow(false)
    val pinEnding: StateFlow<Boolean> = _pinEnding.asStateFlow()

    private val createInFlight = AtomicBoolean(false)
    private val signOutScheduled = AtomicBoolean(false)

    fun retry() = retryTick.update { it + 1 }

    // ── Log Request sheet ──

    fun openLogSheet() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!maintenanceCanLog(signedIn.user.role)) return
        _log.value = LogMaintenanceUiState(open = true)
    }

    fun closeLogSheet() {
        if (_log.value.saving) return
        _log.value = LogMaintenanceUiState()
    }

    fun updateForm(change: (MaintenanceForm) -> MaintenanceForm) = _log.update { it.copy(form = change(it.form)) }

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val state = _log.value
        if (state.saving) return

        val input = when (val check = validateMaintenance(state.form, rooms.value)) {
            is MaintenanceCheck.Ready -> check.input
            is MaintenanceCheck.Toast -> {
                toast.show(check.message, ToastType.Error, check.title)
                return
            }
        }
        if (!createInFlight.compareAndSet(false, true)) return
        _log.update { it.copy(saving = true) }
        val usesPin = signedIn.user.usesPin

        viewModelScope.launch {
            try {
                val result = repository.create(input)
                val message = logResultToast(input, result.roomError)
                toast.show(message.message, if (result.roomError == null) ToastType.Success else ToastType.Info, message.title)
                _log.value = LogMaintenanceUiState() // close and reset the form
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_MAINTENANCE_GENERIC, ToastType.Error, "Error")
                _log.update { it.copy(saving = false) }
            } finally {
                createInFlight.set(false)
            }
        }
    }

    // ── Close ──

    /** The Close button of one open request. Closing does not end a PIN session. */
    fun close(request: MaintenanceRequest) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!maintenanceCanClose(signedIn.user.role)) return
        if (request.id in _closing.value) return
        _closing.update { it + request.id }

        viewModelScope.launch {
            try {
                val freed = repository.close(request)
                val message = closeResultToast(request.roomNumber, freed)
                toast.show(message.message, ToastType.Success, message.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_MAINTENANCE_GENERIC, ToastType.Error, "Error")
            } finally {
                _closing.update { it - request.id }
            }
        }
    }

    // ── shared-device sign-out ──

    /** Shared-device (PIN) sessions end by themselves 2.5 s after a request is logged. */
    private fun scheduleSignOut() {
        if (!signOutScheduled.compareAndSet(false, true)) return
        _pinEnding.value = true
        viewModelScope.launch {
            delay(MAINTENANCE_PIN_SIGN_OUT_DELAY_MS)
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The person can still sign out by hand.
            } finally {
                _pinEnding.value = false
                signOutScheduled.set(false)
            }
        }
    }
}
