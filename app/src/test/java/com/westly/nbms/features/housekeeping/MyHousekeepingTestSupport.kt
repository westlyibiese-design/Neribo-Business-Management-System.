package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.rooms.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** A task read the same tolerant way Phase 24's documents are read. */
internal fun myTask(
    id: String,
    assignedTo: String? = "me",
    status: String = "pending",
    priority: String = "medium",
    roomId: String = "r-$id",
    roomNumber: String = id,
    type: String = "cleaning",
    scheduledFor: Instant? = null,
    completedAt: Instant? = null,
    completedByName: String? = null,
    isDeleted: Boolean = false
): HousekeepingTask = parseHousekeepingTask(
    id,
    buildMap<String, Any?> {
        put("roomId", roomId); put("roomNumber", roomNumber)
        put("status", status); put("type", type); put("priority", priority); put("isDeleted", isDeleted)
        if (assignedTo != null) put("assignedTo", assignedTo)
        if (scheduledFor != null) put("scheduledFor", com.google.firebase.Timestamp(scheduledFor.epochSecond, 0))
        if (completedAt != null) put("completedAt", com.google.firebase.Timestamp(completedAt.epochSecond, 0))
        if (completedByName != null) put("completedByName", completedByName)
    }
)

internal fun myAssignment(roomId: String, housekeeperId: String = "me", status: String = "active") =
    parseRoomAssignment(roomId, mapOf("housekeeperId" to housekeeperId, "status" to status, "roomNumber" to roomId))

internal fun myRoom(id: String, number: String = id, status: String = "cleaning", currentBookingId: String? = null) =
    Room(id = id, number = number, status = status, currentBookingId = currentBookingId)

/** In-memory data source: tests set the flows and read what was asked for. */
internal class MyHousekeepingFakeSource : MyHousekeepingSource {
    val queue = MutableStateFlow<Resource<List<HousekeepingTask>>>(Resource.Loading)
    val assignments = MutableStateFlow<Resource<List<RoomAssignment>>>(Resource.Loading)
    val rooms = MutableStateFlow<Resource<List<Room>>>(Resource.Loading)
    val history = mutableMapOf<String, List<HousekeepingTask>>()
    var historyError: Exception? = null

    val queueUids = mutableListOf<String>()
    val roomIdRequests = mutableListOf<List<String>>()
    val historyRequests = mutableListOf<Pair<String, Int>>()

    override fun observeQueue(uid: String): Flow<Resource<List<HousekeepingTask>>> { queueUids += uid; return queue }
    override fun observeAssignments(uid: String): Flow<Resource<List<RoomAssignment>>> = assignments
    override fun observeRooms(ids: List<String>): Flow<Resource<List<Room>>> { roomIdRequests += ids; return rooms }
    override suspend fun recentCompleted(roomId: String, limit: Int): List<HousekeepingTask> {
        historyRequests += roomId to limit
        historyError?.let { throw it }
        return history[roomId].orEmpty()
    }
}

/** Records the calls My Housekeeping makes; everything else fails loudly. */
internal class MyHousekeepingFakeService : HousekeepingService {
    data class Created(val roomId: String, val roomNumber: String, val type: TaskType, val priority: TaskPriority,
                       val assignedTo: String?, val actor: HousekeepingActor)
    data class Completed(val taskId: String, val room: HousekeepingRoomRef, val actor: HousekeepingActor)

    val order = mutableListOf<String>()
    val started = mutableListOf<Pair<String, HousekeepingActor>>()
    val created = mutableListOf<Created>()
    val completed = mutableListOf<Completed>()
    var failStart: Exception? = null
    var failComplete: Exception? = null

    override suspend fun startTask(taskId: String, actor: HousekeepingActor) {
        order += "start"
        failStart?.let { throw it }
        started += taskId to actor
    }

    override suspend fun createManualTask(
        roomId: String, roomNumber: String, type: TaskType, priority: TaskPriority, instructions: String?,
        assignedTo: String?, assignedToName: String?, scheduledFor: Instant, actor: HousekeepingActor
    ): String {
        order += "create"
        created += Created(roomId, roomNumber, type, priority, assignedTo, actor)
        return "task-${created.size}"
    }

    override suspend fun completeTask(taskId: String, room: HousekeepingRoomRef, actor: HousekeepingActor) {
        order += "complete"
        failComplete?.let { throw it }
        completed += Completed(taskId, room, actor)
    }

    override suspend fun reassignTask(taskId: String, toUserId: String, toUserName: String, actor: HousekeepingActor) = unused()
    override suspend fun skipTask(taskId: String, reason: String?, actor: HousekeepingActor) = unused()
    override suspend fun assignRooms(
        housekeeperId: String, housekeeperName: String, rooms: List<HousekeepingRoomRef>, startDate: java.time.LocalDate,
        endDate: java.time.LocalDate?, notes: String?, actor: HousekeepingActor
    ): String = unused()
    override suspend fun endAssignmentGroup(groupId: String, actor: HousekeepingActor) = unused()
    override suspend fun updateAssignmentGroup(groupId: String, update: AssignmentGroupUpdate, actor: HousekeepingActor) = unused()
    override suspend fun assignedRoomIds(housekeeperId: String): List<String> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by My Housekeeping")
}

/** A signed-in session whose role and PIN use can be chosen. */
internal class MyHousekeepingFakeSession(
    role: Role = Role.HOUSEKEEPING,
    usesPin: Boolean = true,
    uid: String = "me",
    name: String = "Hana Housekeeper"
) : SessionManager {
    var signOutCalls = 0
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser(uid, "biz1", role, name, "h@x.com", null, "active", usesPin),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() { signOutCalls++ }
    override suspend fun refresh() {}
}
