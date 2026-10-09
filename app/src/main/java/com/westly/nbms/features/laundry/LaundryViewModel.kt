package com.westly.nbms.features.laundry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
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

sealed interface LaundryView {
    data object Loading : LaundryView
    data class Error(val message: String) : LaundryView
    data class Ready(
        val active: List<LaundryRequest>,
        val counts: Map<LaundryStatus, Int>
    ) : LaundryView
}

internal fun laundryViewOf(resource: Resource<List<LaundryRequest>>): LaundryView = when (resource) {
    is Resource.Loading -> LaundryView.Loading
    is Resource.Error -> LaundryView.Error(MSG_LAUNDRY_LOAD_FAILED)
    is Resource.Success -> LaundryView.Ready(activeRequestsOldestFirst(resource.data), laundryCounts(resource.data))
}

/** The New Request sheet: whether it is open, what was typed, and whether it is saving. */
data class NewLaundryUiState(
    val open: Boolean = false,
    val form: LaundryRequestForm = LaundryRequestForm(),
    val saving: Boolean = false
)

@HiltViewModel
class LaundryViewModel @Inject internal constructor(
    private val repository: LaundryRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<LaundryRequest>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_LAUNDRY_LOAD_FAILED, it)) }

    val view: StateFlow<LaundryView> = live
        .map { laundryViewOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaundryView.Loading)

    private val _sheet = MutableStateFlow(NewLaundryUiState())
    val sheet: StateFlow<NewLaundryUiState> = _sheet.asStateFlow()

    /** Rows with a change in flight (status, charge or paid). One change per row at a time. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val busyLock = Any()

    /** The request whose Update Charge dialog is open. */
    private val _chargeTarget = MutableStateFlow<LaundryRequest?>(null)
    val chargeTarget: StateFlow<LaundryRequest?> = _chargeTarget.asStateFlow()

    /** True while the "Ending session for security…" screen is up (shared-device PIN sessions only). */
    private val _pinEnding = MutableStateFlow(false)
    val pinEnding: StateFlow<Boolean> = _pinEnding.asStateFlow()

    /** Set before any suspend call and cleared in `finally`: a second tap on Log Request does nothing. */
    private val createInFlight = AtomicBoolean(false)
    private val signOutScheduled = AtomicBoolean(false)

    fun retry() = retryTick.update { it + 1 }

    // ── New Request sheet ──

    fun openSheet() = _sheet.update { it.copy(open = true) }

    fun closeSheet() {
        if (_sheet.value.saving) return
        _sheet.update { it.copy(open = false) }
    }

    fun updateForm(change: (LaundryRequestForm) -> LaundryRequestForm) = _sheet.update { it.copy(form = change(it.form)) }

    fun submit() {
        if (!laundryHasGuestInfo(_sheet.value.form)) {
            toast.show("Enter a guest name or room number.", ToastType.Error, "Missing guest info")
            return
        }
        if (!createInFlight.compareAndSet(false, true)) return
        _sheet.update { it.copy(saving = true) }
        val form = _sheet.value.form
        val usesPin = (session.state.value as? SessionState.SignedIn)?.user?.usesPin == true

        viewModelScope.launch {
            try {
                repository.create(form)
                toast.show("Logged for ${guestOrRoomLabel(form.guestName, form.roomNumber)}.", ToastType.Success, "Laundry Request Logged")
                _sheet.update { NewLaundryUiState() } // close and reset the form
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC, ToastType.Error, "Error")
            } finally {
                createInFlight.set(false)
                _sheet.update { it.copy(saving = false) }
            }
        }
    }

    // ── row actions ──

    /** Takes the row for one action. False when it is already busy (the tap is ignored). */
    private fun claim(id: String): Boolean = synchronized(busyLock) {
        if (id in _busy.value) false else { _busy.value = _busy.value + id; true }
    }

    private fun release(id: String) = synchronized(busyLock) { _busy.value = _busy.value - id }

    /** `Mark {next}` on a row. */
    fun advance(request: LaundryRequest) {
        if (nextStatus(request.status) == null) return
        if (!claim(request.id)) return
        val usesPin = (session.state.value as? SessionState.SignedIn)?.user?.usesPin == true
        viewModelScope.launch {
            try {
                val next = repository.advanceStatus(request)
                toast.show(next.label, ToastType.Success, "Status Updated")
                if (next == LaundryStatus.DELIVERED && usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC, ToastType.Error, "Update Failed")
            } finally {
                release(request.id)
            }
        }
    }

    /** Tap on the Paid / Unpaid badge. */
    fun togglePaid(request: LaundryRequest) {
        if (!claim(request.id)) return
        viewModelScope.launch {
            try {
                val now = repository.togglePaid(request)
                toast.show(guestOrRoom(request), ToastType.Success, if (now == PaymentStatus.PAID) "Marked Paid" else "Marked Unpaid")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC, ToastType.Error, "Error")
            } finally {
                release(request.id)
            }
        }
    }

    // ── Update Charge ──

    fun openCharge(request: LaundryRequest) {
        if (request.id in _busy.value) return
        _chargeTarget.value = request
    }

    fun closeCharge() { _chargeTarget.value = null }

    /** Save in the Update Charge dialog. Invalid or negative numbers are ignored (the dialog just closes). */
    fun saveCharge(text: String, symbol: String) {
        val request = _chargeTarget.value ?: return
        _chargeTarget.value = null
        val charge = parseChargeUpdate(text) ?: return
        if (!claim(request.id)) return
        viewModelScope.launch {
            try {
                repository.updateCharge(request, charge)
                toast.show(Format.currency(charge, symbol), ToastType.Success, "Charge Updated")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC, ToastType.Error, "Error")
            } finally {
                release(request.id)
            }
        }
    }

    // ── shared-device sign-out ──

    /** Shared-device (PIN) sessions end by themselves 2.5 s after a request is logged or delivered. */
    private fun scheduleSignOut() {
        if (!signOutScheduled.compareAndSet(false, true)) return
        _pinEnding.value = true
        viewModelScope.launch {
            delay(LAUNDRY_PIN_SIGN_OUT_DELAY_MS)
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
