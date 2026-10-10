package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.nbms
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// ---- Pure rules (unit-tested) ----------------------------------------------------------------

internal const val WORKLOAD_TITLE = "Today's Workload Balance"
internal const val WORKLOAD_EMPTY_ROSTER = "No housekeeping staff on shift today."
internal const val DEFAULT_BUSINESS_ZONE = "Africa/Lagos"

/** A load is "heavier" when it is more than this share above the team average (Westly: 25 %). */
internal const val HEAVIER_FACTOR = 1.25

/** The thin progress bar never shrinks below this share of the track. */
internal const val MIN_BAR_FRACTION = 0.03

/** One housekeeping shift of today (`shifts` is written by the Shifts phase). */
internal data class ShiftRef(val staffId: String, val staffName: String?, val status: String)

/** One housekeeper's line on the card. */
data class WorkloadRow(
    val housekeeperId: String,
    val name: String,
    val rooms: Int,
    val load: Double,
    val heavier: Boolean,
    val barFraction: Float
) {
    val summary: String get() = "${roomCountText(rooms)} · ${creditsText(load)}"
}

internal fun roomCountText(rooms: Int): String = if (rooms == 1) "1 room" else "$rooms rooms"

internal fun creditsText(load: Double): String = String.format(Locale.US, "%.1f credits", load)

/** The zone named in `settings/hotel.timezone`; Africa/Lagos when it is missing or not a real zone. */
internal fun zoneOfName(name: String?): ZoneId =
    try {
        ZoneId.of(name?.takeIf { it.isNotBlank() } ?: DEFAULT_BUSINESS_ZONE)
    } catch (e: Exception) {
        ZoneId.of(DEFAULT_BUSINESS_ZONE)
    }

/** Credits of one task: its stored `weight`, or the computed one when the stored weight is missing. */
internal fun taskCredits(task: HousekeepingTask): Double =
    if (task.weight > 0.0) task.weight else HousekeepingBalance.computeTaskWeight(task.type.key, task.priority.key)

/** What the card shows. The card is hidden while [loading] and when it has no rows and no unassigned tasks. */
data class WorkloadCardState(
    val loading: Boolean = true,
    val rows: List<WorkloadRow> = emptyList(),
    val onShift: Int = 0,
    val unassigned: Int = 0
) {
    val visible: Boolean get() = !loading && (rows.isNotEmpty() || unassigned > 0)
    val showEmptyRoster: Boolean get() = rows.isEmpty() && unassigned > 0
    val onShiftText: String get() = "$onShift on shift today"
    val unassignedText: String get() = "$unassigned unassigned"
}

/**
 * Today's open work per housekeeper. Tasks count while `dayKey == todayKey` and the status is pending or in progress;
 * people on today's roster (shifts with status "scheduled") start at 0; load = sum of credits; heaviest first.
 */
internal fun computeWorkload(todayKey: String, tasks: List<HousekeepingTask>, shifts: List<ShiftRef>): WorkloadCardState {
    val open = tasks.filter {
        !it.isDeleted && it.dayKey == todayKey && (it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS)
    }

    val names = linkedMapOf<String, String>()
    val rooms = linkedMapOf<String, Int>()
    val loads = linkedMapOf<String, Double>()

    val onDuty = shifts.filter { it.status == "scheduled" && it.staffId.isNotBlank() }.distinctBy { it.staffId }
    onDuty.forEach {
        names[it.staffId] = it.staffName?.takeIf { n -> n.isNotBlank() } ?: "Unknown"
        rooms[it.staffId] = 0
        loads[it.staffId] = 0.0
    }

    var unassigned = 0
    open.forEach { task ->
        val owner = task.assignedTo
        if (owner.isNullOrBlank()) {
            unassigned++
        } else {
            if (names[owner].isNullOrBlank() || names[owner] == "Unknown") {
                names[owner] = task.assignedToName?.takeIf { it.isNotBlank() } ?: names[owner] ?: "Unknown"
            }
            rooms[owner] = (rooms[owner] ?: 0) + 1
            loads[owner] = (loads[owner] ?: 0.0) + taskCredits(task)
        }
    }

    val ordered = loads.keys.sortedWith(compareByDescending<String> { loads.getValue(it) }.thenBy { names[it].orEmpty() })
    val average = if (ordered.isEmpty()) 0.0 else ordered.sumOf { loads.getValue(it) } / ordered.size
    val maxLoad = ordered.maxOfOrNull { loads.getValue(it) } ?: 0.0

    val rows = ordered.map { id ->
        val load = loads.getValue(id)
        val fraction = if (maxLoad > 0.0) (load / maxLoad).coerceAtLeast(MIN_BAR_FRACTION) else MIN_BAR_FRACTION
        WorkloadRow(
            housekeeperId = id,
            name = names[id] ?: "Unknown",
            rooms = rooms.getValue(id),
            load = load,
            heavier = load > average * HEAVIER_FACTOR,
            barFraction = fraction.toFloat()
        )
    }
    return WorkloadCardState(loading = false, rows = rows, onShift = onDuty.size, unassigned = unassigned)
}

internal fun buildWorkloadState(
    todayKey: String,
    tasks: Resource<List<HousekeepingTask>>,
    shifts: Resource<List<ShiftRef>>
): WorkloadCardState = when {
    tasks is Resource.Loading || shifts is Resource.Loading -> WorkloadCardState(loading = true)
    // The card is a nice-to-have: if today's tasks cannot be read it simply stays hidden.
    tasks is Resource.Error -> WorkloadCardState(loading = false)
    else -> computeWorkload(
        todayKey,
        (tasks as Resource.Success).data,
        (shifts as? Resource.Success)?.data.orEmpty()
    )
}

// ---- Live data -------------------------------------------------------------------------------

/** Where the card reads from. The real one is Firestore; tests use an in-memory one. */
internal interface WorkloadBalanceSource {
    /** The business time zone name from `settings/hotel`; emits null when it is missing. Nothing is emitted while loading. */
    fun observeTimezone(): Flow<String?>

    /** Tasks of one calendar day (the caller keeps the open ones). */
    fun observeDayTasks(dayKey: String): Flow<Resource<List<HousekeepingTask>>>

    /** Housekeeping shifts of one calendar day. */
    fun observeShifts(dayKey: String): Flow<Resource<List<ShiftRef>>>
}

/** Only the field the card needs from `settings/hotel`. */
internal data class WorkloadHotelSettings(val timezone: String? = null)

internal class FirestoreWorkloadSource(private val firestore: BusinessFirestore) : WorkloadBalanceSource {
    override fun observeTimezone(): Flow<String?> =
        firestore.observeDoc("settings", "hotel", WorkloadHotelSettings::class.java)
            .filter { it !is Resource.Loading }
            // A missing document, a missing field or a read error all mean "use the default zone" (null).
            .map { result -> (result as? Resource.Success)?.data?.timezone }

    override fun observeDayTasks(dayKey: String): Flow<Resource<List<HousekeepingTask>>> =
        observeRawDocs(
            firestore.collection("housekeeping_tasks").whereEqualTo("dayKey", dayKey),
            "We couldn't load today's tasks."
        ).map { result ->
            when (result) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> result
                is Resource.Success -> Resource.Success(result.data.map { (id, data) -> parseHousekeepingTask(id, data) })
            }
        }

    override fun observeShifts(dayKey: String): Flow<Resource<List<ShiftRef>>> =
        observeRawDocs(
            firestore.collection("shifts").whereEqualTo("date", dayKey),
            "We couldn't load today's shifts."
        ).map { result ->
            when (result) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> result
                is Resource.Success -> Resource.Success(
                    result.data.mapNotNull { (_, data) ->
                        if (data["role"] != "housekeeping") null
                        else ShiftRef(
                            staffId = data["staffId"] as? String ?: "",
                            staffName = data["staffName"] as? String,
                            status = data["status"] as? String ?: ""
                        )
                    }
                )
            }
        }
}

// ---- ViewModel -------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkloadBalanceViewModel internal constructor(
    private val source: WorkloadBalanceSource,
    private val clock: () -> Instant
) : ViewModel() {

    @Inject
    constructor(firestore: BusinessFirestore) : this(FirestoreWorkloadSource(firestore), { Instant.now() })

    /** Today's key in the business time zone, then the live workload for that day. */
    val state: StateFlow<WorkloadCardState> = source.observeTimezone()
        .map { dateKeyInZone(clock(), zoneOfName(it)) }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            combine(source.observeDayTasks(key), source.observeShifts(key)) { tasks, shifts -> buildWorkloadState(key, tasks, shifts) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkloadCardState())
}

// ---- UI --------------------------------------------------------------------------------------

private val INDIGO = Color(0xFF6366F1)
private val ORANGE = Color(0xFFF97316)

/** Today's Workload Balance. Hidden while loading and when there is nothing to show. */
@Composable
fun WorkloadBalanceCard(modifier: Modifier = Modifier) {
    WorkloadBalanceCardContent(hiltViewModel(), modifier)
}

@Composable
internal fun WorkloadBalanceCardContent(vm: WorkloadBalanceViewModel, modifier: Modifier = Modifier) {
    val state = vm.state.collectAsStateWithLifecycle().value
    if (!state.visible) return

    NbmsCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Balance, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(WORKLOAD_TITLE, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            }

            if (state.showEmptyRoster) {
                Text(
                    WORKLOAD_EMPTY_ROSTER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.rows.forEach { row -> WorkloadRowView(row) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.onShiftText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.unassigned > 0) {
                    Text(
                        state.unassignedText,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.nbms.destructive
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkloadRowView(row: WorkloadRow) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.heavier) NbmsBadge("heavier day", BadgeTone.Destructive)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(row.barFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (row.heavier) ORANGE else INDIGO)
            )
        }
    }
}
