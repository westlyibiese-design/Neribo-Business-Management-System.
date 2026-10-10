package com.westly.nbms.features.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

internal const val SHIFT_SAVE_TIMEOUT_MS = 20_000L
private const val CLOCK_TICK_MS = 60_000L

// ---- Staff source (the `users` mirror) ------------------------------------------------------------

/** A `users` mirror document, only the fields this screen needs. */
data class ShiftUserDoc(
    @DocumentId val id: String = "",
    val name: String = "",
    val role: String = "",
    val status: String = "active",
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** Live active staff of one role. The real one reads Firestore; the unit tests use a fake. */
internal interface ShiftStaffSource {
    fun observe(role: Role): Flow<Resource<List<ShiftStaff>>>
}

/** Active, not-deleted accounts only, A to Z by name. */
internal fun activeShiftStaff(docs: List<ShiftUserDoc>): List<ShiftStaff> =
    docs.filter { it.status == "active" && !it.isDeleted }
        .map { ShiftStaff(id = it.id, name = it.name) }
        .sortedBy { it.name.lowercase() }

internal class FirestoreShiftStaffSource(private val firestore: BusinessFirestore) : ShiftStaffSource {
    override fun observe(role: Role): Flow<Resource<List<ShiftStaff>>> =
        firestore.observeList("users", ShiftUserDoc::class.java) { it.whereEqualTo("role", role.key) }.map { r ->
            when (r) {
                is Resource.Success -> Resource.Success(activeShiftStaff(r.data))
                is Resource.Error -> Resource.Error(r.message, r.cause)
                is Resource.Loading -> Resource.Loading
            }
        }
}

// ---- State ------------------------------------------------------------------------------------------

/** What the controls row has chosen. */
internal data class ShiftSelection(val role: Role, val mode: ShiftViewMode, val anchor: LocalDate)

internal sealed interface ShiftsView {
    data object Loading : ShiftsView
    data class Error(val message: String) : ShiftsView
    data class Ready(val board: ShiftBoard) : ShiftsView
}

internal fun shiftZoneOf(session: SessionManager): ZoneId {
    val name = (session.state.value as? SessionState.SignedIn)?.business?.timezone
    return try {
        ZoneId.of(name ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }
}

/** Ticks once now and then once a minute, so "On Duty Right Now" moves with the clock. */
internal fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(CLOCK_TICK_MS)
    }
}

// ---- ViewModel --------------------------------------------------------------------------------------

/**
 * Behind [ShiftSchedulingScreen] and [ShiftFormSheet]: role / range choice, the live calendar, saving, cancelling and
 * the toasts. The repository does the writes, the audit entry and the notification; this class shows the toasts and
 * holds the saving state. Conflicts are not toasts: they go to [conflicts] for the red box.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShiftSchedulingViewModel internal constructor(
    private val repository: ShiftsRepository,
    private val staffSource: ShiftStaffSource,
    private val toast: ToastController,
    private val session: SessionManager,
    private val ticker: Flow<Unit>,
    private val clock: () -> LocalDateTime
) : ViewModel() {

    @Inject
    constructor(
        firestore: BusinessFirestore,
        repository: ShiftsRepository,
        toast: ToastController,
        session: SessionManager
    ) : this(
        repository,
        FirestoreShiftStaffSource(firestore),
        toast,
        session,
        minuteTicker(),
        { LocalDateTime.now(shiftZoneOf(session)) }
    )

    private val _selection = MutableStateFlow(ShiftSelection(DEFAULT_SHIFT_ROLE, ShiftViewMode.WEEK, clock().toLocalDate()))
    internal val selection: StateFlow<ShiftSelection> = _selection.asStateFlow()

    private val _reload = MutableStateFlow(0)

    /** The calendar for the current choice. Shows Loading again each time the role or range changes. */
    internal val view: StateFlow<ShiftsView> = combine(_selection, _reload) { sel, _ -> sel }
        .flatMapLatest { sel ->
            val (from, to) = ShiftLogic.viewRange(sel.mode, sel.anchor)
            // One extra day before the range brings in yesterday's overnight shifts.
            combine(repository.observe(sel.role, from.minusDays(1), to), staffSource.observe(sel.role), ticker) { shifts, staff, _ ->
                when {
                    shifts is Resource.Error || staff is Resource.Error -> ShiftsView.Error(MSG_SHIFT_LOAD_ERROR)
                    shifts is Resource.Success && staff is Resource.Success ->
                        ShiftsView.Ready(buildShiftBoard(sel.role, sel.mode, sel.anchor, shifts.data, staff.data, clock()))
                    else -> ShiftsView.Loading
                }
            }
                .onStart { emit(ShiftsView.Loading) }
                .catch { emit(ShiftsView.Error(MSG_SHIFT_LOAD_ERROR)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftsView.Loading)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _conflicts = MutableStateFlow<ConflictBox?>(null)
    internal val conflicts: StateFlow<ConflictBox?> = _conflicts.asStateFlow()

    private val _seriesOfferFor = MutableStateFlow<String?>(null)

    /** The id of the shift whose series has more than one shift (so "Cancel Series" is offered), or null. */
    val seriesOfferFor: StateFlow<String?> = _seriesOfferFor.asStateFlow()

    /** Today in the business time zone. */
    fun today(): LocalDate = clock().toLocalDate()

    // ---- controls ----

    fun setRole(role: Role) { _selection.value = _selection.value.copy(role = role) }
    fun setMode(mode: ShiftViewMode) { _selection.value = _selection.value.copy(mode = mode) }
    fun previous() { _selection.value = _selection.value.let { it.copy(anchor = ShiftLogic.step(it.mode, it.anchor, -1)) } }
    fun next() { _selection.value = _selection.value.let { it.copy(anchor = ShiftLogic.step(it.mode, it.anchor, 1)) } }
    fun goToToday() { _selection.value = _selection.value.copy(anchor = today()) }
    fun retry() { _reload.value += 1 }

    // ---- form helpers ----

    fun clearConflicts() { _conflicts.value = null }

    /** Finds out whether [shift] belongs to a series of more than one shift. Failures just mean no "Cancel Series". */
    fun checkSeries(shift: Shift) {
        _seriesOfferFor.value = null
        val seriesId = shift.seriesId ?: return
        viewModelScope.launch {
            try {
                if (repository.seriesShifts(seriesId).size > 1) _seriesOfferFor.value = shift.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _seriesOfferFor.value = null
            }
        }
    }

    fun clearSeriesOffer() { _seriesOfferFor.value = null }

    // ---- saving ----

    /** Schedules a new shift (or a repeating series). [onDone] runs only after a successful save. */
    internal fun submitNew(form: ShiftFormState, role: Role, staff: List<ShiftStaff>, onDone: () -> Unit) {
        if (_saving.value) return
        val person = resolveShiftStaff(form, staff)
        val issue = validateShiftForm(form, person != null, isEdit = false)
        if (issue != null || person == null) {
            showIssue((issue ?: ShiftFormIssue.NO_STAFF))
            return
        }
        _conflicts.value = null
        execute(onDone) { actor ->
            when (val result = repository.create(form.toInput(role, person), actor)) {
                is ShiftWriteResult.Success -> {
                    show(scheduledToast(result.count))
                    true
                }
                is ShiftWriteResult.Conflicts -> {
                    _conflicts.value = conflictBox(person.name, result.conflicts)
                    false
                }
            }
        }
    }

    /** Saves changes to one shift. [staff] must include the shift's current person. */
    internal fun submitEdit(shift: Shift, form: ShiftFormState, staff: List<ShiftStaff>, onDone: () -> Unit) {
        if (_saving.value) return
        val person = resolveShiftStaff(form, staff)
        val issue = validateShiftForm(form, person != null, isEdit = true)
        if (issue != null || person == null) {
            showIssue((issue ?: ShiftFormIssue.NO_STAFF))
            return
        }
        _conflicts.value = null
        execute(onDone) { actor ->
            when (val result = repository.updateInstance(shift, form.toUpdate(person), actor)) {
                is ShiftWriteResult.Success -> {
                    show(UPDATED_TOAST)
                    true
                }
                is ShiftWriteResult.Conflicts -> {
                    _conflicts.value = conflictBox(person.name, result.conflicts)
                    false
                }
            }
        }
    }

    fun cancelThisShift(shift: Shift, onDone: () -> Unit) {
        if (_saving.value) return
        execute(onDone) { actor ->
            repository.cancelInstance(shift, actor)
            show(CANCELLED_TOAST)
            true
        }
    }

    /** Cancels this shift and every later one of its series. */
    fun cancelSeries(shift: Shift, onDone: () -> Unit) {
        if (_saving.value) return
        execute(onDone) { actor ->
            val seriesId = shift.seriesId ?: throw IllegalStateException("This shift is not part of a series.")
            val fromDate = shift.date.toLocalDateOrNull() ?: throw IllegalStateException(MSG_SHIFT_BAD_DATE)
            val series = repository.seriesShifts(seriesId)
            repository.cancelSeries(series, fromDate, actor)
            show(SERIES_CANCELLED_TOAST)
            true
        }
    }

    /**
     * Runs [block] with the saving flag on. When it returns true the sheet is closed through [onDone]; false (a conflict)
     * leaves it open. Any failure becomes the "Error" toast.
     */
    private fun execute(onDone: () -> Unit, block: suspend (ShiftActor) -> Boolean) {
        val me = (session.state.value as? SessionState.SignedIn)?.user
        if (me == null) {
            toast.show(MSG_SHIFT_NOT_SIGNED_IN, ToastType.Error, "Error")
            return
        }
        val actor = ShiftActor(me.uid, me.name)
        _saving.value = true
        viewModelScope.launch {
            try {
                val close = withTimeout(SHIFT_SAVE_TIMEOUT_MS) { block(actor) }
                if (close) onDone()
            } catch (e: TimeoutCancellationException) {
                toast.show(MSG_SHIFT_TIMEOUT, ToastType.Error, "Error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_SHIFT_GENERIC, ToastType.Error, "Error")
            } finally {
                _saving.value = false
            }
        }
    }

    private fun showIssue(issue: ShiftFormIssue) {
        val t = issue.toToast()
        toast.show(t.message, ToastType.Error, t.title)
    }

    private fun show(t: ShiftToastText) {
        toast.show(t.message, ToastType.Success, t.title)
    }
}
