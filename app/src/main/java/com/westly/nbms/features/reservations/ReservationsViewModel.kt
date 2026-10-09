package com.westly.nbms.features.reservations

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// ---- Texts (copied from Westly's RoomReservationsPage) ---------------------------------------------

internal const val UI_LOAD_FAILED = "We couldn't load room reservations."
internal const val UI_TITLE_STATUS_OK = "Reservation Updated"
internal const val UI_TITLE_STATUS_FAILED = "Update Failed"
internal const val UI_TITLE_OFFLINE = "You're Offline"
internal const val UI_MSG_OFFLINE = "No internet connection detected. Please reconnect and try again."
internal const val UI_TITLE_CHECKIN_FAILED = "Check-In Failed"
internal const val UI_TITLE_CHECKIN_COMPLETE = "Check-In Complete"
internal const val UI_MSG_BAD_DATE = "Please enter a valid check-in date and time."
internal const val UI_MSG_NO_ENTITLED = "Could not compute the entitled check-out date. Please try again."
internal const val UI_MSG_GENERIC = "Something went wrong. Please try again."
internal const val UI_MSG_STILL_WORKING =
    "Still working — please don't refresh or leave this page. This can take longer on a slow connection."

/** Pay at Check-In / Pay at Check-Out help texts. Phase 13 must use the same wording. */
internal const val UI_HELP_PAY_AT_CHECK_IN = "Guest pays now. The payment is sent to the Accountant for approval."
internal const val UI_HELP_PAY_AT_CHECK_OUT = "Guest pays when leaving. Payment will be collected at check-out."

/** The "Still working" hint appears after this long. */
internal const val UI_SLOW_NOTICE_DELAY_MS = 6_000L

internal fun PaymentOption.help(): String =
    if (this == PaymentOption.PAY_AT_CHECK_IN) UI_HELP_PAY_AT_CHECK_IN else UI_HELP_PAY_AT_CHECK_OUT

/** "Reservation no show." */
internal fun statusToastMessage(status: BookingStatus): String = "Reservation ${status.key.replace('_', ' ')}."

internal fun checkInDoneMessage(guestName: String, paidNow: Boolean): String =
    if (paidNow) "$guestName has been checked in. Payment sent to the Accountant for approval."
    else "$guestName has been checked in. Payment will be collected at check-out."

// ---- Small seams (so the unit tests need no Android or Firebase) -----------------------------------

/** The live list of bookings. The only data the screen reads itself; it never writes. */
fun interface ReservationsSource {
    fun observe(): Flow<Resource<List<Booking>>>
}

class FirestoreReservationsSource @Inject constructor(
    private val firestore: BusinessFirestore
) : ReservationsSource {
    override fun observe(): Flow<Resource<List<Booking>>> = firestore.observeList("bookings", Booking::class.java)
}

/** Is the phone online right now? */
fun interface ReservationsNetwork {
    fun isOnline(): Boolean
}

class AndroidReservationsNetwork @Inject constructor(
    @ApplicationContext private val context: Context
) : ReservationsNetwork {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

// ---- What a card button does -----------------------------------------------------------------------

/** The label of a card button and where it leads: a status change, or (null) the check-in dialog. */
internal data class ActionSpec(val label: String, val status: BookingStatus?)

internal fun actionSpec(action: ReservationAction): ActionSpec = when (action) {
    ReservationAction.CONFIRM_ARRIVAL -> ActionSpec("Confirm Arrival", BookingStatus.CONFIRMED)
    ReservationAction.REJECT -> ActionSpec("Reject", BookingStatus.REJECTED)
    ReservationAction.CHECK_IN -> ActionSpec("Check In", null)
    ReservationAction.CANCEL -> ActionSpec("Cancel", BookingStatus.CANCELLED)
    ReservationAction.NO_SHOW -> ActionSpec("No Show", BookingStatus.NO_SHOW)
}

// ---- Time helpers ----------------------------------------------------------------------------------

internal fun reservationZone(id: String?): TimeZone = try {
    if (id.isNullOrBlank()) TimeZone.of("Africa/Lagos") else TimeZone.of(id)
} catch (e: Exception) {
    TimeZone.of("Africa/Lagos")
}

/**
 * Not named toInstant: a Firestore Timestamp has its own toInstant() (java.time) that would win over an extension.
 * Gives a kotlinx-datetime Instant (null stays null).
 */
internal fun Timestamp?.asKotlinInstant(): Instant? =
    this?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()) }

private fun TimeZone.asJavaZone(): ZoneId = try {
    ZoneId.of(id)
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

/** The moment shown on a card: the real check-in time when there is one, else the booked check-in. */
internal fun cardCheckIn(b: Booking): Instant? = b.checkInAt.asKotlinInstant() ?: b.checkIn.asKotlinInstant()

// ---- Check-in checks before the service is called --------------------------------------------------

/** The result of the checks the screen makes before it calls the service. */
internal sealed interface CheckInCheck {
    data class Ok(val checkInAt: Instant, val entitledCheckOut: Instant) : CheckInCheck
    data class Rejected(val title: String, val message: String) : CheckInCheck
}

/** Offline, then an invalid date, then no entitled check-out: the first problem found is the one shown. */
internal fun validateCheckIn(
    online: Boolean,
    date: LocalDate?,
    time: LocalTime?,
    nights: Int,
    officialTime: String?,
    zone: TimeZone
): CheckInCheck {
    if (!online) return CheckInCheck.Rejected(UI_TITLE_OFFLINE, UI_MSG_OFFLINE)
    if (date == null || time == null) return CheckInCheck.Rejected(UI_TITLE_CHECKIN_FAILED, UI_MSG_BAD_DATE)
    val at = LocalDateTime(date, time).toInstant(zone)
    val entitled = ReservationRules.entitledCheckOut(at, nights, officialTime, zone.asJavaZone())
        ?: return CheckInCheck.Rejected(UI_TITLE_CHECKIN_FAILED, UI_MSG_NO_ENTITLED)
    return CheckInCheck.Ok(at, entitled)
}

// ---- State -----------------------------------------------------------------------------------------

enum class LoadStatus { LOADING, ERROR, READY }

data class ReservationChip(val filter: ReservationFilter, val text: String, val selected: Boolean)

/** What the list shows. [visible] is exactly ReservationRules.filtered(...) of the live reservations. */
data class ReservationsUi(
    val status: LoadStatus = LoadStatus.LOADING,
    val filter: ReservationFilter = ReservationFilter.ALL,
    val query: String = "",
    val chips: List<ReservationChip> = emptyList(),
    val visible: List<Booking> = emptyList()
)

/** The Confirm Check-In dialog. The date and time are the business-zone wall clock; null means "not a valid date". */
data class CheckInDialogState(
    val booking: Booking? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val idDocumentRef: String = "",
    val paymentOption: PaymentOption = PaymentOption.PAY_AT_CHECK_IN,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val notes: String = "",
    val officialTime: String = DEFAULT_OFFICIAL_CHECK_OUT,
    /** "Guest may stay until"; recomputed whenever the date, the time or the official time changes. */
    val entitledCheckOut: Instant? = null,
    val busy: Boolean = false,
    /** True after 6 seconds of saving. */
    val slow: Boolean = false,
    /** Set after a saved check-in; the success screen replaces the list. */
    val success: CheckInOutcome? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReservationsViewModel @Inject constructor(
    private val source: ReservationsSource,
    private val service: ReservationsService,
    private val network: ReservationsNetwork,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val filter = MutableStateFlow(ReservationFilter.ALL)
    private val query = MutableStateFlow("")
    private val retryTick = MutableStateFlow(0)

    private sealed interface Loaded {
        data object Loading : Loaded
        data object Failed : Loaded
        data class Ready(val reservations: List<Booking>) : Loaded
    }

    private val loaded: Flow<Loaded> = retryTick
        .flatMapLatest { source.observe() }
        .map { r ->
            when (r) {
                is Resource.Loading -> Loaded.Loading
                is Resource.Error -> Loaded.Failed
                is Resource.Success -> Loaded.Ready(ReservationRules.roomReservations(r.data))
            }
        }
        .catch { emit(Loaded.Failed) }

    /** The list, the chips (with their counts) and the search, all in one. */
    val ui: StateFlow<ReservationsUi> = combine(loaded, filter, query) { data, f, q ->
        when (data) {
            is Loaded.Loading -> ReservationsUi(LoadStatus.LOADING, f, q, chipsFor(emptyList(), f))
            is Loaded.Failed -> ReservationsUi(LoadStatus.ERROR, f, q, chipsFor(emptyList(), f))
            is Loaded.Ready -> ReservationsUi(
                status = LoadStatus.READY,
                filter = f,
                query = q,
                chips = chipsFor(data.reservations, f),
                visible = ReservationRules.filtered(data.reservations, f, q)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReservationsUi(chips = chipsFor(emptyList(), ReservationFilter.ALL)))

    private fun chipsFor(reservations: List<Booking>, selected: ReservationFilter): List<ReservationChip> {
        val counts = ReservationRules.counts(reservations)
        return ReservationFilter.entries.map { f ->
            ReservationChip(f, "${f.label} (${counts[f] ?: 0})", f == selected)
        }
    }

    fun setFilter(v: ReservationFilter) {
        filter.value = v
    }

    fun setQuery(v: String) {
        query.value = v
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    // ---- Card buttons ----

    /** True while a status change runs; every card button is disabled. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val statusInFlight = AtomicBoolean(false)

    /** One card button was tapped: a status change, or the check-in dialog. */
    fun onAction(booking: Booking, action: ReservationAction) {
        val status = actionSpec(action).status
        if (status == null) openCheckIn(booking) else changeStatus(booking, status)
    }

    fun changeStatus(booking: Booking, newStatus: BookingStatus) {
        if (!statusInFlight.compareAndSet(false, true)) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val failure = service.changeStatus(booking, newStatus).exceptionOrNull()
                if (failure == null) {
                    toast.show(statusToastMessage(newStatus), ToastType.Success, UI_TITLE_STATUS_OK)
                } else {
                    toast.show(failure.message?.takeIf { it.isNotBlank() } ?: UI_MSG_GENERIC, ToastType.Error, UI_TITLE_STATUS_FAILED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: UI_MSG_GENERIC, ToastType.Error, UI_TITLE_STATUS_FAILED)
            } finally {
                statusInFlight.set(false)
                _busy.value = false
            }
        }
    }

    // ---- Check-in dialog ----

    private val _checkIn = MutableStateFlow(CheckInDialogState())
    val checkIn: StateFlow<CheckInDialogState> = _checkIn.asStateFlow()

    /** The synchronous "one check-in at a time" flag; a second tap can never get past it. */
    private val checkInInFlight = AtomicBoolean(false)
    private var officialTimeJob: Job? = null

    private fun currentZone(): TimeZone =
        reservationZone((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    /** Seeds check-in = now, Pay at Check-In, Cash, and loads the hotel's official check-out time once. */
    fun openCheckIn(booking: Booking) {
        if (_checkIn.value.busy || checkInInFlight.get()) return
        val zone = currentZone()
        val local = Clock.System.now().toLocalDateTime(zone)
        _checkIn.value = recomputed(
            CheckInDialogState(booking = booking, date = local.date, time = LocalTime(local.hour, local.minute)),
            zone
        )
        officialTimeJob?.cancel()
        officialTimeJob = viewModelScope.launch {
            val official = try {
                service.loadCheckOutTime()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DEFAULT_OFFICIAL_CHECK_OUT
            }
            _checkIn.update { st -> if (st.booking?.id == booking.id) recomputed(st.copy(officialTime = official), zone) else st }
        }
    }

    fun closeCheckIn() {
        if (_checkIn.value.busy) return
        officialTimeJob?.cancel()
        _checkIn.update { it.copy(booking = null) }
    }

    /** "Check In Another Guest": the success screen closes and the list is back. */
    fun checkInAnother() {
        _checkIn.value = CheckInDialogState()
    }

    fun setCheckInDate(v: LocalDate?) = editCheckIn { it.copy(date = v) }
    fun setCheckInTime(v: LocalTime?) = editCheckIn { it.copy(time = v) }
    fun setIdDocumentRef(v: String) = editCheckIn { it.copy(idDocumentRef = v) }
    fun setPaymentOption(v: PaymentOption) = editCheckIn { it.copy(paymentOption = v) }
    fun setPaymentMethod(v: PaymentMethod) = editCheckIn { it.copy(paymentMethod = v) }
    fun setNotes(v: String) = editCheckIn { it.copy(notes = v) }

    private fun editCheckIn(change: (CheckInDialogState) -> CheckInDialogState) {
        val zone = currentZone()
        _checkIn.update { recomputed(change(it), zone) }
    }

    private fun recomputed(st: CheckInDialogState, zone: TimeZone): CheckInDialogState {
        val booking = st.booking
        val at = checkInInstant(st.date, st.time, zone)
        val entitled = if (booking == null || at == null) null else
            ReservationRules.entitledCheckOut(at, ReservationRules.nightsPaid(booking), st.officialTime, zone.asJavaZone())
        return st.copy(entitledCheckOut = entitled)
    }

    private fun checkInInstant(date: LocalDate?, time: LocalTime?, zone: TimeZone): Instant? =
        if (date == null || time == null) null else LocalDateTime(date, time).toInstant(zone)

    // ---- Check-in submit ----

    fun submitCheckIn() {
        // 1. The synchronous guard: a second tap is ignored here, before anything else happens.
        if (!checkInInFlight.compareAndSet(false, true)) return
        val st = _checkIn.value
        val booking = st.booking
        if (booking == null) {
            checkInInFlight.set(false)
            return
        }
        val zone = currentZone()

        val check = validateCheckIn(
            online = network.isOnline(),
            date = st.date,
            time = st.time,
            nights = ReservationRules.nightsPaid(booking),
            officialTime = st.officialTime,
            zone = zone
        )
        if (check is CheckInCheck.Rejected) {
            checkInInFlight.set(false)
            toast.show(check.message, ToastType.Error, check.title)
            return
        }
        check as CheckInCheck.Ok
        val checkInAt = check.checkInAt

        // 2. Build the form and call the service.
        val form = CheckInForm(
            checkInAt = checkInAt,
            idDocumentRef = st.idDocumentRef.trim().ifEmpty { null },
            paymentOption = st.paymentOption,
            paymentMethod = st.paymentMethod,
            notes = st.notes.trim().ifEmpty { null }
        )
        _checkIn.update { it.copy(busy = true, slow = false) }

        val slowNotice = viewModelScope.launch {
            delay(UI_SLOW_NOTICE_DELAY_MS)
            _checkIn.update { it.copy(slow = true) }
        }
        viewModelScope.launch {
            try {
                val result = service.checkIn(booking, form)
                val outcome = result.getOrNull()
                if (outcome != null) {
                    // 3. Success: the success screen replaces the list.
                    _checkIn.update { it.copy(success = outcome, booking = null) }
                    toast.show(checkInDoneMessage(outcome.guestName, outcome.paidNow), ToastType.Success, UI_TITLE_CHECKIN_COMPLETE)
                } else {
                    // 4. Failure.
                    val message = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: UI_MSG_GENERIC
                    toast.show(message, ToastType.Error, UI_TITLE_CHECKIN_FAILED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: UI_MSG_GENERIC, ToastType.Error, UI_TITLE_CHECKIN_FAILED)
            } finally {
                slowNotice.cancel()
                checkInInFlight.set(false)
                _checkIn.update { it.copy(busy = false, slow = false) }
            }
        }
    }
}
