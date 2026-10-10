package com.westly.nbms.features.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal const val MSG_ATTENDANCE_LOAD_FAILED = "We couldn't load attendance records."
internal const val MSG_ATTENDANCE_EMPTY = "No attendance records found for this range"
internal const val ATTENDANCE_DASH = "—"
private const val CLOCK_TICK_MS = 60_000L

// ---- who may do what ----

private val VIEW_ROLES = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST, Role.OPERATIONS_MANAGER)
private val RECORD_ROLES = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST)

/** Super Admin, Manager, Receptionist and Operations Manager may open the register. */
internal fun attendanceCanView(role: Role): Boolean = role in VIEW_ROLES

/** Only Super Admin and Receptionist see the Record Attendance button. */
internal fun attendanceCanRecord(role: Role): Boolean = role in RECORD_ROLES

// ---- state ----

/** What the filter row has chosen. */
internal data class AttendanceFilters(val search: String, val from: LocalDate, val to: LocalDate)

internal sealed interface AttendanceView {
    data object Loading : AttendanceView
    data class Error(val message: String) : AttendanceView
    /** [groups] is newest day first; empty means "no records in this range". */
    data class Ready(val groups: List<DayGroup>) : AttendanceView
}

internal data class AttendanceUiState(
    val view: AttendanceView,
    val summary: TodaySummary,
    val todayKey: String
)

/** Builds everything the register shows from the two live lists, the filters and today's date key. Pure. */
internal fun buildAttendanceUi(
    attendance: Resource<List<AttendanceRecord>>,
    users: Resource<List<AttendanceUser>>,
    filters: AttendanceFilters,
    todayKey: String,
    zone: ZoneId
): AttendanceUiState {
    val records = (attendance as? Resource.Success)?.data.orEmpty()
    val totalStaff = (users as? Resource.Success)?.data.orEmpty().count { !it.isDeleted }
    val view = when (attendance) {
        is Resource.Loading -> AttendanceView.Loading
        is Resource.Error -> AttendanceView.Error(MSG_ATTENDANCE_LOAD_FAILED)
        is Resource.Success -> AttendanceView.Ready(
            AttendanceRules.groupByDate(
                AttendanceRules.filterRegister(
                    records,
                    from = AttendanceRules.dateKeyOf(filters.from),
                    to = AttendanceRules.dateKeyOf(filters.to),
                    search = filters.search,
                    zone = zone
                ),
                zone
            )
        )
    }
    return AttendanceUiState(view, AttendanceRules.todaySummary(records, todayKey, totalStaff, zone), todayKey)
}

// ---- text helpers for the screen ----

private val DAY_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.ENGLISH)

/** "Monday, Mar 5, 2026". An unreadable key is shown as it is. */
internal fun attendanceDayLabel(dateKey: String): String = try {
    LocalDate.parse(dateKey).format(DAY_LABEL_FORMAT)
} catch (e: Exception) {
    dateKey
}

/** "1 record" / "3 records". */
internal fun attendanceRecordCount(n: Int): String = if (n == 1) "1 record" else "$n records"

/** The role as people read it ("Operations Manager"); an unknown key is shown as it is, a missing one as a dash. */
internal fun attendanceRoleLabel(role: String?): String =
    if (role.isNullOrBlank()) ATTENDANCE_DASH else (Role.fromKey(role)?.label ?: role)

/** A time or note, or a dash when it is empty. */
internal fun attendanceOrDash(value: String?): String = value?.takeIf { it.isNotBlank() } ?: ATTENDANCE_DASH

internal fun attendanceZoneOf(session: SessionManager): ZoneId {
    val signedIn = session.state.value as? SessionState.SignedIn
    return if (signedIn != null) attendanceZoneOf(signedIn.business) else ZoneId.of("Africa/Lagos")
}

/** Ticks now and then once a minute, so "today" moves on at midnight while the screen is open. */
internal fun attendanceMinuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(CLOCK_TICK_MS)
    }
}

// ---- ViewModel ----

/**
 * Behind [AttendanceScreen] (the register): live attendance and staff, the filters, and which day cards the person
 * has opened or closed. The most recent day is open until the person toggles it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AttendanceViewModel internal constructor(
    private val repository: AttendanceRepository,
    private val session: SessionManager,
    private val navigator: ShellNavigator,
    ticker: Flow<Unit>,
    private val clock: () -> Instant
) : ViewModel() {

    @Inject
    constructor(repository: AttendanceRepository, session: SessionManager, navigator: ShellNavigator) :
        this(repository, session, navigator, attendanceMinuteTicker(), { Instant.now() })

    private val zone: ZoneId = attendanceZoneOf(session)

    private fun today(): LocalDate = clock().atZone(zone).toLocalDate()

    private val _filters = MutableStateFlow(
        today().let { AttendanceFilters(search = "", from = it.withDayOfMonth(1), to = it) }
    )
    internal val filters: StateFlow<AttendanceFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val attendanceFlow: Flow<Resource<List<AttendanceRecord>>> = retryTick
        .flatMapLatest { repository.observeAll() }
        .catch { emit(Resource.Error(MSG_ATTENDANCE_LOAD_FAILED, it)) }

    private val usersFlow: Flow<Resource<List<AttendanceUser>>> = retryTick
        .flatMapLatest { repository.observeUsers() }
        .catch { emit(Resource.Error(MSG_ATTENDANCE_LOAD_FAILED, it)) }

    private val todayKeyFlow: Flow<String> = ticker
        .map { AttendanceRules.dateKeyOf(today()) }
        .distinctUntilChanged()

    internal val state: StateFlow<AttendanceUiState> = combine(attendanceFlow, usersFlow, _filters, todayKeyFlow) { a, u, f, t ->
        buildAttendanceUi(a, u, f, t, zone)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AttendanceUiState(AttendanceView.Loading, TodaySummary(0, 0, 0, 0), AttendanceRules.dateKeyOf(today()))
    )

    /** dateKey to open (true) or closed (false), only for the cards the person has toggled. */
    private val _toggled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    internal val toggled: StateFlow<Map<String, Boolean>> = _toggled.asStateFlow()

    fun setSearch(text: String) = _filters.update { it.copy(search = text) }
    internal fun setFrom(date: LocalDate) = _filters.update { it.copy(from = date) }
    internal fun setTo(date: LocalDate) = _filters.update { it.copy(to = date) }

    /** Remembers the person's choice for one day card while the screen is open. */
    fun toggleDay(dateKey: String, currentlyExpanded: Boolean) {
        _toggled.update { it + (dateKey to !currentlyExpanded) }
    }

    /** Opens Record Attendance. Until Phase 28B is installed the shell sends the person to the Dashboard instead. */
    fun openRecord() = navigator.open("attendance/record")

    fun retry() {
        retryTick.update { it + 1 }
    }
}
