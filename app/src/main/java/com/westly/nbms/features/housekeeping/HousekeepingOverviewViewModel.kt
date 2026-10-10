package com.westly.nbms.features.housekeeping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- User-facing texts (Part 23B-1, section 2.5) ---------------------------------------------

internal const val MSG_ROOMS_LOAD_FAILED = "We couldn't load room status."
internal const val MSG_OVERVIEW_GENERIC = "Something went wrong. Please try again."
internal const val OVERVIEW_TITLE = "Housekeeping Overview"
internal const val OVERVIEW_ALL_CLEAN_TITLE = "All rooms are clean!"
internal const val OVERVIEW_ALL_CLEAN_MESSAGE = "No rooms currently need cleaning."

// ---- Pure rules (unit-tested) ----------------------------------------------------------------

/** "1 room needs cleaning today across all housekeepers" / "3 rooms need cleaning today across all housekeepers". */
internal fun overviewSubtitle(needsCleaning: Int): String =
    if (needsCleaning == 1) "1 room needs cleaning today across all housekeepers"
    else "$needsCleaning rooms need cleaning today across all housekeepers"

/** Text of the red banner about pending tasks that nobody owns. */
internal fun unassignedBannerText(count: Int): String =
    if (count == 1) "1 auto-generated task has no assigned housekeeper yet (no active room assignment found)."
    else "$count auto-generated tasks have no assigned housekeeper yet (no active room assignment found)."

/** "Room 101" style second line of a room card: "{type} · Floor {f}". */
internal fun overviewRoomSubtitle(room: Room): String = "${room.type} · Floor ${room.floor}"

/** Super Admin, Manager and Operations Manager may read the Overview and use its two actions. */
internal fun canActOnOverview(role: Role): Boolean =
    role == Role.SUPER_ADMIN || role == Role.MANAGER || role == Role.OPERATIONS_MANAGER

/** Rooms in number order: numeric numbers first by value, anything else after them by text. */
private val ROOM_NUMBER_ORDER: Comparator<Room> =
    compareBy<Room>({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number })

/** A pending task that nobody owns (soft-deleted ones never count). */
internal fun isUnassignedPending(task: HousekeepingTask): Boolean =
    !task.isDeleted && task.status == TaskStatus.PENDING && task.assignedTo.isNullOrBlank()

/** Everything the Overview screen shows, worked out from the live rooms and pending tasks. */
data class HousekeepingOverviewState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val errorDetail: String? = null,
    val cleaningRooms: List<Room> = emptyList(),
    val needsCleaning: Int = 0,
    val maintenance: Int = 0,
    val available: Int = 0,
    val unassignedPending: Int = 0
) {
    val subtitle: String get() = overviewSubtitle(needsCleaning)
    val showUnassignedBanner: Boolean get() = unassignedPending > 0
    val bannerText: String get() = unassignedBannerText(unassignedPending)
}

internal fun buildOverviewState(
    rooms: Resource<List<Room>>,
    pendingTasks: Resource<List<HousekeepingTask>>
): HousekeepingOverviewState {
    val unassigned = ((pendingTasks as? Resource.Success)?.data.orEmpty()).count(::isUnassignedPending)
    return when (rooms) {
        is Resource.Loading -> HousekeepingOverviewState(loading = true)
        is Resource.Error -> HousekeepingOverviewState(
            loading = false,
            failed = true,
            errorDetail = (rooms.cause?.message ?: rooms.message).takeIf { it.isNotBlank() && it != MSG_ROOMS_LOAD_FAILED },
            unassignedPending = unassigned
        )
        is Resource.Success -> {
            val live = rooms.data.filter { !it.isDeleted }
            val cleaning = live.filter { it.status == RoomStatus.CLEANING.key }.sortedWith(ROOM_NUMBER_ORDER)
            HousekeepingOverviewState(
                loading = false,
                cleaningRooms = cleaning,
                needsCleaning = cleaning.size,
                maintenance = live.count { it.status == RoomStatus.MAINTENANCE.key },
                available = live.count { it.status == RoomStatus.AVAILABLE.key },
                unassignedPending = unassigned
            )
        }
    }
}

// ---- Live data -------------------------------------------------------------------------------

/** Where the Overview reads from. The real one is Firestore; tests use an in-memory one. */
internal interface HousekeepingOverviewSource {
    fun observeRooms(): Flow<Resource<List<Room>>>

    /** Tasks with `status == "pending"` (the ViewModel keeps only the ones nobody owns). */
    fun observePendingTasks(): Flow<Resource<List<HousekeepingTask>>>
}

/**
 * Live documents of one Firestore query as (id, data) pairs, with server times shown at once.
 * Emits [Resource.Loading] first and [Resource.Error] with [failMessage] when the listener fails.
 */
internal fun observeRawDocs(query: Query, failMessage: String): Flow<Resource<List<Pair<String, Map<String, Any?>>>>> = callbackFlow {
    trySend(Resource.Loading)
    val registration = try {
        query.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
            if (error != null) {
                trySend(Resource.Error(failMessage, error))
            } else if (snapshot != null) {
                try {
                    val docs = snapshot.documents.map { doc: DocumentSnapshot ->
                        doc.id to (doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                    }
                    trySend(Resource.Success(docs))
                } catch (e: Exception) {
                    trySend(Resource.Error(failMessage, e))
                }
            }
        }
    } catch (e: Exception) {
        trySend(Resource.Error(failMessage, e))
        null
    }
    awaitClose { registration?.remove() }
}

internal class FirestoreOverviewSource(private val firestore: BusinessFirestore) : HousekeepingOverviewSource {
    override fun observeRooms(): Flow<Resource<List<Room>>> = firestore.observeList("rooms", Room::class.java)

    override fun observePendingTasks(): Flow<Resource<List<HousekeepingTask>>> =
        observeRawDocs(
            firestore.collection("housekeeping_tasks").whereEqualTo("status", TaskStatus.PENDING.key),
            "We couldn't load housekeeping tasks."
        ).map { result ->
            when (result) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> result
                is Resource.Success -> Resource.Success(result.data.map { (id, data) -> parseHousekeepingTask(id, data) })
            }
        }
}

// ---- ViewModel -------------------------------------------------------------------------------

/**
 * Management view of the `housekeeping` route (Super Admin, Manager, Operations Manager): stats, rooms awaiting
 * cleaning, the unassigned-tasks banner, and the two room actions Mark Clean and Maintenance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HousekeepingOverviewViewModel internal constructor(
    private val source: HousekeepingOverviewSource,
    private val service: HousekeepingService,
    private val roomLogic: RoomLogic,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val toast: ToastController,
    private val session: SessionManager,
    private val navigator: ShellNavigator,
    actionSet: Set<HousekeepingOverviewAction>
) : ViewModel() {

    @Inject
    constructor(
        firestore: BusinessFirestore,
        service: HousekeepingService,
        roomLogic: RoomLogic,
        audit: AuditLogger,
        notifier: Notifier,
        toast: ToastController,
        session: SessionManager,
        navigator: ShellNavigator,
        actionSet: Set<@JvmSuppressWildcards HousekeepingOverviewAction>
    ) : this(FirestoreOverviewSource(firestore), service, roomLogic, audit, notifier, toast, session, navigator, actionSet)

    /** Extra buttons bound by other phases (Phase 24); the screen shows them to the left of Room Assignments. */
    val actions: List<HousekeepingOverviewAction> = actionSet.toList()

    private val retryTick = MutableStateFlow(0)

    /** Live rooms and pending tasks, worked out for the screen. */
    val state: StateFlow<HousekeepingOverviewState> = retryTick
        .flatMapLatest { combine(source.observeRooms(), source.observePendingTasks(), ::buildOverviewState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HousekeepingOverviewState())

    private val _busyRoomIds = MutableStateFlow<Set<String>>(emptySet())

    /** Rooms with a Mark Clean or Maintenance action running right now. */
    val busyRoomIds: StateFlow<Set<String>> = _busyRoomIds.asStateFlow()

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Opens Room Assignments (the outlined button and the banner's Assign Now). */
    fun openAssignments() {
        navigator.open(HousekeepingAssignmentsFeature.ROUTE_ROOM_ASSIGNMENTS)
    }

    private fun signedIn(): SessionState.SignedIn? = session.state.value as? SessionState.SignedIn

    private fun actor(): HousekeepingActor? = signedIn()?.let { HousekeepingActor(it.user.uid, it.user.name) }

    /** Room id to the cleaning task already created for it while a Mark Clean has not finished yet. */
    private val markCleanTaskIds = mutableMapOf<String, String>()

    /** Management Mark Clean: a `cleaning`/`medium` task assigned to me is created and completed at once. */
    fun markClean(room: Room) {
        val me = signedIn()
        if (me == null || !canActOnOverview(me.user.role)) {
            toast.show(MSG_OVERVIEW_GENERIC, ToastType.Error, "Failed to mark clean")
            return
        }
        if (room.id in _busyRoomIds.value) return
        val actor = HousekeepingActor(me.user.uid, me.user.name)
        _busyRoomIds.update { it + room.id }
        viewModelScope.launch {
            try {
                // A retry after a failed finish reuses the task already created, so no duplicate task is left behind.
                val taskId = markCleanTaskIds[room.id] ?: service.createManualTask(
                    roomId = room.id,
                    roomNumber = room.number,
                    type = TaskType.CLEANING,
                    priority = TaskPriority.MEDIUM,
                    instructions = null,
                    assignedTo = actor.id,
                    assignedToName = actor.name,
                    actor = actor
                ).also { markCleanTaskIds[room.id] = it }
                service.completeTask(taskId, HousekeepingRoomRef(room.id, room.number, room.type), actor)
                markCleanTaskIds.remove(room.id)
                val (title, message) = markCleanToast(room)
                toast.show(message, ToastType.Success, title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_OVERVIEW_GENERIC, ToastType.Error, "Failed to mark clean")
            } finally {
                _busyRoomIds.update { it - room.id }
            }
        }
    }

    /**
     * Management maintenance flag: the room goes to Maintenance, the audit log keeps the old status too, and the
     * operations team is told. Unlike the housekeeper's version, nothing ends the person's PIN session.
     */
    fun flagMaintenance(room: Room) {
        val me = signedIn()
        if (me == null || !canActOnOverview(me.user.role)) {
            toast.show(MSG_OVERVIEW_GENERIC, ToastType.Error, "Failed to flag maintenance")
            return
        }
        if (room.id in _busyRoomIds.value) return
        val actor = HousekeepingActor(me.user.uid, me.user.name)
        val oldStatus = room.status
        _busyRoomIds.update { it + room.id }
        viewModelScope.launch {
            try {
                roomLogic.updateRoomStatus(room.id, RoomStatus.MAINTENANCE)
                audit.log(
                    "room_maintenance", "rooms", room.id,
                    mapOf("status" to oldStatus),
                    mapOf("status" to RoomStatus.MAINTENANCE.key, "roomNumber" to room.number)
                )
                try {
                    notifier.notifyMaintenanceRequest("Maintenance issue", "Room ${room.number}", actor.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The room is already flagged; a failed alert must not turn that into an error.
                }
                toast.show("Room ${room.number} has been flagged for maintenance.", ToastType.Success, "Maintenance Requested")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_OVERVIEW_GENERIC, ToastType.Error, "Failed to flag maintenance")
            } finally {
                _busyRoomIds.update { it - room.id }
            }
        }
    }
}
