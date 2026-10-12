package com.westly.nbms.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.feature.NavRules
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.dashboard.data.dashZoneOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

private const val GYM_REPORTS_ROUTE = "gym/reports"
private const val CLOCK_TICK_MS = 60_000L

/**
 * The card is only for businesses that have a gym (GYM module on) and for roles that may open `gym/reports` (A.9).
 * Westly always showed it; NBMS never shows an empty gym card.
 */
internal fun gymOverviewVisible(role: Role, modules: Set<ModuleKey>): Boolean =
    ModuleKey.GYM in modules && NavRules.canOpen(GYM_REPORTS_ROUTE, role, modules)

internal sealed interface GymOverviewState {
    data object Loading : GymOverviewState
    data object Error : GymOverviewState
    data class Ready(val summary: GymSummary) : GymOverviewState
}

/** An error in either list is an error; Loading until both lists arrived; otherwise the three figures worked out at [now]. */
internal fun gymOverviewStateOf(
    members: Resource<List<DashGymMember>>,
    visits: Resource<List<DashGymVisit>>,
    now: Instant,
    zone: ZoneId
): GymOverviewState = when {
    members is Resource.Error || visits is Resource.Error -> GymOverviewState.Error
    members is Resource.Success && visits is Resource.Success -> GymOverviewState.Ready(
        DashboardMath.gymSummary(members.data, visits.data, now.atZone(zone).toLocalDate().toString(), now)
    )
    else -> GymOverviewState.Loading
}

/** Reads gym members and visits live and turns them into the three figures of [GymOverviewCard]. */
@HiltViewModel
class GymOverviewViewModel @Inject constructor(
    repository: DashboardRepository,
    session: SessionManager,
    private val navigator: ShellNavigator
) : ViewModel() {

    private val zone: ZoneId = dashZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    /** Ticks every minute so "today" and expiring memberships stay right while the dashboard is open. */
    private val clock: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(CLOCK_TICK_MS)
        }
    }

    /** The "Reports →" link. */
    fun openReports() = navigator.open(GYM_REPORTS_ROUTE)

    internal val state: StateFlow<GymOverviewState> =
        combine(repository.gymMembers(), repository.gymVisits(), clock) { members, visits, now ->
            gymOverviewStateOf(members, visits, now, zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GymOverviewState.Loading)
}
