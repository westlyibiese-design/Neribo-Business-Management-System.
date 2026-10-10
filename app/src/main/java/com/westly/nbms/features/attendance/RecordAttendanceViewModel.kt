package com.westly.nbms.features.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// ---- texts (exactly as the Westly page words them) ----

internal const val MSG_RECORD_LOAD_FAILED =
    "Staff or attendance data failed to load. Reload before saving, or existing records may be overwritten."
internal const val TOAST_CANT_SAVE_TITLE = "Can't save yet"
internal const val TOAST_CANT_SAVE_MESSAGE =
    "Staff or attendance data failed to load. Reload the page before saving to avoid overwriting existing records."
internal const val TOAST_RECORDED_TITLE = "Attendance Recorded"
internal const val TOAST_UPDATED_TITLE = "Attendance Updated"
internal const val TOAST_ERROR_TITLE = "Error"
internal const val MSG_RECORD_SAVE_FAILED = "We couldn't save this attendance record. Please try again."

/** How long the "Ending session for security…" screen stays up before a shared-device (PIN) session signs out. */
internal const val RECORD_PIN_SIGN_OUT_DELAY_MS = 2_500L

/** How long the green check stays on a row after it was saved. */
internal const val RECORD_SAVED_TICK_MS = 2_000L

// ---- pure helpers ----

private val TOAST_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** "Mar 5, 2026". An unreadable key is shown as it is. */
internal fun attendanceToastDay(dateKey: String): String = try {
    LocalDate.parse(dateKey).format(TOAST_DAY_FORMAT)
} catch (e: Exception) {
    dateKey
}

/** The toast text under "Attendance Recorded" / "Attendance Updated": "Ada — Mar 5, 2026". */
internal fun attendanceToastDescription(staffName: String, dateKey: String): String =
    "$staffName — ${attendanceToastDay(dateKey)}"

/** Staff whose name contains [search] (ignoring case and surrounding spaces). A blank search keeps everyone. */
internal fun filterRecordStaff(staff: List<AttendanceUser>, search: String): List<AttendanceUser> {
    val needle = search.trim().lowercase()
    return if (needle.isEmpty()) staff else staff.filter { it.name.lowercase().contains(needle) }
}

/** What the audit log keeps as the "before" of a save: null for a brand-new record. */
internal fun attendanceAuditOld(existing: AttendanceRecord?): Map<String, Any?>? = existing?.let {
    mapOf("status" to it.status, "clockIn" to it.clockIn, "clockOut" to it.clockOut)
}

/** What the audit log keeps as the "after" of a save (blank times are stored as null, so they are logged as null). */
internal fun attendanceAuditNew(row: AttendanceRowState, dateKey: String): Map<String, Any?> = mapOf(
    "status" to row.status.key,
    "clockIn" to row.clockIn.trim().ifBlank { null },
    "clockOut" to row.clockOut.trim().ifBlank { null },
    "date" to dateKey
)

/** "08:05" as a time of day, or null when the text is empty or not a time. */
internal fun attendanceParseTime(text: String): LocalTime? =
    if (text.isBlank()) null else try {
        LocalTime.parse(text.trim())
    } catch (e: Exception) {
        null
    }

/** A time of day as "HH:mm". */
internal fun attendanceFormatTime(time: LocalTime): String = String.format(Locale.US, "%02d:%02d", time.hour, time.minute)

// ---- what the screen shows ----

/** One staff member's row. [row] already mixes the saved record with what the person has typed. */
internal data class RecordRowUi(
    val staff: AttendanceUser,
    /** The saved record of the chosen day; a green check shows next to the name when it is not null. */
    val existing: AttendanceRecord?,
    val row: AttendanceRowState,
    /** True when the row is in the editable map (seeded from a record, or edited): Save All Edited Rows saves it. */
    val touched: Boolean,
    val saving: Boolean,
    val justSaved: Boolean
)

internal data class RecordUiState(
    val date: LocalDate,
    val dateKey: String,
    val search: String,
    val loading: Boolean,
    /** Staff or attendance failed to load: show the error box and refuse to save. */
    val loadFailed: Boolean,
    val rows: List<RecordRowUi>,
    val savingAll: Boolean,
    /** How many active staff there are, before the search is applied. */
    val activeStaffCount: Int
)

/** Builds everything the Record Attendance page shows from the two live lists and the person's edits. Pure. */
internal fun buildRecordUi(
    attendance: Resource<List<AttendanceRecord>>,
    users: Resource<List<AttendanceUser>>,
    date: LocalDate,
    search: String,
    edited: Map<String, AttendanceRowState>,
    saving: Set<String>,
    justSaved: Set<String>,
    savingAll: Boolean,
    zone: ZoneId
): RecordUiState {
    val dateKey = AttendanceRules.dateKeyOf(date)
    val failed = attendance is Resource.Error || users is Resource.Error
    val loading = !failed && (attendance is Resource.Loading || users is Resource.Loading)
    val records = (attendance as? Resource.Success)?.data.orEmpty()
    val allStaff = AttendanceRules.activeStaff((users as? Resource.Success)?.data.orEmpty())
    val existing = AttendanceRules.existingForDate(records, dateKey, zone)
    val rows = filterRecordStaff(allStaff, search).map { person ->
        val saved = existing[person.id]
        RecordRowUi(
            staff = person,
            existing = saved,
            row = AttendanceRules.effectiveRow(saved, edited[person.id]),
            touched = person.id in edited,
            saving = person.id in saving,
            justSaved = person.id in justSaved
        )
    }
    return RecordUiState(
        date = date,
        dateKey = dateKey,
        search = search,
        loading = loading,
        loadFailed = failed,
        rows = rows,
        savingAll = savingAll,
        activeStaffCount = allStaff.size
    )
}

// ---- ViewModel ----

/**
 * Behind [RecordAttendanceScreen]: the live staff and attendance lists, the chosen day, the editable rows and every
 * save. Attendance is one record per staff member per day, so a check-in in the morning and a check-out in the evening
 * update the same document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordAttendanceViewModel internal constructor(
    private val repository: AttendanceRepository,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val toast: ToastController,
    private val navigator: ShellNavigator,
    private val clock: () -> Instant,
    private val signOutDelayMs: Long
) : ViewModel() {

    @Inject
    constructor(
        repository: AttendanceRepository,
        session: SessionManager,
        audit: AuditLogger,
        toast: ToastController,
        navigator: ShellNavigator
    ) : this(repository, session, audit, toast, navigator, { Instant.now() }, RECORD_PIN_SIGN_OUT_DELAY_MS)

    private val zone: ZoneId = attendanceZoneOf(session)

    private fun today(): LocalDate = clock().atZone(zone).toLocalDate()

    // The order of these properties matters: the data pipeline below starts at once and uses them.

    private val _date = MutableStateFlow(today())
    private val _search = MutableStateFlow("")

    /** The editable rows: only staff who were seeded from a record or edited by hand. */
    private val _rows = MutableStateFlow<Map<String, AttendanceRowState>>(emptyMap())
    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    private val _justSaved = MutableStateFlow<Set<String>>(emptySet())
    private val _savingAll = MutableStateFlow(false)
    private val _pinEnding = MutableStateFlow(false)

    /** True while the "Ending session for security…" screen is up (shared-device PIN sessions only). */
    internal val pinEnding: StateFlow<Boolean> = _pinEnding.asStateFlow()

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val savedTickJobs = HashMap<String, Job>()
    private val savingAllNow = AtomicBoolean(false)
    private val signOutScheduled = AtomicBoolean(false)

    private var lastDateKey: String? = null
    private var lastLoaded = false

    private val retryTick = MutableStateFlow(0)

    private val attendanceFlow: Flow<Resource<List<AttendanceRecord>>> = retryTick.flatMapLatest {
        repository.observeAll().catch { emit(Resource.Error(MSG_RECORD_LOAD_FAILED, it)) }
    }

    private val usersFlow: Flow<Resource<List<AttendanceUser>>> = retryTick.flatMapLatest {
        repository.observeUsers().catch { emit(Resource.Error(MSG_RECORD_LOAD_FAILED, it)) }
    }

    private data class Snapshot(
        val attendance: Resource<List<AttendanceRecord>>,
        val users: Resource<List<AttendanceUser>>,
        val date: LocalDate
    )

    /** The latest lists and the chosen day. Runs from the start so a save always sees the newest data. */
    private val snapshot: StateFlow<Snapshot> = combine(attendanceFlow, usersFlow, _date) { a, u, d -> Snapshot(a, u, d) }
        .onEach { reseedIfNeeded(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot(Resource.Loading, Resource.Loading, _date.value))

    internal val ui: StateFlow<RecordUiState> = combine(
        snapshot,
        _search,
        _rows,
        combine(_saving, _justSaved, _savingAll) { saving, saved, all -> Triple(saving, saved, all) }
    ) { snap, search, rows, flags ->
        buildRecordUi(snap.attendance, snap.users, snap.date, search, rows, flags.first, flags.second, flags.third, zone)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildRecordUi(
            Resource.Loading, Resource.Loading, _date.value, "", emptyMap(), emptySet(), emptySet(), false, zone
        )
    )

    /**
     * The rows are re-seeded from the saved records only when the day changed or the data has just finished loading,
     * never on every realtime update (that would wipe what the person is typing).
     */
    private fun reseedIfNeeded(snap: Snapshot) {
        val dateKey = AttendanceRules.dateKeyOf(snap.date)
        val users = (snap.users as? Resource.Success)?.data
        val records = (snap.attendance as? Resource.Success)?.data
        val loadedNow = users != null && records != null
        if (AttendanceRules.shouldReseed(lastDateKey, dateKey, lastLoaded, loadedNow)) {
            _rows.value = if (users != null && records != null) {
                AttendanceRules.seedRows(
                    AttendanceRules.activeStaff(users),
                    AttendanceRules.existingForDate(records, dateKey, zone)
                )
            } else {
                emptyMap()
            }
        }
        lastDateKey = dateKey
        lastLoaded = loadedNow
    }

    // ---- what the person can change ----

    fun setSearch(text: String) = _search.update { text }

    /** Picks another day. The rows are cleared first, then the new day's records seed them again. */
    fun setDate(date: LocalDate) {
        if (date == _date.value) return
        _rows.value = emptyMap()
        _justSaved.value = emptySet()
        _date.value = date
    }

    fun setStatus(staffId: String, status: AttendanceStatus) = editRow(staffId) { it.copy(status = status) }
    fun setClockIn(staffId: String, value: String) = editRow(staffId) { it.copy(clockIn = value) }
    fun setClockOut(staffId: String, value: String) = editRow(staffId) { it.copy(clockOut = value) }
    fun setNotes(staffId: String, value: String) = editRow(staffId) { it.copy(notes = value) }

    private fun editRow(staffId: String, change: (AttendanceRowState) -> AttendanceRowState) {
        val snap = snapshot.value
        val records = (snap.attendance as? Resource.Success)?.data.orEmpty()
        val existing = AttendanceRules.existingForDate(records, AttendanceRules.dateKeyOf(snap.date), zone)[staffId]
        _rows.update { current -> current + (staffId to change(AttendanceRules.effectiveRow(existing, current[staffId]))) }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** The footer button: opens the Attendance Register. */
    fun openRegister() = navigator.open("attendance")

    // ---- saving ----

    /** The Save button of one row. */
    fun save(staffId: String) {
        viewModelScope.launch { saveRow(staffId) }
    }

    /** "Check in now": saves the row with the current business time as check-in, keeping its status. */
    fun checkInNow(staffId: String) = clockNow(staffId) { AttendanceRowOverrides(clockIn = it) }

    /** "Check out now": saves the row with the current business time as check-out. */
    fun checkOutNow(staffId: String) = clockNow(staffId) { AttendanceRowOverrides(clockOut = it) }

    private fun clockNow(staffId: String, overrides: (String) -> AttendanceRowOverrides) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val usesPin = signedIn.user.usesPin
        viewModelScope.launch {
            val now = AttendanceRules.nowHHmm(clock(), zone)
            // A shared-device (PIN) session ends only after a check-in or check-out that really saved.
            if (saveRow(staffId, overrides(now)) && usesPin) scheduleSignOut()
        }
    }

    /** The Save All Edited Rows button. */
    fun saveAllEdited() {
        viewModelScope.launch { saveAllEditedRows() }
    }

    /**
     * Saves, one after another, every row that the search leaves on screen and that has been touched. Each row gives
     * its own toast. A second tap while this runs does nothing.
     */
    internal suspend fun saveAllEditedRows() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!attendanceCanRecord(signedIn.user.role)) return
        if (!savingAllNow.compareAndSet(false, true)) return
        _savingAll.value = true
        try {
            val snap = snapshot.value
            if (!AttendanceRules.canSave(snap.users is Resource.Error, snap.attendance is Resource.Error)) {
                toast.show(TOAST_CANT_SAVE_MESSAGE, ToastType.Error, TOAST_CANT_SAVE_TITLE)
                return
            }
            val users = (snap.users as? Resource.Success)?.data ?: return
            val touched = _rows.value
            val ids = filterRecordStaff(AttendanceRules.activeStaff(users), _search.value)
                .filter { it.id in touched }
                .map { it.id }
            for (id in ids) saveRow(id)
        } finally {
            _savingAll.value = false
            savingAllNow.set(false)
        }
    }

    /**
     * Saves one row: the saved record, then what the person typed, then [overrides] on top. Merge-writes to
     * `{staffId}__{dateKey}`, audits, updates the row, flashes the tick and toasts. Returns false when nothing was saved
     * (load error, a save already running for this person, or a failure).
     */
    internal suspend fun saveRow(staffId: String, overrides: AttendanceRowOverrides? = null): Boolean {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return false
        if (!attendanceCanRecord(signedIn.user.role)) return false

        val snap = snapshot.value
        if (!AttendanceRules.canSave(snap.users is Resource.Error, snap.attendance is Resource.Error)) {
            toast.show(TOAST_CANT_SAVE_MESSAGE, ToastType.Error, TOAST_CANT_SAVE_TITLE)
            return false
        }
        // Still loading: we don't know yet whether a record exists, so saving could overwrite it.
        val users = (snap.users as? Resource.Success)?.data ?: return false
        val records = (snap.attendance as? Resource.Success)?.data ?: return false
        val staff = AttendanceRules.activeStaff(users).firstOrNull { it.id == staffId } ?: return false

        // One save at a time per person: a second tap on the same row is ignored.
        if (!inFlight.add(staffId)) return false
        _saving.update { it + staffId }
        try {
            val dateKey = AttendanceRules.dateKeyOf(snap.date)
            val existing = AttendanceRules.existingForDate(records, dateKey, zone)[staffId]
            val row = AttendanceRules.effectiveRow(existing, _rows.value[staffId], overrides)

            repository.save(row, staff, dateKey, existing)

            try {
                audit.log(
                    action = AttendanceRules.auditAction(existing),
                    collection = ATTENDANCE_COLLECTION,
                    documentId = AttendanceRules.attendanceDocId(staffId, dateKey),
                    previousValue = attendanceAuditOld(existing),
                    newValue = attendanceAuditNew(row, dateKey)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The record is already saved; a failed audit entry must not turn that into an error.
            }

            // The person may have picked another day while this saved: only the shown day's rows are touched.
            if (_date.value == snap.date) {
                _rows.update { it + (staffId to row) }
                flashSaved(staffId)
            }
            toast.show(
                attendanceToastDescription(staff.name, dateKey),
                ToastType.Success,
                if (existing == null) TOAST_RECORDED_TITLE else TOAST_UPDATED_TITLE
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_RECORD_SAVE_FAILED, ToastType.Error, TOAST_ERROR_TITLE)
            return false
        } finally {
            inFlight.remove(staffId)
            _saving.update { it - staffId }
        }
    }

    /** Shows the green check on [staffId]'s row for two seconds. */
    private fun flashSaved(staffId: String) {
        _justSaved.update { it + staffId }
        savedTickJobs[staffId]?.cancel()
        savedTickJobs[staffId] = viewModelScope.launch {
            delay(RECORD_SAVED_TICK_MS)
            _justSaved.update { it - staffId }
        }
    }

    // ---- shared-device sign-out ----

    /** Shared-device (PIN) sessions end by themselves 2.5 s after a successful check-in or check-out. */
    private fun scheduleSignOut() {
        if (!signOutScheduled.compareAndSet(false, true)) return
        _pinEnding.value = true
        viewModelScope.launch {
            delay(signOutDelayMs)
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
