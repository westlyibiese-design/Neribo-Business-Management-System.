package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.feature.ShellNavigator
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

/** A room with only the fields the Overview looks at. */
internal fun overviewRoom(
    id: String, number: String, status: String = "available",
    type: String = "Standard Room", floor: String = "1", isDeleted: Boolean = false
) = Room(id = id, number = number, status = status, type = type, floor = floor, isDeleted = isDeleted)

/** A task read the same tolerant way Phase 24's documents are read. */
internal fun overviewTask(
    id: String,
    status: String = "pending",
    assignedTo: String? = null,
    assignedToName: String? = null,
    dayKey: String = "2026-10-10",
    type: String = "cleaning",
    priority: String = "medium",
    weight: Double? = null,
    isDeleted: Boolean = false
): HousekeepingTask = parseHousekeepingTask(
    id,
    buildMap<String, Any?> {
        put("roomId", "r-$id"); put("roomNumber", id)
        put("status", status); put("type", type); put("priority", priority)
        put("dayKey", dayKey); put("isDeleted", isDeleted)
        if (assignedTo != null) put("assignedTo", assignedTo)
        if (assignedToName != null) put("assignedToName", assignedToName)
        if (weight != null) put("weight", weight)
    }
)

internal class FakeOverviewSource : HousekeepingOverviewSource {
    val rooms = MutableStateFlow<Resource<List<Room>>>(Resource.Loading)
    val tasks = MutableStateFlow<Resource<List<HousekeepingTask>>>(Resource.Success(emptyList()))
    override fun observeRooms(): Flow<Resource<List<Room>>> = rooms
    override fun observePendingTasks(): Flow<Resource<List<HousekeepingTask>>> = tasks
}

/** Records the calls the Overview makes; everything else is out of its reach and fails loudly. */
internal class FakeOverviewService : HousekeepingService {
    data class Created(
        val roomId: String, val roomNumber: String, val type: TaskType, val priority: TaskPriority,
        val assignedTo: String?, val assignedToName: String?, val actor: HousekeepingActor
    )
    data class Completed(val taskId: String, val room: HousekeepingRoomRef, val actor: HousekeepingActor)

    /** "create" and "complete" in the order they happened. */
    val order = mutableListOf<String>()
    val created = mutableListOf<Created>()
    val completed = mutableListOf<Completed>()
    var failCreate: Exception? = null
    var failComplete: Exception? = null

    override suspend fun createManualTask(
        roomId: String, roomNumber: String, type: TaskType, priority: TaskPriority, instructions: String?,
        assignedTo: String?, assignedToName: String?, scheduledFor: java.time.Instant, actor: HousekeepingActor
    ): String {
        order += "create"
        failCreate?.let { throw it }
        created += Created(roomId, roomNumber, type, priority, assignedTo, assignedToName, actor)
        return "task-${created.size}"
    }

    override suspend fun completeTask(taskId: String, room: HousekeepingRoomRef, actor: HousekeepingActor) {
        order += "complete"
        failComplete?.let { throw it }
        completed += Completed(taskId, room, actor)
    }

    override suspend fun reassignTask(taskId: String, toUserId: String, toUserName: String, actor: HousekeepingActor) = unused()
    override suspend fun startTask(taskId: String, actor: HousekeepingActor) = unused()
    override suspend fun skipTask(taskId: String, reason: String?, actor: HousekeepingActor) = unused()
    override suspend fun assignRooms(
        housekeeperId: String, housekeeperName: String, rooms: List<HousekeepingRoomRef>, startDate: java.time.LocalDate,
        endDate: java.time.LocalDate?, notes: String?, actor: HousekeepingActor
    ): String = unused()
    override suspend fun endAssignmentGroup(groupId: String, actor: HousekeepingActor) = unused()
    override suspend fun updateAssignmentGroup(groupId: String, update: AssignmentGroupUpdate, actor: HousekeepingActor) = unused()
    override suspend fun assignedRoomIds(housekeeperId: String): List<String> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the Overview")
}

/** Audit that keeps the old value too (23A's fake drops it). */
internal class OverviewFakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val documentId: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class OverviewFakeSession(role: Role = Role.MANAGER, uid: String = "u1", name: String = "Wale", timezone: String = "Africa/Lagos") : SessionManager {
    var signOutCalls = 0
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser(uid, "biz1", role, name, "w@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() { signOutCalls++ }
    override suspend fun refresh() {}
}

internal class OverviewFakeNavigator : ShellNavigator {
    val opened = mutableListOf<String>()
    override fun open(link: String) { opened += link }
}

internal class FakeWorkloadSource(timezoneName: String? = null) : WorkloadBalanceSource {
    val timezone = MutableStateFlow<String?>(timezoneName)
    val tasks = MutableStateFlow<Resource<List<HousekeepingTask>>>(Resource.Loading)
    val shifts = MutableStateFlow<Resource<List<ShiftRef>>>(Resource.Success(emptyList()))
    val requestedKeys = mutableListOf<String>()

    override fun observeTimezone(): Flow<String?> = timezone
    override fun observeDayTasks(dayKey: String): Flow<Resource<List<HousekeepingTask>>> {
        requestedKeys += dayKey
        return tasks
    }
    override fun observeShifts(dayKey: String): Flow<Resource<List<ShiftRef>>> = shifts
}
