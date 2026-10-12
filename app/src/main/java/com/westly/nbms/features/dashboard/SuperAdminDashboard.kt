package com.westly.nbms.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.dashboard.data.ActivityItem
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashExpense
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.charts.AreaChart
import com.westly.nbms.features.finance.charts.ChartPoint
import com.westly.nbms.shell.firstName
import com.westly.nbms.shell.initialOf
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

internal const val RECENT_BOOKINGS_COUNT = 5
internal const val ACTIVITY_FEED_COUNT = 8

/** The live lists the Super Admin dashboard reads (one cold flow each, so Retry can collect them again). */
internal class SuperAdminFlows(
    val rooms: Flow<Resource<List<DashRoom>>>,
    val bookings: Flow<Resource<List<DashBooking>>>,
    val expenses: Flow<Resource<List<DashExpense>>>,
    val approvedRevenue: Flow<Resource<List<RevenueTransaction>>>,
    val activity: Flow<Resource<List<ActivityItem>>>
)

/** Everything the Super Admin screen shows. */
internal data class SuperAdminState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val monthRevenue: Double = 0.0,
    val net: Double = 0.0,
    val occupancyPercent: Int = 0,
    val rooms: RoomCounts = RoomCounts(0, 0, 0, 0, 0, 0, 0),
    val checkIns: Int = 0,
    val checkOuts: Int = 0,
    val pendingApprovals: Int = 0,
    val last7Days: List<ChartPoint> = emptyList(),
    val recentBookings: List<DashBooking> = emptyList(),
    val activity: List<ActivityItem> = emptyList()
)

/** Works out the whole Super Admin dashboard from the live lists. Pure: all numbers come from [DashboardMath]. */
internal fun superAdminStateOf(
    rooms: Resource<List<DashRoom>>,
    bookings: Resource<List<DashBooking>>,
    expenses: Resource<List<DashExpense>>,
    approvedRevenue: Resource<List<RevenueTransaction>>,
    activity: Resource<List<ActivityItem>>,
    now: Instant,
    zone: ZoneId
): SuperAdminState {
    val failed = anyFailed(rooms, bookings, expenses, approvedRevenue)
    val loading = !failed && anyLoading(rooms, bookings, expenses, approvedRevenue)
    val roomRows = rooms.rowsOrEmpty()
    val bookingRows = bookings.rowsOrEmpty()
    val approved = approvedRevenue.rowsOrEmpty()
    val counts = DashboardMath.roomCounts(roomRows)
    val revenue = DashboardMath.monthRevenue(approved, now, zone)
    val spent = DashboardMath.monthExpenses(expenses.rowsOrEmpty(), now, zone)
    return SuperAdminState(
        loading = loading,
        failed = failed,
        now = now,
        monthRevenue = revenue,
        net = revenue - spent,
        occupancyPercent = DashboardMath.occupancyPercent(counts.occupied, counts.total),
        rooms = counts,
        checkIns = DashboardMath.todayCheckIns(bookingRows, now, zone).size,
        checkOuts = DashboardMath.todayCheckOuts(bookingRows, now, zone).size,
        pendingApprovals = DashboardMath.pendingBookings(bookingRows).size,
        last7Days = DashboardMath.last7Days(approved, now, zone),
        recentBookings = DashboardMath.recentBookings(bookingRows, RECENT_BOOKINGS_COUNT),
        activity = activity.rowsOrEmpty().sortedByDescending { it.at }.take(ACTIVITY_FEED_COUNT)
    )
}

private class SuperAdminInputs(
    val rooms: Resource<List<DashRoom>>,
    val bookings: Resource<List<DashBooking>>,
    val expenses: Resource<List<DashExpense>>,
    val approved: Resource<List<RevenueTransaction>>,
    val activity: Resource<List<ActivityItem>>
)

/** Super Admin dashboard data. All numbers are live and computed in the business time zone. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SuperAdminDashboardViewModel internal constructor(
    private val flows: () -> SuperAdminFlows,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(repository: DashboardRepository, session: SessionManager, navigator: ShellNavigator) : this(
        flows = {
            SuperAdminFlows(
                rooms = repository.rooms(),
                bookings = repository.bookings(),
                expenses = repository.expenses(),
                approvedRevenue = repository.approvedRevenue(),
                activity = repository.activityFeed(ACTIVITY_FEED_COUNT)
            )
        },
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<SuperAdminState> = attempt
        .flatMapLatest {
            val f = flows()
            combine(f.rooms, f.bookings, f.expenses, f.approvedRevenue, f.activity) { r, b, e, a, act ->
                SuperAdminInputs(r, b, e, a, act)
            }.combine(clock) { i, now ->
                superAdminStateOf(i.rooms, i.bookings, i.expenses, i.approved, i.activity, now, zone)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SuperAdminState())

    /** Retry after an error: collects every list again. */
    fun retry() { attempt.value += 1 }

    fun openBookings() = navigator.open(ROUTE_BOOKINGS)

    fun openRooms() = navigator.open(ROUTE_ROOMS)
}

/** The Dashboard of the Super Admin. */
@Composable
fun SuperAdminDashboard(session: SessionState.SignedIn) {
    val viewModel: SuperAdminDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val nbms = MaterialTheme.nbms
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = superAdminTitle(firstName(session.user.name)),
            subtitle = longDateText(if (state.now == Instant.EPOCH) Instant.now() else state.now, zone),
            trailing = {
                NbmsButton(
                    text = "View Bookings",
                    onClick = viewModel::openBookings,
                    size = ButtonSize.Sm,
                    leadingIcon = NbmsIcons.CalendarCheck
                )
            }
        )
        DashStateHost(state.loading, state.failed, viewModel::retry) {
            WithWidth { width ->
                val kpiColumns = if (width < 360.dp) 1 else 2
                TileGrid(
                    kpiColumns,
                    listOf(
                        {
                            KpiCard(
                                "Revenue (Month)", Format.currency(state.monthRevenue, symbol), NbmsIcons.Banknote,
                                scheme.primary, scheme.primary.copy(alpha = 0.12f),
                                sub = "Net: ${Format.currency(state.net, symbol)}"
                            )
                        },
                        {
                            KpiCard(
                                "Occupancy Rate", "${state.occupancyPercent}%", NbmsIcons.Bed,
                                nbms.gold, nbms.gold.copy(alpha = 0.15f),
                                sub = occupancySub(state.rooms.occupied, state.rooms.total)
                            )
                        },
                        {
                            KpiCard(
                                "Today's Activity", (state.checkIns + state.checkOuts).toString(), NbmsIcons.Activity,
                                nbms.info, nbms.info.copy(alpha = 0.15f),
                                sub = activitySub(state.checkIns, state.checkOuts)
                            )
                        },
                        {
                            val orange = if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
                            KpiCard(
                                "Pending Approvals", state.pendingApprovals.toString(), NbmsIcons.Clock,
                                orange, orange.copy(alpha = 0.15f),
                                sub = "Bookings awaiting review"
                            )
                        }
                    )
                )
            }
            RoomStatusCard(state.rooms, viewModel::openRooms)
            SectionCard(title = "Revenue — Last 7 Days", icon = NbmsIcons.TrendingUp) {
                AreaChart(
                    points = state.last7Days,
                    height = 200.dp,
                    lineColor = scheme.primary,
                    valueFormatter = { Format.currency(it, symbol) }
                )
            }
            RecentBookingsCard(state.recentBookings, zone, viewModel::openBookings)
            GymOverviewCard(session)
            if (state.activity.isNotEmpty()) {
                SectionCard(title = "Live Activity Feed", icon = NbmsIcons.Activity) {
                    state.activity.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                Format.relative(kotlinx.datetime.Instant.fromEpochMilliseconds(item.at), zone = kotlinx.datetime.TimeZone.of(zone.id)),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomStatusCard(rooms: RoomCounts, onManage: () -> Unit) {
    SectionCard(title = "Live Room Status", icon = NbmsIcons.Bed, linkText = "Manage", onLink = onManage) {
        WithWidth { width ->
            val tiles = listOf(
                Triple("Available", rooms.available, "available"),
                Triple("Occupied", rooms.occupied, "occupied"),
                Triple("Cleaning", rooms.cleaning, "cleaning"),
                Triple("Maintenance", rooms.maintenance, "maintenance")
            )
            val cells = buildList<@Composable () -> Unit> {
                tiles.forEach { (label, count, key) -> add { RoomTile(label, count, key) } }
            }
            TileGrid(columnsFor(width, narrow = 2, wide = 4), cells)
        }
    }
}

@Composable
private fun RoomTile(label: String, count: Int, statusKey: String) {
    val colours = MaterialTheme.nbms.statusPill(statusKey)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colours.container).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = colours.content)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colours.content, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecentBookingsCard(bookings: List<DashBooking>, zone: ZoneId, onViewAll: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Recent Bookings", icon = NbmsIcons.CalendarCheck) {
        if (bookings.isEmpty()) {
            Text("No bookings yet", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            bookings.forEach { b ->
                val guest = b.guestName?.takeIf { it.isNotBlank() } ?: "Guest"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Text(initialOf(guest), color = scheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(guest, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Room ${b.roomNumber ?: "—"} · ${dashDate(b.checkIn, zone)}",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    NbmsPill(statusText(b.status), MaterialTheme.nbms.statusPill(bookingBadgeKey(b.status)))
                }
            }
        }
        NbmsButton(text = "View all", onClick = onViewAll, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, modifier = Modifier.align(Alignment.End))
    }
}
