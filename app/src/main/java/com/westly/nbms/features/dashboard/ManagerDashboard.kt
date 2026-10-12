package com.westly.nbms.features.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.dashboard.data.DashboardRepository
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

internal const val MANAGER_LIST_COUNT = 5

/** The live lists the Manager dashboard reads. */
internal class ManagerFlows(
    val rooms: Flow<Resource<List<DashRoom>>>,
    val bookings: Flow<Resource<List<DashBooking>>>,
    val inventory: Flow<Resource<List<InventoryItem>>>
)

/** Everything the Manager screen shows. */
internal data class ManagerState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val availableRooms: Int = 0,
    val checkInCount: Int = 0,
    val checkOutCount: Int = 0,
    val pendingApprovals: Int = 0,
    val lowStock: List<InventoryItem> = emptyList(),
    val todayCheckIns: List<DashBooking> = emptyList(),
    val pendingBookings: List<DashBooking> = emptyList()
)

/** Works out the whole Manager dashboard from the live lists. Pure: all numbers come from [DashboardMath]. */
internal fun managerStateOf(
    rooms: Resource<List<DashRoom>>,
    bookings: Resource<List<DashBooking>>,
    inventory: Resource<List<InventoryItem>>,
    now: Instant,
    zone: ZoneId
): ManagerState {
    val failed = anyFailed(rooms, bookings, inventory)
    val loading = !failed && anyLoading(rooms, bookings, inventory)
    val bookingRows = bookings.rowsOrEmpty()
    val checkIns = DashboardMath.todayCheckIns(bookingRows, now, zone)
    val pending = DashboardMath.pendingBookings(bookingRows)
    return ManagerState(
        loading = loading,
        failed = failed,
        now = now,
        availableRooms = DashboardMath.roomCounts(rooms.rowsOrEmpty()).available,
        checkInCount = checkIns.size,
        checkOutCount = DashboardMath.todayCheckOuts(bookingRows, now, zone).size,
        pendingApprovals = pending.size,
        lowStock = DashboardMath.lowStock(inventory.rowsOrEmpty()),
        todayCheckIns = checkIns.take(MANAGER_LIST_COUNT),
        pendingBookings = pending.take(MANAGER_LIST_COUNT)
    )
}

private class ManagerInputs(
    val rooms: Resource<List<DashRoom>>,
    val bookings: Resource<List<DashBooking>>,
    val inventory: Resource<List<InventoryItem>>
)

/** Manager dashboard data. All numbers are live and computed in the business time zone. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ManagerDashboardViewModel internal constructor(
    private val flows: () -> ManagerFlows,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(repository: DashboardRepository, session: SessionManager, navigator: ShellNavigator) : this(
        flows = { ManagerFlows(repository.rooms(), repository.bookings(), repository.inventory()) },
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<ManagerState> = attempt
        .flatMapLatest {
            val f = flows()
            combine(f.rooms, f.bookings, f.inventory) { r, b, i -> ManagerInputs(r, b, i) }
                .combine(clock) { i, now -> managerStateOf(i.rooms, i.bookings, i.inventory, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManagerState())

    /** Retry after an error: collects every list again. */
    fun retry() { attempt.value += 1 }

    /** The "Review" button of a pending booking. */
    fun openBookings() = navigator.open(ROUTE_BOOKINGS)
}

/** "Low Stock Alerts" chip text: "Soap: 3 left". */
internal fun lowStockChipText(item: InventoryItem): String = "${item.name}: ${item.quantity} left"

/** The Dashboard of the Manager. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManagerDashboard(session: SessionState.SignedIn) {
    val viewModel: ManagerDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms
    val orange = if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    val yellow = if (nbms.isDark) Color(0xFFFACC15) else Color(0xFFCA8A04)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Manager Dashboard",
            subtitle = managerSubtitle(session.user.name, if (state.now == Instant.EPOCH) Instant.now() else state.now, zone)
        )
        DashStateHost(state.loading, state.failed, viewModel::retry) {
            WithWidth { width ->
                TileGrid(
                    columnsFor(width, narrow = 2, wide = 4),
                    listOf(
                        { ManagerStat("Available Rooms", state.availableRooms, NbmsIcons.Bed, nbms.success) },
                        { ManagerStat("Today Check-Ins", state.checkInCount, NbmsIcons.CalendarCheck, nbms.info) },
                        { ManagerStat("Today Check-Outs", state.checkOutCount, NbmsIcons.CalendarClock, orange) },
                        { ManagerStat("Pending Approvals", state.pendingApprovals, NbmsIcons.Clock, yellow) }
                    )
                )
            }
            if (state.lowStock.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(orange.copy(alpha = 0.10f))
                        .border(BorderStroke(1.dp, orange.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = orange, modifier = Modifier.size(18.dp))
                        Text("Low Stock Alerts", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.lowStock.forEach { item ->
                            Text(
                                lowStockChipText(item),
                                style = MaterialTheme.typography.labelMedium,
                                color = orange,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, orange.copy(alpha = 0.6f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            WithWidth { width ->
                val sideBySide = width >= 600.dp
                val checkIns: @Composable (Modifier) -> Unit = { m -> TodayCheckInsCard(state.todayCheckIns, m) }
                val pending: @Composable (Modifier) -> Unit = { m -> PendingBookingsCard(state.pendingBookings, zone, viewModel::openBookings, m) }
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        checkIns(Modifier.weight(1f))
                        pending(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        checkIns(Modifier)
                        pending(Modifier)
                    }
                }
            }
            GymOverviewCard(session)
        }
    }
}

@Composable
private fun ManagerStat(label: String, value: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(value.toString(), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
        }
    }
}

@Composable
private fun TodayCheckInsCard(items: List<DashBooking>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Today's Check-Ins", icon = NbmsIcons.CalendarCheck, modifier = modifier) {
        if (items.isEmpty()) {
            Text("No check-ins today", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            items.forEach { b ->
                RowItem(
                    primary = b.guestName?.takeIf { it.isNotBlank() } ?: "Guest",
                    secondary = "Room ${b.roomNumber ?: "—"}",
                    trailing = { NbmsPill(statusText(b.status), MaterialTheme.nbms.statusPill(bookingBadgeKey(b.status))) }
                )
            }
        }
    }
}

@Composable
private fun PendingBookingsCard(items: List<DashBooking>, zone: ZoneId, onReview: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Pending Bookings", icon = NbmsIcons.Clock, modifier = modifier) {
        if (items.isEmpty()) {
            Text("No pending bookings", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            items.forEach { b ->
                RowItem(
                    primary = b.guestName?.takeIf { it.isNotBlank() } ?: "Guest",
                    secondary = dashDate(b.checkIn, zone),
                    trailing = { NbmsButton(text = "Review", onClick = onReview, variant = ButtonVariant.Outline, size = ButtonSize.Sm) }
                )
            }
        }
    }
}
