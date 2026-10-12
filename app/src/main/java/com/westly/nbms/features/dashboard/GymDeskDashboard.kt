package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import com.westly.nbms.features.dashboard.data.DashboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal const val GYM_DESK_ROUTE_CHECKIN = "gym/checkin"
internal const val GYM_DESK_ROUTE_MEMBERS = "gym/members"
internal const val GYM_DESK_ROUTE_ATTENDANCE = "gym/attendance"
internal const val GYM_DESK_ROUTE_REPORTS = "gym/reports"
internal const val MSG_GYM_LOAD_FAILED = "We couldn't load gym data."

/** Largest number of rows the "Memberships Expiring Soon" card shows. */
internal const val GYM_EXPIRING_CARD_COUNT = 5

/** The two live lists the Gym Desk reads. The desk is desk-wide: every member and every visit of the business. */
internal class GymDeskFlows(
    val members: Flow<Resource<List<DashGymMember>>>,
    val visits: Flow<Resource<List<DashGymVisit>>>
)

/** Everything the Gym Desk screen shows. [expiring] holds at most [GYM_EXPIRING_CARD_COUNT] rows, soonest first. */
internal data class GymDeskState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val active: Int = 0,
    val inGymNow: Int = 0,
    val visitsToday: Int = 0,
    val expiringCount: Int = 0,
    val expiring: List<ExpiringMember> = emptyList()
) {
    /** True when more members are expiring than the card lists, so the "View all →" link is shown. */
    val hasMoreExpiring: Boolean get() = expiringCount > GYM_EXPIRING_CARD_COUNT
}

/** Works out the Gym Desk numbers. Pure: all numbers come from [FrontlineMath]; "today" is the business-zone day. */
internal fun gymDeskStateOf(
    members: Resource<List<DashGymMember>>,
    visits: Resource<List<DashGymVisit>>,
    now: Instant,
    zone: ZoneId
): GymDeskState {
    val failed = anyFailed(members, visits)
    val loading = !failed && anyLoading(members, visits)
    val todayKey = now.atZone(zone).toLocalDate().toString()
    val stats = FrontlineMath.gymDeskStats(members.rowsOrEmpty(), visits.rowsOrEmpty(), todayKey, now)
    return GymDeskState(
        loading = loading,
        failed = failed,
        now = now,
        active = stats.active,
        inGymNow = stats.inGymNow,
        visitsToday = stats.visitsToday,
        expiringCount = stats.expiring.size,
        expiring = stats.expiring.take(GYM_EXPIRING_CARD_COUNT)
    )
}

/** Gym Desk data: desk-wide, so there is no per-person filter. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class GymDeskDashboardViewModel internal constructor(
    private val flows: () -> GymDeskFlows,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(
        repository: DashboardRepository,
        session: SessionManager,
        navigator: ShellNavigator
    ) : this(
        flows = { GymDeskFlows(repository.gymMembers(), repository.gymVisits()) },
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<GymDeskState> = attempt
        .flatMapLatest {
            val f = flows()
            combine(f.members, f.visits, clock) { members, visits, now -> gymDeskStateOf(members, visits, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GymDeskState())

    /** Retry after an error: collects both lists again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Gym Desk (role Gym Staff). */
@Composable
fun GymDeskDashboard(session: SessionState.SignedIn) {
    val viewModel: GymDeskDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(NbmsIcons.Dumbbell, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            DashGreeting(
                title = "Gym Desk",
                subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone),
                modifier = Modifier.weight(1f)
            )
        }
        FrontlineStateHost(state.loading, state.failed, MSG_GYM_LOAD_FAILED, viewModel::retry) {
            WithWidth { width ->
                val columns = columnsFor(width, narrow = 2, wide = 4)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TileGrid(
                        columns,
                        listOf(
                            { QuickActionTile("Check-In / Out", Icons.AutoMirrored.Outlined.Login, { viewModel.open(GYM_DESK_ROUTE_CHECKIN) }, Modifier.fillMaxWidth(), primary = true) },
                            { QuickActionTile("Members", NbmsIcons.Users, { viewModel.open(GYM_DESK_ROUTE_MEMBERS) }, Modifier.fillMaxWidth()) },
                            { QuickActionTile("Attendance Log", NbmsIcons.CalendarClock, { viewModel.open(GYM_DESK_ROUTE_ATTENDANCE) }, Modifier.fillMaxWidth()) },
                            { QuickActionTile("Reports", NbmsIcons.BarChart, { viewModel.open(GYM_DESK_ROUTE_REPORTS) }, Modifier.fillMaxWidth()) }
                        )
                    )
                    TileGrid(
                        columns,
                        listOf(
                            { FrontlineStatCard("Active Members", state.active.toString(), nbms.success) },
                            { FrontlineStatCard("Currently In Gym", state.inGymNow.toString(), nbms.info) },
                            { FrontlineStatCard("Visits Today", state.visitsToday.toString(), MaterialTheme.colorScheme.primary) },
                            { FrontlineStatCard("Expiring ≤ 7 Days", state.expiringCount.toString(), orangeTone()) }
                        )
                    )
                }
            }
            if (state.expiring.isNotEmpty()) {
                val orange = orangeTone()
                SectionCard(
                    title = "Memberships Expiring Soon",
                    icon = NbmsIcons.CalendarClock,
                    linkText = if (state.hasMoreExpiring) "View all" else null,
                    onLink = if (state.hasMoreExpiring) ({ viewModel.open(GYM_DESK_ROUTE_MEMBERS) }) else null
                ) {
                    state.expiring.forEach { member ->
                        RowItem(
                            primary = member.name,
                            trailing = {
                                Text(
                                    "${member.daysLeft}d left",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = orange
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
