package com.westly.nbms.features.gym

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject

internal const val MSG_REPORTS_LOAD_FAILED = "We couldn't load gym report data."

private const val REPORTS_CLOCK_TICK_MS = 60_000L

internal sealed interface ReportsView {
    data object Loading : ReportsView
    data class Error(val message: String) : ReportsView
    data class Ready(val data: GymReportData) : ReportsView
}

/** Loading until both lists arrive; an error in either one is the page error; otherwise the report worked out at [now]. */
internal fun reportsViewOf(
    members: Resource<List<GymMember>>,
    visits: Resource<List<GymVisit>>,
    now: Instant,
    timezone: String
): ReportsView = when {
    members is Resource.Error || visits is Resource.Error -> ReportsView.Error(MSG_REPORTS_LOAD_FAILED)
    members is Resource.Success && visits is Resource.Success ->
        ReportsView.Ready(GymReportsLogic.reportOf(members.data, visits.data, now, timezone))
    else -> ReportsView.Loading
}

/** Reads the members and the visit log live and turns them into the Reports page. */
@HiltViewModel
class GymReportsViewModel @Inject constructor(
    private val members: GymMembersRepository,
    private val visits: GymVisitsRepository,
    session: SessionManager
) : ViewModel() {

    private val timezone: String = (session.state.value as? SessionState.SignedIn)?.business?.timezone ?: "Africa/Lagos"

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveMembers: Flow<Resource<List<GymMember>>> = retryTick
        .flatMapLatest { members.observe() }
        .catch { emit(Resource.Error(MSG_REPORTS_LOAD_FAILED, it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveVisits: Flow<Resource<List<GymVisit>>> = retryTick
        .flatMapLatest { visits.observeVisits() }
        .catch { emit(Resource.Error(MSG_REPORTS_LOAD_FAILED, it)) }

    /** Ticks every minute so "expiring" and "this month" stay right while the page is open. */
    private val clock: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(REPORTS_CLOCK_TICK_MS)
        }
    }

    internal val view: StateFlow<ReportsView> = combine(liveMembers, liveVisits, clock) { m, v, now ->
        reportsViewOf(m, v, now, timezone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsView.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }
}
