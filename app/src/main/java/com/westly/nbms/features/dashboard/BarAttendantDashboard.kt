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
import com.westly.nbms.core.util.Format
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

internal const val BAR_DASH_ROUTE_NEW_SALE = "bar/new-sale"
internal const val BAR_DASH_ROUTE_SALES_HISTORY = "bar/sales-history"
internal const val MSG_BAR_LOAD_FAILED = "We couldn't load your bar sales."

/** The live list the Bar Attendant dashboard reads: only this attendant's own bar orders. */
internal class BarFlows(val orders: Flow<Resource<List<OrderRow>>>)

/** Everything the Bar Attendant screen shows. */
internal data class BarState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val todayCount: Int = 0,
    val pendingCount: Int = 0,
    val todayAmount: Double = 0.0
)

/** Works out the Bar Attendant numbers from the attendant's own orders. Pure: all numbers come from [FrontlineMath]. */
internal fun barStateOf(orders: Resource<List<OrderRow>>, now: Instant, zone: ZoneId): BarState {
    val failed = anyFailed(orders)
    val loading = !failed && anyLoading(orders)
    val stats = FrontlineMath.orderStats(orders.rowsOrEmpty(), now, zone)
    return BarState(
        loading = loading,
        failed = failed,
        now = now,
        todayCount = stats.todayCount,
        pendingCount = stats.pendingCount,
        todayAmount = stats.todayAmount
    )
}

/** Bar dashboard data: only bar orders whose `barAttendantId` is the signed-in person; a colleague's sales never reach it. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BarAttendantDashboardViewModel internal constructor(
    private val flows: (String) -> BarFlows,
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
        flows = { uid -> BarFlows(frontline.myBarOrders(uid)) },
        uid = uidOfSession(session),
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<BarState> = attempt
        .flatMapLatest {
            flows(uid).orders.combine(clock) { orders, now -> barStateOf(orders, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BarState())

    /** Retry after an error: collects the orders again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Bar Attendant. */
@Composable
fun BarAttendantDashboard(session: SessionState.SignedIn) {
    val viewModel: BarAttendantDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val nbms = MaterialTheme.nbms

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Bar",
            subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_BAR_LOAD_FAILED, viewModel::retry) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionTile("New Sale", NbmsIcons.Wine, { viewModel.open(BAR_DASH_ROUTE_NEW_SALE) }, Modifier.weight(1f), primary = true)
                QuickActionTile("Sales History", NbmsIcons.History, { viewModel.open(BAR_DASH_ROUTE_SALES_HISTORY) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrontlineStatCard("Today's Sales", state.todayCount.toString(), nbms.info, Modifier.weight(1f))
                FrontlineStatCard("Pending", state.pendingCount.toString(), orangeTone(), Modifier.weight(1f))
                FrontlineStatCard("Today's Revenue", Format.currency(state.todayAmount, symbol), nbms.success, Modifier.weight(1f))
            }
            FrontlineFootnote(OWN_ACTIVITY_NOTE)
        }
    }
}
