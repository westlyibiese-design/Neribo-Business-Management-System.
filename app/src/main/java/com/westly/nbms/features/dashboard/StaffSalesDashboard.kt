package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.dashboard.data.MSG_DASHBOARD_LOAD_FAILED
import com.westly.nbms.features.inventory.InventoryItem
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

/** How many low-stock items the orange Low Stock card lists. */
internal const val STAFF_LOW_STOCK_COUNT = 5

/** The live lists the Staff (sales) dashboard reads: only this person's sales, plus the shared inventory. */
internal class StaffFlows(
    val sales: Flow<Resource<List<AmountRow>>>,
    val inventory: Flow<Resource<List<InventoryItem>>>
)

/** Everything the Staff screen shows. */
internal data class StaffState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val todayCount: Int = 0,
    val todayAmount: Double = 0.0,
    val totalCount: Int = 0,
    val totalAmount: Double = 0.0,
    val lowStock: List<InventoryItem> = emptyList()
)

/**
 * Works out the whole Staff dashboard. Pure: all numbers come from [FrontlineMath]. A failing sales list is an error; a failing
 * inventory list only hides the Low Stock card (a role that cannot read inventory must still see its own sales).
 */
internal fun staffStateOf(
    sales: Resource<List<AmountRow>>,
    inventory: Resource<List<InventoryItem>>,
    now: Instant,
    zone: ZoneId
): StaffState {
    val failed = anyFailed(sales)
    val loading = !failed && anyLoading(sales, inventory)
    val (today, all) = FrontlineMath.myTotals(sales.rowsOrEmpty(), now, zone)
    return StaffState(
        loading = loading,
        failed = failed,
        now = now,
        todayCount = today.first,
        todayAmount = today.second,
        totalCount = all.first,
        totalAmount = all.second,
        lowStock = FrontlineMath.lowStockTop(inventory.rowsOrEmpty(), STAFF_LOW_STOCK_COUNT)
    )
}

/** Staff (sales) dashboard data: only the signed-in person's own sales. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class StaffSalesDashboardViewModel internal constructor(
    private val flows: (String) -> StaffFlows,
    private val uid: String,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(
        frontline: FrontlineRepository,
        repository: DashboardRepository,
        session: SessionManager,
        navigator: ShellNavigator
    ) : this(
        flows = { uid -> StaffFlows(frontline.mySales(uid), repository.inventory()) },
        uid = uidOfSession(session),
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<StaffState> = attempt
        .flatMapLatest {
            val f = flows(uid)
            combine(f.sales, f.inventory) { s, i -> s to i }
                .combine(clock) { (s, i), now -> staffStateOf(s, i, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StaffState())

    /** Retry after an error: collects the lists again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** Right-hand text of a Low Stock row: "3 left". */
internal fun leftText(item: InventoryItem): String = "${item.quantity} left"

/** The Dashboard of the Staff (sales) role. */
@Composable
fun StaffSalesDashboard(session: SessionState.SignedIn) {
    val viewModel: StaffSalesDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val scheme = MaterialTheme.colorScheme
    val orange = orangeTone()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "My Dashboard",
            subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_DASHBOARD_LOAD_FAILED, viewModel::retry) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionTile("New Sale", NbmsIcons.ShoppingCart, { viewModel.open(ROUTE_SALES_NEW) }, Modifier.weight(1f), primary = true)
                QuickActionTile("Sales History", NbmsIcons.History, { viewModel.open(ROUTE_SALES_HISTORY) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Today's Sales", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(
                            state.todayCount.toString(),
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary
                        )
                        Text(Format.currency(state.todayAmount, symbol), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
                NbmsCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Total My Sales", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(
                            state.totalCount.toString(),
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                        Text(Format.currency(state.totalAmount, symbol), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
            }
            if (state.lowStock.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().tintedBox(orange).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = orange, modifier = Modifier.size(18.dp))
                        Text(
                            "Low Stock",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface
                        )
                    }
                    state.lowStock.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(leftText(item), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = orange)
                        }
                    }
                }
            }
            FrontlineFootnote(OWN_ACTIVITY_NOTE)
        }
    }
}
