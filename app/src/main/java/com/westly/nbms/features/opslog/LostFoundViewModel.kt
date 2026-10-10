package com.westly.nbms.features.opslog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
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
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

sealed interface LostFoundView {
    data object Loading : LostFoundView
    data class Error(val message: String) : LostFoundView
    /** All live items, newest found first. The search and status filter are applied by the screen with [filterLostFound]. */
    data class Ready(val items: List<LostFoundItem>) : LostFoundView
}

internal fun lostFoundViewOf(resource: Resource<List<LostFoundItem>>): LostFoundView = when (resource) {
    is Resource.Loading -> LostFoundView.Loading
    is Resource.Error -> LostFoundView.Error(MSG_LOST_FOUND_LOAD_FAILED)
    is Resource.Success -> LostFoundView.Ready(sortLostFound(resource.data))
}

/** The Log Found Item sheet: whether it is open, what was typed, and whether it is saving. */
data class LogFoundUiState(
    val open: Boolean = false,
    val form: LogFoundForm = LogFoundForm(),
    val saving: Boolean = false,
    val descriptionError: String? = null
)

/** The open detail sheet: which item, the typed note, and the status being saved (null = nothing in flight). */
data class DetailUiState(
    val itemId: String,
    val note: String = "",
    val updating: ItemStatus? = null
)

@HiltViewModel
class LostFoundViewModel @Inject internal constructor(
    private val repository: LostFoundRepository,
    private val session: SessionManager,
    private val toast: ToastController,
    imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
) : ViewModel() {

    /** The first photo field the app offers, or null (the form then shows a plain "Photo URL (optional)" field). */
    val imageProvider: ImageFieldProvider? = imageProviders.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<LostFoundItem>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_LOST_FOUND_LOAD_FAILED, it)) }

    val view: StateFlow<LostFoundView> = live
        .map { lostFoundViewOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LostFoundView.Loading)

    /** Rooms for the Room dropdown. If they cannot be loaded the list is empty and the person types the room number. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rooms: StateFlow<List<LostFoundRoomOption>> = retryTick
        .flatMapLatest { repository.observeRooms() }
        .map { (it as? Resource.Success)?.data ?: emptyList() }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null = All Status. */
    private val _statusFilter = MutableStateFlow<ItemStatus?>(null)
    val statusFilter: StateFlow<ItemStatus?> = _statusFilter.asStateFlow()

    private val _log = MutableStateFlow(LogFoundUiState())
    val log: StateFlow<LogFoundUiState> = _log.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail: StateFlow<DetailUiState?> = _detail.asStateFlow()

    /** True while the "Ending session for security…" screen is up (shared-device PIN sessions only). */
    private val _pinEnding = MutableStateFlow(false)
    val pinEnding: StateFlow<Boolean> = _pinEnding.asStateFlow()

    /** Set before any suspend call and cleared in `finally`: a second tap on Log Item does nothing. */
    private val createInFlight = AtomicBoolean(false)
    private val statusInFlight = AtomicBoolean(false)
    private val signOutScheduled = AtomicBoolean(false)

    fun retry() = retryTick.update { it + 1 }

    fun setQuery(text: String) { _query.value = text }

    fun setStatusFilter(status: ItemStatus?) { _statusFilter.value = status }

    // ── Log Found Item sheet ──

    /** Opens the sheet with the date and time set to now (business time zone) and Found By set to the signed-in name. */
    fun openLogSheet() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!lostFoundCanCreate(signedIn.user.role)) return
        val local = Clock.System.now().toLocalDateTime(lostFoundZone(signedIn.business.timezone))
        _log.value = LogFoundUiState(
            open = true,
            form = LogFoundForm(
                foundDate = local.date,
                foundTime = LocalTime(local.hour, local.minute),
                foundBy = signedIn.user.name
            )
        )
    }

    fun closeLogSheet() {
        if (_log.value.saving) return
        _log.value = LogFoundUiState()
    }

    fun updateForm(change: (LogFoundForm) -> LogFoundForm) = _log.update { state ->
        val form = change(state.form)
        state.copy(form = form, descriptionError = if (form.description.isNotBlank()) null else state.descriptionError)
    }

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val state = _log.value
        if (state.saving) return

        val input = when (val check = validateLogFound(state.form, rooms.value, signedIn.business.timezone)) {
            is LogFoundCheck.Ready -> check.input
            is LogFoundCheck.Toast -> {
                toast.show(check.message, ToastType.Error, check.title)
                return
            }
            is LogFoundCheck.DescriptionMissing -> {
                _log.update { it.copy(descriptionError = MSG_DESCRIPTION_REQUIRED) }
                return
            }
        }
        if (!createInFlight.compareAndSet(false, true)) return
        _log.update { it.copy(saving = true, descriptionError = null) }
        val usesPin = signedIn.user.usesPin

        viewModelScope.launch {
            try {
                repository.create(input)
                toast.show("${input.itemName} recorded for Room ${input.roomNumber}.", ToastType.Success, "Item Logged")
                _log.value = LogFoundUiState() // close and reset the form
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LOST_FOUND_GENERIC, ToastType.Error, "Error")
                _log.update { it.copy(saving = false) }
            } finally {
                createInFlight.set(false)
            }
        }
    }

    // ── detail sheet ──

    fun openDetail(item: LostFoundItem) { _detail.value = DetailUiState(itemId = item.id) }

    fun closeDetail() { _detail.value = null }

    fun setNote(text: String) = _detail.update { it?.copy(note = text) }

    /** `Mark {Status}` in the detail sheet (Super Admin and Manager only). */
    fun markStatus(item: LostFoundItem, target: ItemStatus) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!lostFoundCanManageStatus(signedIn.user.role)) return
        val current = _detail.value ?: return
        if (current.updating != null || target == item.status) return
        if (!statusInFlight.compareAndSet(false, true)) return
        _detail.update { it?.copy(updating = target) }
        val note = current.note

        viewModelScope.launch {
            try {
                repository.changeStatus(item, target, note)
                toast.show("${item.itemName} marked as ${target.label}.", ToastType.Success, "Status Updated")
                _detail.update { it?.copy(note = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_LOST_FOUND_GENERIC, ToastType.Error, "Error")
            } finally {
                _detail.update { it?.copy(updating = null) }
                statusInFlight.set(false)
            }
        }
    }

    // ── shared-device sign-out ──

    /** Shared-device (PIN) sessions end by themselves 2.5 s after an item is logged. Status changes do not end the session. */
    private fun scheduleSignOut() {
        if (!signOutScheduled.compareAndSet(false, true)) return
        _pinEnding.value = true
        viewModelScope.launch {
            delay(LOST_FOUND_PIN_SIGN_OUT_DELAY_MS)
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
