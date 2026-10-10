package com.westly.nbms.features.housekeeping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomDisplayStatus
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// ---- User-facing texts (Part 23B-2, sections 2.4 and 2.8) --------------------------------------

internal const val MSG_MY_LOAD_FAILED = "We couldn't load your housekeeping dashboard."
internal const val MSG_MY_GENERIC = "Something went wrong. Please try again."
internal const val MY_TITLE = "My Housekeeping"
internal const val MY_QUEUE_EMPTY_TITLE = "Your queue is empty!"
internal const val MY_QUEUE_EMPTY_MESSAGE = "Nothing pending right now."
internal const val MY_ROOMS_EMPTY_MESSAGE = "No rooms are assigned to you yet. Ask your Operations Manager to set up a room assignment."
internal const val MY_HISTORY_LOADING = "Loading…"
internal const val MY_HISTORY_EMPTY = "No completed tasks logged yet."
internal const val MY_PIN_ENDING_TEXT = "Ending session for security…"

/** Shared-device (PIN) sessions end 2.5 s after Complete, Mark Clean or the maintenance flag. */
internal const val MY_PIN_SIGN_OUT_DELAY_MS = 2_500L
internal const val MY_ROOM_ID_CHUNK_SIZE = 10
internal const val MY_HISTORY_LIMIT = 5

// ---- Pure rules (unit-tested) ------------------------------------------------------------------

/** urgent, high, medium, low. */
internal fun myPriorityRank(priority: TaskPriority): Int = when (priority) {
    TaskPriority.URGENT -> 0
    TaskPriority.HIGH -> 1
    TaskPriority.MEDIUM -> 2
    TaskPriority.LOW -> 3
}

/** My queue: tasks I own that are pending or in progress, urgent first (earlier schedule first inside one priority). */
internal fun buildMyQueue(tasks: List<HousekeepingTask>, uid: String): List<HousekeepingTask> =
    tasks
        .filter { !it.isDeleted && it.assignedTo == uid && (it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS) }
        .sortedWith(compareBy<HousekeepingTask> { myPriorityRank(it.priority) }.thenBy { it.scheduledFor ?: Instant.MAX })

/** Room ids of my active assignments, each once. */
internal fun myActiveRoomIds(assignments: List<RoomAssignment>, uid: String): List<String> =
    assignments
        .filter { it.housekeeperId == uid && it.status == "active" && it.roomId.isNotBlank() }
        .map { it.roomId }
        .distinct()

/** Firestore `whereIn` takes at most 10 values, so room ids are fetched in groups of 10. */
internal fun chunkRoomIds(ids: List<String>): List<List<String>> = ids.distinct().chunked(MY_ROOM_ID_CHUNK_SIZE)

private fun countWord(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

/** "2 tasks in your queue · 1 room assigned to you". */
internal fun myHeaderSubtitle(tasks: Int, rooms: Int): String =
    "${countWord(tasks, "task")} in your queue · ${countWord(rooms, "room")} assigned to you"

internal fun myQueueTabLabel(n: Int) = "My Queue ($n)"
internal fun myRoomsTabLabel(m: Int) = "My Rooms ($m)"

/** "Mar 5, 10:30 AM" in the business time zone (falls back to the phone's zone for an unknown id). */
internal fun myDateTime(at: Instant?, zoneId: String): String {
    if (at == null) return ""
    val zone = try { ZoneId.of(zoneId) } catch (_: Exception) { ZoneId.systemDefault() }
    return DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(zone).format(at)
}

/** One cleaning-history row: "{type label} — {Mar 5, 10:30 AM} · {completedByName}". */
internal fun historyRowText(task: HousekeepingTask, zoneId: String): String {
    val base = "${task.type.label} — ${myDateTime(task.completedAt, zoneId)}"
    val who = task.completedByName?.takeIf { it.isNotBlank() }
    return if (who != null) "$base · $who" else base
}

/** Title and message of the Mark Clean toast: a guest still checked in keeps the room Occupied. */
internal fun markCleanToast(room: Room): Pair<String, String> =
    if (!room.currentBookingId.isNullOrBlank())
        "Room Marked Clean" to "Room ${room.number} is clean. Guest is still checked in, so it stays Occupied."
    else
        "Room Marked Clean" to "Room ${room.number} is now available."

private val MY_ROOM_ORDER: Comparator<Room> = compareBy<Room>({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number })

/** Everything the My Housekeeping screen shows. */
data class MyHousekeepingState(
    val queueLoading: Boolean = true,
    val roomsLoading: Boolean = true,
    val failed: Boolean = false,
    val errorDetail: String? = null,
    val queue: List<HousekeepingTask> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val assignedRoomCount: Int = 0
) {
    val subtitle: String get() = myHeaderSubtitle(queue.size, assignedRoomCount)

    /** The most urgent queue task of a room, for the "priority task pending" badge. */
    fun pendingTaskFor(roomId: String): HousekeepingTask? = queue.firstOrNull { it.roomId == roomId }
}

/** What the cleaning history of one room shows. */
sealed interface MyHistoryState {
    data object Loading : MyHistoryState
    data class Loaded(val rows: List<HousekeepingTask>) : MyHistoryState
    data class Failed(val message: String) : MyHistoryState
}

internal fun buildMyState(
    uid: String,
    queue: Resource<List<HousekeepingTask>>,
    assignments: Resource<List<RoomAssignment>>,
    rooms: Resource<List<Room>>
): MyHousekeepingState {
    val firstError = listOf(queue, assignments, rooms).filterIsInstance<Resource.Error>().firstOrNull()
    if (firstError != null) {
        return MyHousekeepingState(
            queueLoading = false,
            roomsLoading = false,
            failed = true,
            errorDetail = (firstError.cause?.message ?: firstError.message).takeIf { it.isNotBlank() && it != MSG_MY_LOAD_FAILED }
        )
    }
    val myQueue = (queue as? Resource.Success)?.data?.let { buildMyQueue(it, uid) }.orEmpty()
    val assigned = (assignments as? Resource.Success)?.data?.let { myActiveRoomIds(it, uid) }.orEmpty()
    val myRooms = (rooms as? Resource.Success)?.data.orEmpty().filter { !it.isDeleted }.sortedWith(MY_ROOM_ORDER)
    return MyHousekeepingState(
        queueLoading = queue is Resource.Loading,
        roomsLoading = assignments is Resource.Loading || rooms is Resource.Loading,
        queue = myQueue,
        rooms = myRooms,
        assignedRoomCount = assigned.size
    )
}

/**
 * Live rooms for a list of ids, one `whereIn` listener per group of 10 ids ([observeChunk] makes one listener).
 * Loading until every group has answered; the first error wins.
 */
internal fun observeRoomsInChunks(
    ids: List<String>,
    observeChunk: (List<String>) -> Flow<Resource<List<Room>>>
): Flow<Resource<List<Room>>> {
    val chunks = chunkRoomIds(ids)
    if (chunks.isEmpty()) return flowOf(Resource.Success(emptyList()))
    return combine(chunks.map(observeChunk)) { results: Array<Resource<List<Room>>> ->
        val error = results.filterIsInstance<Resource.Error>().firstOrNull()
        when {
            error != null -> error
            results.any { it is Resource.Loading } -> Resource.Loading
            else -> Resource.Success(
                results.flatMap { r -> (r as? Resource.Success<List<Room>>)?.data.orEmpty() }
            )
        }
    }
}

// ---- Live data ---------------------------------------------------------------------------------

/** Where My Housekeeping reads from. The real one is Firestore; tests use an in-memory one. */
internal interface MyHousekeepingSource {
    /** Tasks with `assignedTo == uid` and status pending or in progress. */
    fun observeQueue(uid: String): Flow<Resource<List<HousekeepingTask>>>

    /** `room_assignments` with `housekeeperId == uid` and status active. */
    fun observeAssignments(uid: String): Flow<Resource<List<RoomAssignment>>>

    /** Only these rooms, fetched by id in groups of 10; never the whole rooms collection. */
    fun observeRooms(ids: List<String>): Flow<Resource<List<Room>>>

    /** The last [limit] completed tasks of one room, newest first. Called only when the room is expanded. */
    suspend fun recentCompleted(roomId: String, limit: Int): List<HousekeepingTask>
}

internal class FirestoreMyHousekeepingSource(private val firestore: BusinessFirestore) : MyHousekeepingSource {

    override fun observeQueue(uid: String): Flow<Resource<List<HousekeepingTask>>> =
        observeRawDocs(
            firestore.collection("housekeeping_tasks")
                .whereEqualTo("assignedTo", uid)
                .whereIn("status", listOf(TaskStatus.PENDING.key, TaskStatus.IN_PROGRESS.key)),
            MSG_MY_LOAD_FAILED
        ).map { result ->
            when (result) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> result
                is Resource.Success -> Resource.Success(result.data.map { (id, data) -> parseHousekeepingTask(id, data) })
            }
        }

    override fun observeAssignments(uid: String): Flow<Resource<List<RoomAssignment>>> =
        observeRawDocs(
            firestore.collection("room_assignments")
                .whereEqualTo("housekeeperId", uid)
                .whereEqualTo("status", "active"),
            MSG_MY_LOAD_FAILED
        ).map { result ->
            when (result) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> result
                is Resource.Success -> Resource.Success(result.data.map { (id, data) -> parseRoomAssignment(id, data) })
            }
        }

    override fun observeRooms(ids: List<String>): Flow<Resource<List<Room>>> = observeRoomsInChunks(ids, ::observeRoomChunk)

    private fun observeRoomChunk(ids: List<String>): Flow<Resource<List<Room>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection("rooms").whereIn(FieldPath.documentId(), ids)
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        trySend(Resource.Error(MSG_MY_LOAD_FAILED, error))
                    } else if (snapshot != null) {
                        try {
                            trySend(Resource.Success(snapshot.documents.mapNotNull { it.toObject(Room::class.java) }))
                        } catch (e: Exception) {
                            trySend(Resource.Error(MSG_MY_LOAD_FAILED, e))
                        }
                    }
                }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_MY_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override suspend fun recentCompleted(roomId: String, limit: Int): List<HousekeepingTask> =
        firestore.collection("housekeeping_tasks")
            .whereEqualTo("roomId", roomId)
            .whereEqualTo("status", TaskStatus.COMPLETED.key)
            .orderBy("completedAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .await()
            .documents
            .map { doc: DocumentSnapshot ->
                parseHousekeepingTask(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
            }
            .filter { !it.isDeleted }
}

// ---- ViewModel ---------------------------------------------------------------------------------

private data class AssignedRooms(val assignments: Resource<List<RoomAssignment>>, val rooms: Resource<List<Room>>)

/**
 * The Housekeeping role's own screen: my queue, my rooms and their cleaning history. Start and Complete go through
 * [HousekeepingService]; Mark Clean and the maintenance flag follow section 2.4. After Complete, Mark Clean and the
 * maintenance flag a shared-device (PIN) session ends by itself (section 2.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyHousekeepingViewModel internal constructor(
    private val source: MyHousekeepingSource,
    private val service: HousekeepingService,
    private val roomLogic: RoomLogic,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val toast: ToastController,
    private val session: SessionManager
) : ViewModel() {

    @Inject
    constructor(
        firestore: BusinessFirestore,
        service: HousekeepingService,
        roomLogic: RoomLogic,
        audit: AuditLogger,
        notifier: Notifier,
        toast: ToastController,
        session: SessionManager
    ) : this(FirestoreMyHousekeepingSource(firestore), service, roomLogic, audit, notifier, toast, session)

    private val retryTick = MutableStateFlow(0)

    /** Live queue, assignments and their rooms, worked out for the screen. */
    val state: StateFlow<MyHousekeepingState> = retryTick
        .flatMapLatest {
            val uid = signedIn()?.user?.uid
            if (uid == null) {
                flowOf(MyHousekeepingState(queueLoading = false, roomsLoading = false, failed = true))
            } else {
                val roomsFlow: Flow<AssignedRooms> = source.observeAssignments(uid).flatMapLatest { assignments ->
                    when (assignments) {
                        is Resource.Success ->
                            source.observeRooms(myActiveRoomIds(assignments.data, uid)).map { AssignedRooms(assignments, it) }
                        is Resource.Loading -> flowOf(AssignedRooms(assignments, Resource.Loading))
                        is Resource.Error -> flowOf(AssignedRooms(assignments, Resource.Success(emptyList())))
                    }
                }
                combine(source.observeQueue(uid), roomsFlow) { queue, assigned ->
                    buildMyState(uid, queue, assigned.assignments, assigned.rooms)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyHousekeepingState())

    private val _busyTaskIds = MutableStateFlow<Set<String>>(emptySet())

    /** Queue rows with Start or Complete running right now. */
    val busyTaskIds: StateFlow<Set<String>> = _busyTaskIds.asStateFlow()

    private val _busyRoomIds = MutableStateFlow<Set<String>>(emptySet())

    /** Rooms with Mark Clean or the maintenance flag running right now. */
    val busyRoomIds: StateFlow<Set<String>> = _busyRoomIds.asStateFlow()

    private val _expandedRoomIds = MutableStateFlow<Set<String>>(emptySet())

    /** Rooms whose "Cleaning history" section is open. */
    val expandedRoomIds: StateFlow<Set<String>> = _expandedRoomIds.asStateFlow()

    private val _history = MutableStateFlow<Map<String, MyHistoryState>>(emptyMap())

    /** Cleaning history of the open rooms. */
    val history: StateFlow<Map<String, MyHistoryState>> = _history.asStateFlow()

    private val _pinEnding = MutableStateFlow(false)

    /** True while the "Ending session for security…" screen is up. */
    val pinEnding: StateFlow<Boolean> = _pinEnding.asStateFlow()

    private val signOutScheduled = AtomicBoolean(false)

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun displayStatus(room: Room): RoomDisplayStatus = roomLogic.getRoomDisplayStatus(room)

    private fun signedIn(): SessionState.SignedIn? = session.state.value as? SessionState.SignedIn

    private fun toastError(e: Exception) {
        toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_MY_GENERIC, ToastType.Error, "Error")
    }

    // -- queue actions --

    fun start(task: HousekeepingTask) {
        val me = signedIn() ?: return
        if (task.id in _busyTaskIds.value) return
        _busyTaskIds.update { it + task.id }
        viewModelScope.launch {
            try {
                service.startTask(task.id, HousekeepingActor(me.user.uid, me.user.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastError(e)
            } finally {
                _busyTaskIds.update { it - task.id }
            }
        }
    }

    fun complete(task: HousekeepingTask) {
        val me = signedIn() ?: return
        if (task.id in _busyTaskIds.value) return
        _busyTaskIds.update { it + task.id }
        viewModelScope.launch {
            try {
                service.completeTask(
                    task.id,
                    HousekeepingRoomRef(task.roomId, task.roomNumber),
                    HousekeepingActor(me.user.uid, me.user.name)
                )
                toast.show("Room ${task.roomNumber} marked done.", ToastType.Success, "Task Complete")
                endPinSessionIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastError(e)
            } finally {
                _busyTaskIds.update { it - task.id }
            }
        }
    }

    // -- room actions --

    /** Room id to the cleaning task already created for it while a Mark Clean has not finished yet. */
    private val markCleanTaskIds = mutableMapOf<String, String>()

    /** A `cleaning`/`medium` task assigned to me is created and completed at once. */
    fun markClean(room: Room) {
        val me = signedIn() ?: return
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
                endPinSessionIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastError(e)
            } finally {
                _busyRoomIds.update { it - room.id }
            }
        }
    }

    /** Sends the room to Maintenance, writes the audit entry and tells the operations team. */
    fun flagMaintenance(room: Room) {
        val me = signedIn() ?: return
        if (room.id in _busyRoomIds.value) return
        val oldStatus = room.status
        _busyRoomIds.update { it + room.id }
        viewModelScope.launch {
            try {
                roomLogic.updateRoomStatus(room.id, RoomStatus.MAINTENANCE)
                audit.log(
                    "room_maintenance", "rooms", room.id,
                    mapOf("status" to oldStatus),
                    mapOf("status" to RoomStatus.MAINTENANCE.key)
                )
                try {
                    notifier.notifyMaintenanceRequest("Flagged by housekeeping", "Room ${room.number}", me.user.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The room is already flagged; a failed alert must not turn that into an error.
                }
                toast.show("Room ${room.number} flagged for maintenance.", ToastType.Success, "Room Sent to Maintenance")
                endPinSessionIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastError(e)
            } finally {
                _busyRoomIds.update { it - room.id }
            }
        }
    }

    // -- cleaning history (loaded only when a room is expanded) --

    fun toggleHistory(roomId: String) {
        if (roomId in _expandedRoomIds.value) {
            _expandedRoomIds.update { it - roomId }
            return
        }
        _expandedRoomIds.update { it + roomId }
        _history.update { it + (roomId to MyHistoryState.Loading) }
        viewModelScope.launch {
            val result = try {
                MyHistoryState.Loaded(source.recentCompleted(roomId, MY_HISTORY_LIMIT))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MyHistoryState.Failed(e.message?.takeIf { it.isNotBlank() } ?: MSG_MY_GENERIC)
            }
            // Ignore the answer if the section was closed meanwhile.
            if (roomId in _expandedRoomIds.value) _history.update { it + (roomId to result) }
        }
    }

    // -- shared-device sign-out (section 2.8) --

    /** Only a PIN user on the Housekeeping role's own screen is signed out; nobody else is ever affected. */
    private fun endPinSessionIfNeeded() {
        val me = signedIn() ?: return
        if (me.user.role != Role.HOUSEKEEPING || !me.user.usesPin) return
        if (!signOutScheduled.compareAndSet(false, true)) return
        _pinEnding.value = true
        viewModelScope.launch {
            delay(MY_PIN_SIGN_OUT_DELAY_MS)
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The person can still sign out by hand.
            } finally {
                _pinEnding.value = false
                signOutScheduled.set(false)
            }
        }
    }
}
