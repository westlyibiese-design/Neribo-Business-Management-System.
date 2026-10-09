package com.westly.nbms.features.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class WalkInUiState(
    val form: WalkInForm,
    /** True while a submit runs; the button shows "Processing…". */
    val busy: Boolean = false,
    /** Set after a saved walk-in; the success screen replaces the form. */
    val success: WalkInSuccess? = null,
    /** Turned on by the first tap on the button, so the "required" messages appear under the fields. */
    val showErrors: Boolean = false
)

/** The room list as the page shows it. */
data class WalkInRooms(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val rooms: List<Room> = emptyList()
)

@HiltViewModel
class WalkInViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val repository: WalkInRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(WalkInUiState(WalkInForm.initial(Clock.System.now(), currentZone())))
    val state: StateFlow<WalkInUiState> = _state.asStateFlow()

    /** Every room of every status (deleted ones left out). The picker shows them all; only available ones can be submitted. */
    val rooms: StateFlow<WalkInRooms> = firestore.observeList("rooms", Room::class.java)
        .map { r ->
            when (r) {
                is Resource.Loading -> WalkInRooms(loading = true)
                is Resource.Error -> WalkInRooms(loading = false, failed = true)
                is Resource.Success -> WalkInRooms(loading = false, rooms = listedRooms(r.data))
            }
        }
        .catch { emit(WalkInRooms(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalkInRooms())

    /** Live `settings/hotel.checkOutTime`, "11:00" until it is known. */
    val checkOutTime: StateFlow<String> = firestore.observeDoc("settings", "hotel", WalkInHotelSettings::class.java)
        .map { r -> if (r is Resource.Success) officialCheckOutTime(r.data?.checkOutTime) else DEFAULT_CHECK_OUT_TIME }
        .catch { emit(DEFAULT_CHECK_OUT_TIME) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_CHECK_OUT_TIME)

    /** The synchronous "one submit at a time" flag; a second tap can never get past it. */
    private val inFlight = AtomicBoolean(false)

    // ---- form changes ----

    fun setFullName(v: String) = edit { it.copy(fullName = v) }
    fun setPhone(v: String) = edit { it.copy(phone = v) }
    fun setEmail(v: String) = edit { it.copy(email = v) }
    fun setNationality(v: String) = edit { it.copy(nationality = v) }
    fun setIdDocumentRef(v: String) = edit { it.copy(idDocumentRef = v) }
    fun setRoom(roomId: String) = edit { it.copy(roomId = roomId) }
    fun setAdults(v: Int) = edit { it.copy(adults = v) }
    fun setChildren(v: Int) = edit { it.copy(children = v) }
    fun setPaymentOption(v: PaymentOption) = edit { it.copy(paymentOption = v) }
    fun setPaymentMethod(v: PaymentMethod) = edit { it.copy(paymentMethod = v) }
    fun setNotes(v: String) = edit { it.copy(notes = v) }
    fun setCheckInTime(v: LocalTime) = edit { it.copy(checkInTime = v) }

    /** A later check-in date pushes the check-out to the next day when it would otherwise fall before it. */
    fun setCheckInDate(v: LocalDate) = edit { f ->
        val out = f.checkOutDate
        f.copy(checkInDate = v, checkOutDate = if (out == null || out < v) v.plus(DatePeriod(days = 1)) else out)
    }

    /** The check-out date can never be earlier than the check-in date. */
    fun setCheckOutDate(v: LocalDate) = edit { f ->
        val min = f.checkInDate
        f.copy(checkOutDate = if (min != null && v < min) min else v)
    }

    private fun edit(change: (WalkInForm) -> WalkInForm) {
        _state.update { it.copy(form = change(it.form)) }
    }

    // ---- submit ----

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val form = _state.value.form
        val room = rooms.value.rooms.firstOrNull { it.id == form.roomId } ?: return
        if (form.fullName.isBlank()) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        if (fieldErrors(form).any) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true) }

        val staff = WalkInStaff(signedIn.user.uid, signedIn.user.name)
        val zone = zoneOrLagos(signedIn.business.timezone)
        val officialTime = checkOutTime.value
        val usesPin = signedIn.user.usesPin

        viewModelScope.launch {
            try {
                val result = repository.submit(
                    form = form,
                    room = room,
                    staff = staff,
                    zone = zone,
                    officialCheckOutTime = officialTime,
                    onConnectionLost = { toast.show(MSG_CONNECTION_LOST, ToastType.Info, TITLE_CONNECTION_LOST) }
                )
                when (result) {
                    is WalkInResult.Done -> {
                        _state.update {
                            it.copy(success = result.success, form = WalkInForm.initial(Clock.System.now(), zone), showErrors = false)
                        }
                        toast.show(
                            completeMessage(result.success.guestName, result.success.paidNow),
                            ToastType.Success,
                            TITLE_COMPLETE
                        )
                        if (usesPin) scheduleSignOut()
                    }
                    is WalkInResult.Rejected -> toast.show(result.message, ToastType.Error, result.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC, ToastType.Error, TITLE_FAILED)
            } finally {
                inFlight.set(false)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Shared-device (PIN) sessions end by themselves after the task, like Westly. */
    private fun scheduleSignOut() {
        viewModelScope.launch {
            delay(PIN_SIGN_OUT_DELAY_MS)
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The person can still sign out by hand.
            }
        }
    }

    /** "Register Another Walk-In": back to the empty form. */
    fun registerAnother() {
        _state.update { it.copy(success = null) }
    }

    private fun currentZone(): TimeZone =
        zoneOrLagos((session.state.value as? SessionState.SignedIn)?.business?.timezone)
}
