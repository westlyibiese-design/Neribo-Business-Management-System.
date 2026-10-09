package com.westly.nbms.features.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.bookings.Booking
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Only the part of `settings/hotel` this page needs (a private copy so no other phase's model is imported). */
data class CheckOutHotelSettings(val checkOutTime: String = DEFAULT_CHECK_OUT_TIME)

/** The checked-in guests as the page shows them. */
data class CheckOutGuests(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val guests: List<Booking> = emptyList()
)

data class CheckOutUiState(
    val filters: CheckOutFilters,
    /** The booking whose Confirm Check-Out dialog is open. */
    val selectedId: String? = null,
    val draft: CheckOutDraft = CheckOutDraft(),
    /** True while a check-out runs. */
    val busy: Boolean = false,
    /** True after 6 seconds of saving: the "Still working" line appears. */
    val slow: Boolean = false,
    /** Set after a saved check-out; the success screen replaces the list. */
    val success: CheckOutSuccess? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CheckOutViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val repository: CheckOutRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(CheckOutUiState(CheckOutFilters.initial(today())))
    val state: StateFlow<CheckOutUiState> = _state.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    /** Live bookings, kept to the checked-in ones on the phone (no composite index needed). */
    val guests: StateFlow<CheckOutGuests> = retryTick
        .flatMapLatest { firestore.observeList("bookings", Booking::class.java) }
        .map { r ->
            when (r) {
                is Resource.Loading -> CheckOutGuests(loading = true)
                is Resource.Error -> CheckOutGuests(loading = false, failed = true)
                is Resource.Success -> CheckOutGuests(loading = false, guests = checkedInGuests(r.data))
            }
        }
        .catch { emit(CheckOutGuests(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckOutGuests())

    /** Live `settings/hotel.checkOutTime`, "11:00" until it is known. */
    val checkOutTime: StateFlow<String> = firestore.observeDoc("settings", "hotel", CheckOutHotelSettings::class.java)
        .map { r -> if (r is Resource.Success) officialCheckOutTime(r.data?.checkOutTime) else DEFAULT_CHECK_OUT_TIME }
        .catch { emit(DEFAULT_CHECK_OUT_TIME) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_CHECK_OUT_TIME)

    /** The synchronous "one check-out at a time" flag; a second tap can never get past it. */
    private val inFlight = AtomicBoolean(false)

    fun retry() {
        retryTick.update { it + 1 }
    }

    // ---- filters ----

    fun setQuery(v: String) = editFilters { it.copy(query = v) }
    fun setDue(v: DueFilter) = editFilters { it.copy(due = v) }
    fun setSpecificDate(v: LocalDate) = editFilters { it.copy(specificDate = v) }

    /** A start after the end pushes the end along. */
    fun setRangeStart(v: LocalDate) = editFilters { it.copy(rangeStart = v, rangeEnd = if (it.rangeEnd < v) v else it.rangeEnd) }

    /** An end before the start is raised to the start. */
    fun setRangeEnd(v: LocalDate) = editFilters { it.copy(rangeEnd = if (v < it.rangeStart) it.rangeStart else v) }

    /** "Clear filters": empty search and the All chip. */
    fun clearFilters() = editFilters { it.copy(query = "", due = DueFilter.ALL) }

    private fun editFilters(change: (CheckOutFilters) -> CheckOutFilters) {
        _state.update { it.copy(filters = change(it.filters)) }
    }

    // ---- the dialog ----

    fun open(booking: Booking) {
        if (_state.value.busy) return
        _state.update { it.copy(selectedId = booking.id, draft = CheckOutDraft.startingAt(Clock.System.now(), currentZone())) }
    }

    fun closeDialog() {
        if (_state.value.busy) return
        _state.update { it.copy(selectedId = null) }
    }

    fun setActualDate(v: LocalDate) = editDraft { it.copy(actualDate = v) }
    fun setActualTime(v: LocalTime) = editDraft { it.copy(actualTime = v) }
    fun setExtras(v: String) = editDraft { it.copy(extrasText = sanitizeDecimal(v)) }
    fun setMethod(v: CheckOutPaymentMethod) = editDraft { it.copy(method = v) }
    fun setNotes(v: String) = editDraft { it.copy(notes = v) }

    private fun editDraft(change: (CheckOutDraft) -> CheckOutDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
    }

    // ---- submit ----

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val ui = _state.value
        val booking = guests.value.guests.firstOrNull { it.id == ui.selectedId } ?: return
        if (!inFlight.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true, slow = false) }

        val zone = zoneOrLagos(signedIn.business.timezone)
        val input = CheckOutInput(
            actualAt = ui.draft.actualAt(zone),
            extraCharges = parseExtras(ui.draft.extrasText),
            method = ui.draft.method,
            notes = ui.draft.notes,
            staff = CheckOutStaff(signedIn.user.uid, signedIn.user.name),
            zone = zone,
            officialCheckOutTime = checkOutTime.value,
            businessName = signedIn.business.name,
            currencySymbol = signedIn.business.currencySymbol
        )
        val usesPin = signedIn.user.usesPin

        val slowNotice = viewModelScope.launch {
            delay(SLOW_NOTICE_DELAY_MS)
            _state.update { it.copy(slow = true) }
        }
        viewModelScope.launch {
            try {
                when (val result = repository.checkOut(booking, input)) {
                    is CheckOutResult.Done -> {
                        _state.update { it.copy(success = result.success, selectedId = null) }
                        toast.show(
                            completeMessage(result.success.guestName, result.success.summary.amountToCharge > 0.0),
                            ToastType.Success,
                            TITLE_CHECKOUT_COMPLETE
                        )
                        if (usesPin) scheduleSignOut()
                    }
                    is CheckOutResult.Rejected -> toast.show(result.message, ToastType.Error, result.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC, ToastType.Error, TITLE_CHECKOUT_FAILED)
            } finally {
                slowNotice.cancel()
                inFlight.set(false)
                _state.update { it.copy(busy = false, slow = false) }
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

    /** "Check Out Another Guest": back to the list. */
    fun checkOutAnother() {
        _state.update { it.copy(success = null) }
    }

    fun receiptFailed() {
        toast.show(MSG_RECEIPT_FAILED, ToastType.Error, TITLE_RECEIPT_FAILED)
    }

    private fun currentZone(): TimeZone =
        zoneOrLagos((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    private fun today(): LocalDate {
        val zone = currentZone()
        return Clock.System.now().localDate(zone)
    }
}
