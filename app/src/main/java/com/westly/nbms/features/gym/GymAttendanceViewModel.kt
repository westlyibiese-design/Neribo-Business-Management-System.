package com.westly.nbms.features.gym

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import java.time.Instant
import javax.inject.Inject

internal const val MSG_ATTENDANCE_LOAD_FAILED = "We couldn't load attendance history."

/** The two controls above the log. [date] null means "every day" (the Clear date button). */
internal data class AttendanceFilters(val date: LocalDate? = null, val search: String = "")

internal sealed interface AttendanceView {
    data object Loading : AttendanceView
    data class Error(val message: String) : AttendanceView
    data class Ready(val rows: List<GymVisit>) : AttendanceView
}

// ── pure helpers (unit-tested without Android) ──

/** Visits of [dateKey] when it is set, whose member name contains [search] (trimmed, any case); not deleted; newest check-in first. */
internal fun filterVisits(visits: List<GymVisit>, dateKey: String?, search: String): List<GymVisit> {
    val needle = search.trim()
    return visits
        .filter { !it.isDeleted }
        .filter { dateKey == null || it.dateKey == dateKey }
        .filter { needle.isEmpty() || it.memberName.contains(needle, ignoreCase = true) }
        .sortedWith(visitNewestFirst())
}

internal fun attendanceViewOf(resource: Resource<List<GymVisit>>, filters: AttendanceFilters): AttendanceView = when (resource) {
    is Resource.Loading -> AttendanceView.Loading
    is Resource.Error -> AttendanceView.Error(MSG_ATTENDANCE_LOAD_FAILED)
    is Resource.Success -> AttendanceView.Ready(filterVisits(resource.data, filters.date?.toString(), filters.search))
}

/** Reads the visit log live, filtered by day (default today in the business time zone) and member name. */
@HiltViewModel
class GymAttendanceViewModel @Inject constructor(
    private val visits: GymVisitsRepository,
    session: SessionManager
) : ViewModel() {

    private val _filters = MutableStateFlow(
        AttendanceFilters(
            date = LocalDate.parse(
                GymLogic.dateKey(
                    Instant.now(),
                    (session.state.value as? SessionState.SignedIn)?.business?.timezone ?: "Africa/Lagos"
                )
            )
        )
    )
    internal val filters: StateFlow<AttendanceFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<GymVisit>>> = retryTick
        .flatMapLatest { visits.observeVisits() }
        .catch { emit(Resource.Error(MSG_ATTENDANCE_LOAD_FAILED, it)) }

    internal val view: StateFlow<AttendanceView> = combine(live, _filters) { resource, filters ->
        attendanceViewOf(resource, filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendanceView.Loading)

    fun setDate(date: LocalDate?) {
        _filters.update { it.copy(date = date) }
    }

    fun setSearch(text: String) {
        _filters.update { it.copy(search = text) }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }
}
