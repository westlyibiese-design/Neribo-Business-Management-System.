package com.westly.nbms.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.DashAttendance
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashLaundry
import com.westly.nbms.features.dashboard.data.DashMaintenance
import com.westly.nbms.features.dashboard.data.DashOrder
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.dashboard.data.DashTask
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.tasks.TaskAssignDialog
import com.westly.nbms.features.tasks.TaskDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** The live lists the Operations dashboard reads (one cold flow each, so Retry can collect them again). */
internal class OperationsFlows(
    val rooms: Flow<Resource<List<DashRoom>>>,
    val bookings: Flow<Resource<List<DashBooking>>>,
    val tasks: Flow<Resource<List<DashTask>>>,
    val orders: Flow<Resource<List<DashOrder>>>,
    val barOrders: Flow<Resource<List<DashOrder>>>,
    val laundry: Flow<Resource<List<DashLaundry>>>,
    val maintenance: Flow<Resource<List<DashMaintenance>>>,
    val attendance: Flow<Resource<List<DashAttendance>>>
)

/** Everything the Operations screen shows. A section of a module the business does not have is null (and its list is never read). */
internal data class OperationsState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val pills: List<OpsPill> = emptyList(),
    val roomsOccupied: String = "0/0",
    val checkIns: Int = 0,
    val checkOuts: Int = 0,
    val staffOnDuty: Int = 0,
    val activeTasks: Int = 0,
    val openMaintenance: Int = 0,
    val showRoomAssignments: Boolean = false,
    val reservations: OpsSection = OpsSection(reservationsSub(0), emptyList()),
    val readiness: OpsReadiness? = null,
    val maintenance: OpsSection = OpsSection(null, emptyList()),
    val restaurant: OpsSection? = null,
    val bar: OpsSection? = null,
    val laundry: OpsSection? = null,
    val staff: StaffChips = StaffChips(emptyList(), 0)
)

/** Works out the whole Operations dashboard from the live lists. Pure: all numbers come from [DashboardMath]. */
internal fun operationsStateOf(
    rooms: Resource<List<DashRoom>>,
    bookings: Resource<List<DashBooking>>,
    tasks: Resource<List<DashTask>>,
    orders: Resource<List<DashOrder>>,
    barOrders: Resource<List<DashOrder>>,
    laundry: Resource<List<DashLaundry>>,
    maintenance: Resource<List<DashMaintenance>>,
    attendance: Resource<List<DashAttendance>>,
    modules: Set<ModuleKey>,
    now: Instant,
    zone: ZoneId
): OperationsState {
    val failed = anyFailed(rooms, bookings, tasks, orders, barOrders, laundry, maintenance, attendance)
    val loading = !failed && anyLoading(rooms, bookings, tasks, orders, barOrders, laundry, maintenance, attendance)
    val visible = opsVisibility(modules)
    val roomRows = rooms.rowsOrEmpty()
    val counts = DashboardMath.roomCounts(roomRows)
    val bookingRows = bookings.rowsOrEmpty()
    val reservations = DashboardMath.pendingReservations(bookingRows)
    val taskRows = tasks.rowsOrEmpty()
    val overdue = DashboardMath.overdueTasks(taskRows, now)
    val open = DashboardMath.openMaintenance(maintenance.rowsOrEmpty())
    val onDuty = DashboardMath.staffOnDuty(attendance.rowsOrEmpty(), now.atZone(zone).toLocalDate().toString())

    val foodRows = orders.rowsOrEmpty()
    val pendingFood = DashboardMath.pendingFood(foodRows)
    val unassigned = DashboardMath.unassignedFood(foodRows)
    val pendingBar = DashboardMath.pendingFood(barOrders.rowsOrEmpty())
    val activeLaundry = DashboardMath.activeLaundry(laundry.rowsOrEmpty())

    return OperationsState(
        loading = loading,
        failed = failed,
        now = now,
        pills = opsAlertPills(reservations.size, overdue.size, open.size),
        roomsOccupied = "${counts.occupied}/${counts.total}",
        checkIns = DashboardMath.todayCheckIns(bookingRows, now, zone).size,
        checkOuts = DashboardMath.todayCheckOuts(bookingRows, now, zone).size,
        staffOnDuty = onDuty.size,
        activeTasks = DashboardMath.activeTasks(taskRows).size,
        openMaintenance = open.size,
        showRoomAssignments = visible.housekeeping,
        reservations = OpsSection(reservationsSub(reservations.size), reservationRows(reservations, zone)),
        readiness = if (visible.housekeeping) {
            OpsReadiness(counts.available, counts.occupied, counts.cleaning, counts.maintenance, readinessRows(roomRows))
        } else null,
        maintenance = OpsSection(null, maintenanceRows(open)),
        restaurant = if (visible.restaurant) {
            OpsSection(restaurantSub(pendingFood.size, unassigned.size), restaurantRows(unassigned))
        } else null,
        bar = if (visible.bar) OpsSection(barSub(pendingBar.size), barRows(pendingBar)) else null,
        laundry = if (visible.laundry) OpsSection(laundrySub(activeLaundry.size), laundryRows(activeLaundry)) else null,
        staff = staffChips(onDuty)
    )
}

private fun <T> noRows(): Flow<Resource<List<T>>> = flowOf(Resource.Success(emptyList()))

/** The repository lists for a business with these [modules]; a list of a module the business lacks is never collected. */
internal fun operationsFlowsOf(repository: DashboardRepository, modules: Set<ModuleKey>): OperationsFlows {
    val visible = opsVisibility(modules)
    return OperationsFlows(
        rooms = repository.rooms(),
        bookings = repository.bookings(),
        tasks = repository.tasks(),
        orders = if (visible.restaurant) repository.orders() else noRows(),
        barOrders = if (visible.bar) repository.barOrders() else noRows(),
        laundry = if (visible.laundry) repository.laundry() else noRows(),
        maintenance = repository.maintenance(),
        attendance = repository.attendance()
    )
}

private fun modulesOf(session: SessionManager): Flow<Set<ModuleKey>> =
    session.state.map { (it as? SessionState.SignedIn)?.modules ?: emptySet() }.distinctUntilChanged()

private class OpsInputsA(
    val rooms: Resource<List<DashRoom>>,
    val bookings: Resource<List<DashBooking>>,
    val tasks: Resource<List<DashTask>>,
    val orders: Resource<List<DashOrder>>
)

private class OpsInputsB(
    val barOrders: Resource<List<DashOrder>>,
    val laundry: Resource<List<DashLaundry>>,
    val maintenance: Resource<List<DashMaintenance>>,
    val attendance: Resource<List<DashAttendance>>
)

/** Operations Manager dashboard data. All numbers are live and computed in the business time zone. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class OperationsDashboardViewModel internal constructor(
    private val flows: (Set<ModuleKey>) -> OperationsFlows,
    private val modules: Flow<Set<ModuleKey>>,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(repository: DashboardRepository, session: SessionManager, navigator: ShellNavigator) : this(
        flows = { mods -> operationsFlowsOf(repository, mods) },
        modules = modulesOf(session),
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<OperationsState> = combine(attempt, modules) { _, mods -> mods }
        .flatMapLatest { mods ->
            val f = flows(mods)
            val a = combine(f.rooms, f.bookings, f.tasks, f.orders) { r, b, t, o -> OpsInputsA(r, b, t, o) }
            val b = combine(f.barOrders, f.laundry, f.maintenance, f.attendance) { bar, l, m, att -> OpsInputsB(bar, l, m, att) }
            combine(a, b, clock) { x, y, now ->
                operationsStateOf(x.rooms, x.bookings, x.tasks, x.orders, y.barOrders, y.laundry, y.maintenance, y.attendance, mods, now, zone)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperationsState())

    /** Retry after an error: collects every list again. */
    fun retry() { attempt.value += 1 }

    /** Opens a page through the shell (an unknown or forbidden route lands on the Dashboard). */
    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Operations Manager. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationsDashboard(session: SessionState.SignedIn) {
    val viewModel: OperationsDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms
    val scheme = MaterialTheme.colorScheme
    val orange = if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    val yellow = if (nbms.isDark) Color(0xFFFACC15) else Color(0xFFCA8A04)

    var dialogOpen by remember { mutableStateOf(false) }
    var dialogDefaults by remember { mutableStateOf<TaskDefaults?>(null) }
    val assign: (TaskDefaults?) -> Unit = { defaults ->
        dialogDefaults = defaults
        dialogOpen = true
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Operations Overview",
            subtitle = operationsSubtitle(session.user.name, if (state.now == Instant.EPOCH) Instant.now() else state.now, zone)
        )
        DashStateHost(state.loading, state.failed, viewModel::retry) {
            WithWidth { width ->
                val buttons = buildList<@Composable () -> Unit> {
                    add { OpsActionButton("Shift Scheduling", NbmsIcons.CalendarClock) { viewModel.open(ROUTE_SHIFTS) } }
                    add { OpsActionButton("All Tasks", NbmsIcons.ClipboardCheck) { viewModel.open(ROUTE_TASKS) } }
                    if (state.showRoomAssignments) {
                        add { OpsActionButton("Room Assignments", NbmsIcons.Bed) { viewModel.open(ROUTE_ROOM_ASSIGNMENTS) } }
                    }
                    add {
                        NbmsButton(text = "Assign Task", onClick = { assign(null) }, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Plus)
                    }
                }
                if (width < 600.dp) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) { buttons.forEach { it() } }
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { buttons.forEach { it() } }
                }
            }
            if (state.pills.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.pills.forEach { pill ->
                        val icon = when (pill.tone) {
                            AlertTone.BLUE -> NbmsIcons.CalendarCheck
                            AlertTone.RED -> NbmsIcons.AlertTriangle
                            AlertTone.ORANGE -> NbmsIcons.Wrench
                        }
                        AlertPill(pill.text, icon, pill.tone)
                    }
                }
            }
            WithWidth { width ->
                TileGrid(
                    columnsFor(width, narrow = 3, wide = 6),
                    listOf(
                        { MiniStat("Rooms Occupied", state.roomsOccupied, NbmsIcons.Bed, nbms.info) },
                        { MiniStat("Check-ins Today", state.checkIns.toString(), NbmsIcons.CalendarCheck, nbms.success) },
                        { MiniStat("Check-outs Today", state.checkOuts.toString(), NbmsIcons.CalendarClock, orange) },
                        { MiniStat("Staff On Duty", state.staffOnDuty.toString(), NbmsIcons.Users, scheme.primary) },
                        { MiniStat("Active Tasks", state.activeTasks.toString(), NbmsIcons.ClipboardCheck, nbms.gold) },
                        { MiniStat("Open Maintenance", state.openMaintenance.toString(), NbmsIcons.Wrench, nbms.destructive) }
                    )
                )
            }

            OpsSectionCard(
                title = "Room Reservations", icon = NbmsIcons.CalendarCheck, linkText = "View All",
                onLink = { viewModel.open(ROUTE_ROOM_RESERVATIONS) },
                section = state.reservations, emptyText = "No unconfirmed reservations.", onAssign = assign
            )
            state.readiness?.let { readiness ->
                OpsSectionCard(
                    title = "Room Readiness", icon = NbmsIcons.Bed, linkText = "Housekeeping",
                    onLink = { viewModel.open(ROUTE_HOUSEKEEPING) },
                    section = OpsSection(null, readiness.rows), emptyText = "No rooms currently awaiting cleaning.", onAssign = assign,
                    header = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadinessFigure("Available", readiness.available, nbms.success, Modifier.weight(1f))
                            ReadinessFigure("Occupied", readiness.occupied, nbms.info, Modifier.weight(1f))
                            ReadinessFigure("Needs Cleaning", readiness.cleaning, yellow, Modifier.weight(1f))
                            ReadinessFigure("Maintenance", readiness.maintenance, orange, Modifier.weight(1f))
                        }
                    }
                )
            }
            OpsSectionCard(
                title = "Maintenance", icon = NbmsIcons.Wrench, linkText = "View All",
                onLink = { viewModel.open(ROUTE_MAINTENANCE) },
                section = state.maintenance, emptyText = "No open maintenance requests.", onAssign = assign
            )
            state.restaurant?.let { section ->
                OpsSectionCard(
                    title = "Restaurant", icon = NbmsIcons.Utensils, linkText = "View All",
                    onLink = { viewModel.open(ROUTE_ORDER_HISTORY) },
                    section = section, emptyText = "No unassigned food orders.", onAssign = assign
                )
            }
            state.bar?.let { section ->
                OpsSectionCard(
                    title = "Bar", icon = NbmsIcons.Wine, linkText = "View All",
                    onLink = { viewModel.open(ROUTE_BAR_SALES_HISTORY) },
                    section = section, emptyText = "No pending bar sales.", onAssign = assign
                )
            }
            state.laundry?.let { section ->
                OpsSectionCard(
                    title = "Laundry", icon = NbmsIcons.Laundry, linkText = "View All",
                    onLink = { viewModel.open(ROUTE_LAUNDRY) },
                    section = section, emptyText = "No active laundry requests.", onAssign = assign
                )
            }
            StaffOnDutyCard(state.staff) { viewModel.open(ROUTE_ATTENDANCE) }
            GymOverviewCard(session)
        }
    }

    TaskAssignDialog(open = dialogOpen, onDismiss = { dialogOpen = false }, defaults = dialogDefaults)
}

@Composable
private fun OpsActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NbmsButton(text = text, onClick = onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = icon)
}

@Composable
private fun ReadinessFigure(label: String, value: Int, tint: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = tint)
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** A section card: optional sub line, optional header, then at most six rows (each with an Assign button) or the empty text. */
@Composable
private fun OpsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    linkText: String,
    onLink: () -> Unit,
    section: OpsSection,
    emptyText: String,
    onAssign: (TaskDefaults) -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = title, icon = icon, linkText = linkText, onLink = onLink) {
        section.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        header?.invoke(this)
        if (section.rows.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            section.rows.forEach { row ->
                RowItem(
                    primary = row.primary,
                    secondary = row.secondary,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.badge?.let { NbmsBadge(it, BadgeTone.Outline) }
                            NbmsButton(
                                text = "Assign",
                                onClick = { onAssign(row.defaults) },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                leadingIcon = NbmsIcons.Plus
                            )
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffOnDutyCard(staff: StaffChips, onAttendance: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Staff On Duty", icon = NbmsIcons.Users, linkText = "Attendance", onLink = onAttendance) {
        if (staff.names.isEmpty()) {
            Text(
                "No attendance recorded yet today.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                staff.names.forEach { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(scheme.secondary)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (staff.more > 0) {
                    Text(
                        moreChipText(staff.more),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, MaterialTheme.nbms.cardBorder, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
