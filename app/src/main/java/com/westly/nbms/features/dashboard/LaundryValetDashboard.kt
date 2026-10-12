package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
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

internal const val LAUNDRY_DASH_ROUTE_MANAGE = "laundry"
internal const val LAUNDRY_DASH_ROUTE_HISTORY = "laundry/history"
internal const val MSG_LAUNDRY_LOAD_FAILED = "We couldn't load your laundry requests."

/** The live list the Laundry Valet dashboard reads: only this valet's own laundry requests. */
internal class LaundryFlows(val requests: Flow<Resource<List<FrontlineLaundryRow>>>)

/** Everything the Laundry Valet screen shows. */
internal data class LaundryState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val active: Int = 0,
    val ready: Int = 0,
    val deliveredToday: Int = 0
)

/** Works out the Laundry Valet numbers from the valet's own requests. Pure: all numbers come from [FrontlineMath]. */
internal fun laundryStateOf(requests: Resource<List<FrontlineLaundryRow>>, now: Instant, zone: ZoneId): LaundryState {
    val failed = anyFailed(requests)
    val loading = !failed && anyLoading(requests)
    val stats = FrontlineMath.laundryStats(requests.rowsOrEmpty(), now, zone)
    return LaundryState(
        loading = loading,
        failed = failed,
        now = now,
        active = stats.active,
        ready = stats.ready,
        deliveredToday = stats.deliveredToday
    )
}

/** Laundry dashboard data: only requests whose `laundryValetId` is the signed-in person; a colleague's requests never reach it. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LaundryValetDashboardViewModel internal constructor(
    private val flows: (String) -> LaundryFlows,
    private val uid: String,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(
        frontline: FrontlineRepository,
        session: SessionManager,
        navigator: ShellNavigator
    ) : this(
        flows = { uid -> LaundryFlows(frontline.myLaundry(uid)) },
        uid = uidOfSession(session),
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<LaundryState> = attempt
        .flatMapLatest {
            flows(uid).requests.combine(clock) { requests, now -> laundryStateOf(requests, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaundryState())

    /** Retry after an error: collects the requests again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Laundry Valet. */
@Composable
fun LaundryValetDashboard(session: SessionState.SignedIn) {
    val viewModel: LaundryValetDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Laundry",
            subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_LAUNDRY_LOAD_FAILED, viewModel::retry) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionTile("Manage Laundry", NbmsIcons.Laundry, { viewModel.open(LAUNDRY_DASH_ROUTE_MANAGE) }, Modifier.weight(1f), primary = true)
                QuickActionTile("History", NbmsIcons.History, { viewModel.open(LAUNDRY_DASH_ROUTE_HISTORY) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrontlineStatCard("Active Requests", state.active.toString(), nbms.info, Modifier.weight(1f))
                FrontlineStatCard("Ready for Collection", state.ready.toString(), orangeTone(), Modifier.weight(1f))
                FrontlineStatCard("Delivered Today", state.deliveredToday.toString(), nbms.success, Modifier.weight(1f))
            }
        }
    }
}
