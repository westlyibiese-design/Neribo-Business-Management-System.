package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** The live list the Waiter dashboard reads: only this waiter's own orders. */
internal class WaiterFlows(val orders: Flow<Resource<List<OrderRow>>>)

/** Everything the Waiter screen shows. */
internal data class WaiterState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val todayCount: Int = 0,
    val pendingCount: Int = 0,
    val todayAmount: Double = 0.0
)

/** Works out the Waiter numbers from the waiter's own orders. Pure: all numbers come from [FrontlineMath]. */
internal fun waiterStateOf(orders: Resource<List<OrderRow>>, now: Instant, zone: ZoneId): WaiterState {
    val failed = anyFailed(orders)
    val loading = !failed && anyLoading(orders)
    val stats = FrontlineMath.orderStats(orders.rowsOrEmpty(), now, zone)
    return WaiterState(
        loading = loading,
        failed = failed,
        now = now,
        todayCount = stats.todayCount,
        pendingCount = stats.pendingCount,
        todayAmount = stats.todayAmount
    )
}

/** Waiter dashboard data: only orders whose `waiterId` is the signed-in person; a second waiter's orders never reach it. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class WaiterDashboardViewModel internal constructor(
    private val flows: (String) -> WaiterFlows,
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
        flows = { uid -> WaiterFlows(frontline.myOrders(uid)) },
        uid = uidOfSession(session),
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<WaiterState> = attempt
        .flatMapLatest {
            flows(uid).orders.combine(clock) { orders, now -> waiterStateOf(orders, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WaiterState())

    /** Retry after an error: collects the orders again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Waiter. */
@Composable
fun WaiterDashboard(session: SessionState.SignedIn) {
    val viewModel: WaiterDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val nbms = MaterialTheme.nbms

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "My Orders",
            subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_ORDERS_LOAD_FAILED, viewModel::retry) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionTile("New Order", NbmsIcons.Coffee, { viewModel.open(ROUTE_ORDERS_NEW) }, Modifier.weight(1f), primary = true)
                QuickActionTile("Order History", NbmsIcons.History, { viewModel.open(ROUTE_ORDERS_HISTORY) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrontlineStatCard("Today's Orders", state.todayCount.toString(), nbms.info, Modifier.weight(1f))
                FrontlineStatCard("Pending", state.pendingCount.toString(), orangeTone(), Modifier.weight(1f))
                FrontlineStatCard("Today's Revenue", Format.currency(state.todayAmount, symbol), nbms.success, Modifier.weight(1f))
            }
            FrontlineFootnote(OWN_ACTIVITY_NOTE)
        }
    }
}
