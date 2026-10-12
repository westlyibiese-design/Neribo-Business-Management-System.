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
import androidx.compose.ui.text.style.TextOverflow
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
import com.westly.nbms.features.housekeeping.PriorityPill
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

/** Largest number of rooms the My Cleaning Queue card shows. */
internal const val HK_QUEUE_CARD_COUNT = 9

/** The live lists the Housekeeping dashboard reads: only this housekeeper's queue and room assignments. */
internal class HousekeepingFlows(
    val queue: Flow<Resource<List<HkTaskRow>>>,
    val rooms: Flow<Resource<Int>>
)

/** Everything the Housekeeping screen shows. It never carries hotel-wide room counts. */
internal data class HousekeepingState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val queue: Int = 0,
    val highPriority: Int = 0,
    val myRooms: Int = 0,
    val tasks: List<HkTaskRow> = emptyList()
)

/** Works out the Housekeeping numbers and the queue grid. Pure: all numbers come from [FrontlineMath]. */
internal fun housekeepingStateOf(
    queue: Resource<List<HkTaskRow>>,
    rooms: Resource<Int>,
    now: Instant
): HousekeepingState {
    val failed = anyFailed(queue, rooms)
    val loading = !failed && anyLoading(queue, rooms)
    val rows = queue.rowsOrEmpty()
    val stats = FrontlineMath.housekeepingStats(rows, (rooms as? Resource.Success)?.data ?: 0)
    return HousekeepingState(
        loading = loading,
        failed = failed,
        now = now,
        queue = stats.queue,
        highPriority = stats.highPriority,
        myRooms = stats.myRooms,
        tasks = rows.filter { it.status == "pending" || it.status == "in_progress" }.take(HK_QUEUE_CARD_COUNT)
    )
}

/** Housekeeping dashboard data: only the signed-in housekeeper's own tasks and assignments. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HousekeepingDashboardViewModel internal constructor(
    private val flows: (String) -> HousekeepingFlows,
    private val uid: String,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(
        frontline: FrontlineRepository,
        session: SessionManager,
        navigator: ShellNavigator
    ) : this(
        flows = { uid -> HousekeepingFlows(frontline.myHousekeepingQueue(uid), frontline.myRoomAssignmentCount(uid)) },
        uid = uidOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<HousekeepingState> = attempt
        .flatMapLatest {
            val f = flows(uid)
            combine(f.queue, f.rooms) { q, r -> q to r }
                .combine(clock) { (q, r), now -> housekeepingStateOf(q, r, now) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HousekeepingState())

    /** Retry after an error: collects the lists again. */
    fun retry() { attempt.value += 1 }

    fun open(route: String) = navigator.open(route)
}

/** The Dashboard of the Housekeeping role. */
@Composable
fun HousekeepingDashboard(session: SessionState.SignedIn) {
    val viewModel: HousekeepingDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms
    val yellow = yellowTone()
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Housekeeping",
            subtitle = managerSubtitle(session.user.name, nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_QUEUE_LOAD_FAILED, viewModel::retry) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrontlineStatCard("My Queue", state.queue.toString(), yellow, Modifier.weight(1f))
                FrontlineStatCard("High Priority", state.highPriority.toString(), redTone(), Modifier.weight(1f))
                FrontlineStatCard("My Rooms", state.myRooms.toString(), nbms.info, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionTile("My Housekeeping", NbmsIcons.Sparkles, { viewModel.open(ROUTE_HOUSEKEEPING) }, Modifier.weight(1f), primary = true)
                QuickActionTile("Maintenance", NbmsIcons.Wrench, { viewModel.open(ROUTE_MAINTENANCE) }, Modifier.weight(1f))
            }
            FrontlineSectionCard("My Cleaning Queue", NbmsIcons.Sparkles, scheme.onSurfaceVariant) {
                if (state.tasks.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = nbms.success, modifier = Modifier.size(20.dp))
                        Text("Your queue is empty!", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                } else {
                    WithWidth { width ->
                        TileGrid(
                            columnsFor(width, narrow = 2, wide = 3),
                            state.tasks.map { task -> @Composable { QueueRoomTile(task, yellow) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRoomTile(task: HkTaskRow, yellow: androidx.compose.ui.graphics.Color) {
    val priority = priorityOf(task.priority)
    Row(
        modifier = Modifier.fillMaxWidth().tintedBox(yellow, cornerDp = 8).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.Bed, contentDescription = null, tint = yellow, modifier = Modifier.size(16.dp))
        Text(
            roomLabel(task.roomNumber),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (priority != null) PriorityPill(priority)
    }
}
