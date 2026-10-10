package com.westly.nbms.features.housekeeping

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Cleanliness
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ---- Part interface (section 2.0) -----------------------------------------------------------

data class HousekeepingActor(val id: String, val name: String)
data class HousekeepingRoomRef(val id: String, val number: String, val type: String? = null)
data class AssignmentGroupUpdate(val startDate: java.time.LocalDate? = null, val changeEndDate: Boolean = false,
                                 val endDate: java.time.LocalDate? = null /* null with changeEndDate = ongoing */, val notes: String? = null)
/** Failure whose message is already the exact user-facing text (e.g. "Select at least one room to assign."). */
class HousekeepingException(message: String) : Exception(message)

interface HousekeepingService {                                            // @Singleton; behaviour exactly as section 2.7
    suspend fun createManualTask(roomId: String, roomNumber: String, type: TaskType, priority: TaskPriority, instructions: String? = null,
        assignedTo: String?, assignedToName: String?, scheduledFor: Instant = Instant.now(), actor: HousekeepingActor): String
    suspend fun reassignTask(taskId: String, toUserId: String, toUserName: String, actor: HousekeepingActor)
    suspend fun startTask(taskId: String, actor: HousekeepingActor)
    suspend fun completeTask(taskId: String, room: HousekeepingRoomRef, actor: HousekeepingActor)
    suspend fun skipTask(taskId: String, reason: String?, actor: HousekeepingActor)
    suspend fun assignRooms(housekeeperId: String, housekeeperName: String, rooms: List<HousekeepingRoomRef>, startDate: java.time.LocalDate,
        endDate: java.time.LocalDate?, notes: String?, actor: HousekeepingActor): String
    suspend fun endAssignmentGroup(groupId: String, actor: HousekeepingActor)
    suspend fun updateAssignmentGroup(groupId: String, update: AssignmentGroupUpdate, actor: HousekeepingActor)
    suspend fun assignedRoomIds(housekeeperId: String): List<String>
}

// ---- User-facing messages -------------------------------------------------------------------

internal const val MSG_SELECT_ROOMS_TO_ASSIGN = "Select at least one room to assign."
internal const val MSG_ASSIGNMENT_NOT_FOUND = "Assignment not found."
internal const val MSG_TASK_NOT_FOUND = "Task not found."
internal const val MSG_HOUSEKEEPING_TIMEOUT = "This took too long to save. Check your connection and try again."

private const val TAG = "Housekeeping"
internal const val HOUSEKEEPING_WRITE_TIMEOUT_MS = 20_000L

private const val TASKS = "housekeeping_tasks"
private const val GROUPS = "room_assignment_groups"
private const val MIRRORS = "room_assignments"
private const val ROOMS = "rooms"

// ---- Private helpers shared inside this feature ---------------------------------------------

/**
 * A calendar day picked in a dialog, stored as UTC midnight (`yyyy-MM-ddT00:00:00Z`) exactly like Westly,
 * never shifted by the device time zone.
 */
internal fun calendarDayInstant(day: LocalDate): Instant = day.atStartOfDay(ZoneOffset.UTC).toInstant()

private val DAY_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Business time zone from the signed-in session; Africa/Lagos when it is unknown or invalid. */
internal fun zoneOfSession(session: SessionManager): ZoneId {
    val name = (session.state.value as? SessionState.SignedIn)?.business?.timezone
    return try {
        ZoneId.of(name ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }
}

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object HousekeepingServerTime

/** One write of a batch. Time values inside are [Instant]s or [HousekeepingServerTime]. */
internal sealed interface HkWrite {
    data class Put(val collection: String, val id: String, val data: Map<String, Any?>) : HkWrite
    data class Patch(val collection: String, val id: String, val fields: Map<String, Any?>) : HkWrite
    data class Remove(val collection: String, val id: String) : HkWrite
}

/** The database calls the service needs. [FirestoreHousekeepingStore] is the real one; the unit tests use a fake. */
internal interface HousekeepingStore {
    /** A fresh document id in [collection] (nothing is written). */
    fun newId(collection: String): String

    /** The document's fields, or null when it does not exist. */
    suspend fun getDoc(collection: String, id: String): Map<String, Any?>?

    /** Creates a document with a new id and returns that id. */
    suspend fun addDoc(collection: String, data: Map<String, Any?>): String

    /** Updates ONLY [fields] of an existing document. */
    suspend fun updateDoc(collection: String, id: String, fields: Map<String, Any?>)

    /** Applies every write in ONE batch: all of them are saved together or none. */
    suspend fun commit(writes: List<HkWrite>)

    /** Room ids of `room_assignments` owned by [housekeeperId] with status "active". */
    suspend fun activeMirrorRoomIds(housekeeperId: String): List<String>
}

internal class FirestoreHousekeepingStore(private val firestore: BusinessFirestore) : HousekeepingStore {

    private fun toFirestore(value: Any?): Any? = when (value) {
        is HousekeepingServerTime -> FieldValue.serverTimestamp()
        is Instant -> Timestamp(value.epochSecond, value.nano)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toFirestore(v) }
        is List<*> -> value.map { toFirestore(it) }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun toFirestoreMap(map: Map<String, Any?>): Map<String, Any?> = toFirestore(map) as Map<String, Any?>

    /**
     * Gives the write [HOUSEKEEPING_WRITE_TIMEOUT_MS] to finish. Only a real timeout becomes the "took too long" error.
     * (A write's task result is null by design, so the old `withTimeoutOrNull(...) ?: throw` treated every successful
     * update or batch as a timeout.)
     */
    private suspend fun <T> withWriteTimeout(block: suspend () -> T): T =
        try {
            withTimeout(HOUSEKEEPING_WRITE_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw HousekeepingException(MSG_HOUSEKEEPING_TIMEOUT)
        }

    override fun newId(collection: String): String = firestore.collection(collection).document().id

    override suspend fun getDoc(collection: String, id: String): Map<String, Any?>? {
        val snapshot = firestore.doc(collection, id).get().await()
        return if (snapshot.exists()) (snapshot.data ?: emptyMap()) else null
    }

    override suspend fun addDoc(collection: String, data: Map<String, Any?>): String = withWriteTimeout {
        firestore.collection(collection).add(toFirestoreMap(data)).await().id
    }

    override suspend fun updateDoc(collection: String, id: String, fields: Map<String, Any?>) {
        withWriteTimeout { firestore.doc(collection, id).update(toFirestoreMap(fields)).await() }
    }

    override suspend fun commit(writes: List<HkWrite>) {
        if (writes.isEmpty()) return
        val batch = firestore.collection(MIRRORS).firestore.batch()
        for (write in writes) {
            when (write) {
                is HkWrite.Put -> batch.set(firestore.doc(write.collection, write.id), toFirestoreMap(write.data))
                is HkWrite.Patch -> batch.update(firestore.doc(write.collection, write.id), toFirestoreMap(write.fields))
                is HkWrite.Remove -> batch.delete(firestore.doc(write.collection, write.id))
            }
        }
        withWriteTimeout { batch.commit().await() }
    }

    override suspend fun activeMirrorRoomIds(housekeeperId: String): List<String> {
        val query: Query = firestore.collection(MIRRORS)
            .whereEqualTo("housekeeperId", housekeeperId)
            .whereEqualTo("status", "active")
        return query.get().await().documents.map { it.id }
    }
}

// ---- The service ----------------------------------------------------------------------------

/** Runs [block]; any failure except cancellation is only logged (audit and notifications never block the real work). */
private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
    }
}

/** Housekeeping task lifecycle and long-term room assignments (Westly `lib/housekeeping.ts`, client half). */
@Singleton
class HousekeepingServiceImpl internal constructor(
    private val store: HousekeepingStore,
    private val roomLogic: RoomLogic,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val zone: () -> ZoneId
) : HousekeepingService {

    @Inject
    constructor(
        firestore: BusinessFirestore,
        roomLogic: RoomLogic,
        audit: AuditLogger,
        notifier: Notifier,
        session: SessionManager
    ) : this(FirestoreHousekeepingStore(firestore), roomLogic, audit, notifier, { zoneOfSession(session) })

    // ---- Tasks --------------------------------------------------------------------------

    override suspend fun createManualTask(
        roomId: String, roomNumber: String, type: TaskType, priority: TaskPriority, instructions: String?,
        assignedTo: String?, assignedToName: String?, scheduledFor: Instant, actor: HousekeepingActor
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "roomId" to roomId,
            "roomNumber" to roomNumber,
            "type" to type.key,
            "status" to TaskStatus.PENDING.key,
            "priority" to priority.key,
            "instructions" to instructions,
            "assignedTo" to assignedTo,
            "assignedToName" to assignedToName,
            "assignedBy" to actor.id,
            "assignedByName" to actor.name,
            "scheduledFor" to scheduledFor,
            "source" to "manual",
            "bookingId" to null,
            "dayKey" to dateKeyInZone(scheduledFor, zone()),
            "weight" to HousekeepingBalance.computeTaskWeight(type.key, priority.key),
            "homeOwnerId" to null,
            "homeOwnerName" to null,
            "rebalanced" to false,
            "createdAt" to HousekeepingServerTime,
            "updatedAt" to HousekeepingServerTime,
            "startedAt" to null,
            "completedAt" to null,
            "completedBy" to null,
            "completedByName" to null,
            "isDeleted" to false
        )
        val id = store.addDoc(TASKS, payload)

        bestEffort("task audit") {
            audit.log(
                "housekeeping_task_created", TASKS, id, null,
                mapOf("roomNumber" to roomNumber, "type" to type.key, "priority" to priority.key, "assignedTo" to assignedTo)
            )
        }
        if (assignedTo != null) {
            bestEffort("task notification") {
                notifier.notifyHousekeepingTaskQueued(assignedTo, roomNumber, actor.name, priority.key, instructions)
            }
        }
        bestEffort("room cleanliness") {
            roomLogic.updateRoomCleanliness(
                roomId,
                if (type == TaskType.CHECKOUT_CLEANING) Cleanliness.CHECKOUT_CLEANING_DUE else Cleanliness.DAILY_CLEANING_DUE
            )
        }
        return id
    }

    override suspend fun reassignTask(taskId: String, toUserId: String, toUserName: String, actor: HousekeepingActor) {
        val before = store.getDoc(TASKS, taskId)
        store.updateDoc(
            TASKS, taskId,
            mapOf(
                "assignedTo" to toUserId,
                "assignedToName" to toUserName,
                "assignedBy" to actor.id,
                "assignedByName" to actor.name,
                "updatedAt" to HousekeepingServerTime
            )
        )
        bestEffort("task audit") {
            audit.log(
                "housekeeping_task_reassigned", TASKS, taskId,
                mapOf("assignedTo" to before?.get("assignedTo")),
                mapOf("assignedTo" to toUserId)
            )
        }
        bestEffort("task notification") {
            val task = parseHousekeepingTask(taskId, before ?: emptyMap())
            notifier.notifyHousekeepingTaskQueued(toUserId, task.roomNumber, actor.name, task.priority.key, task.instructions)
        }
    }

    override suspend fun startTask(taskId: String, actor: HousekeepingActor) {
        store.updateDoc(
            TASKS, taskId,
            mapOf("status" to TaskStatus.IN_PROGRESS.key, "startedAt" to HousekeepingServerTime, "updatedAt" to HousekeepingServerTime)
        )
        bestEffort("task audit") {
            audit.log("housekeeping_task_started", TASKS, taskId, null, mapOf("status" to TaskStatus.IN_PROGRESS.key))
        }
        bestEffort("room cleanliness") {
            val roomId = store.getDoc(TASKS, taskId)?.get("roomId") as? String
            if (roomId != null) roomLogic.updateRoomCleanliness(roomId, Cleanliness.CLEANING_IN_PROGRESS)
        }
    }

    override suspend fun completeTask(taskId: String, room: HousekeepingRoomRef, actor: HousekeepingActor) {
        // 1. The task itself.
        store.updateDoc(
            TASKS, taskId,
            mapOf(
                "status" to TaskStatus.COMPLETED.key,
                "completedAt" to HousekeepingServerTime,
                "completedBy" to actor.id,
                "completedByName" to actor.name,
                "updatedAt" to HousekeepingServerTime
            )
        )

        // 2. Re-read the room: a guest still in the room means occupancy is never touched.
        val stillOccupied = !(store.getDoc(ROOMS, room.id)?.get("currentBookingId") as? String).isNullOrEmpty()
        if (stillOccupied) {
            roomLogic.updateRoomCleanliness(room.id, Cleanliness.CLEAN)
        } else {
            // 3. Vacated room: Available, then Clean. If Available is refused, still mark it Clean and pass the error on.
            try {
                roomLogic.updateRoomStatus(room.id, RoomStatus.AVAILABLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                roomLogic.updateRoomCleanliness(room.id, Cleanliness.CLEAN)
                throw e
            }
            roomLogic.updateRoomCleanliness(room.id, Cleanliness.CLEAN, mapOf("cleaningReminderLastSentAt" to null))
        }

        // 4. Best-effort audit and notification.
        bestEffort("task audit") {
            audit.log(
                "housekeeping_task_completed", TASKS, taskId, null,
                mapOf("status" to TaskStatus.COMPLETED.key, "roomRemainedOccupied" to stillOccupied)
            )
        }
        bestEffort("task notification") { notifier.notifyHousekeepingDone(room.number, actor.name) }
    }

    override suspend fun skipTask(taskId: String, reason: String?, actor: HousekeepingActor) {
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        store.updateDoc(
            TASKS, taskId,
            mapOf("status" to TaskStatus.SKIPPED.key, "skipReason" to cleanReason, "updatedAt" to HousekeepingServerTime)
        )
        bestEffort("task audit") {
            audit.log("housekeeping_task_skipped", TASKS, taskId, null, mapOf("status" to TaskStatus.SKIPPED.key, "skipReason" to cleanReason))
        }
    }

    // ---- Room assignments ---------------------------------------------------------------

    override suspend fun assignRooms(
        housekeeperId: String, housekeeperName: String, rooms: List<HousekeepingRoomRef>, startDate: LocalDate,
        endDate: LocalDate?, notes: String?, actor: HousekeepingActor
    ): String {
        if (rooms.isEmpty()) throw HousekeepingException(MSG_SELECT_ROOMS_TO_ASSIGN)
        val selected = rooms.distinctBy { it.id }
        val selectedIds = selected.map { it.id }.toSet()

        // 1. Existing mirrors of the selected rooms, grouped by the group that owns them.
        class Displaced(val groupId: String, val previousHousekeeperId: String, val movedRoomIds: MutableList<String>, val movedRoomNumbers: MutableList<String>)
        val displaced = linkedMapOf<String, Displaced>()
        for (room in selected) {
            val mirror = store.getDoc(MIRRORS, room.id) ?: continue
            val groupId = mirror["groupId"] as? String
            if (groupId.isNullOrEmpty()) continue
            val entry = displaced.getOrPut(groupId) {
                Displaced(groupId, mirror["housekeeperId"] as? String ?: "", mutableListOf(), mutableListOf())
            }
            entry.movedRoomIds += room.id
            entry.movedRoomNumbers += (mirror["roomNumber"] as? String) ?: room.number
        }

        // 2. The displaced groups themselves.
        val groupDocs = displaced.keys.associateWith { store.getDoc(GROUPS, it) }

        // 3. ONE batch: shrink or end the displaced groups, create the new group, point every selected room at it.
        val writes = mutableListOf<HkWrite>()
        for ((groupId, entry) in displaced) {
            val group = parseAssignmentGroup(groupId, groupDocs[groupId] ?: continue)
            val keepIds = mutableListOf<String>()
            val keepNumbers = mutableListOf<String>()
            group.roomIds.forEachIndexed { index, roomId ->
                if (roomId !in entry.movedRoomIds.toSet()) {
                    keepIds += roomId
                    group.roomNumbers.getOrNull(index)?.let { keepNumbers += it }
                }
            }
            writes += if (keepIds.isEmpty()) {
                HkWrite.Patch(
                    GROUPS, groupId,
                    mapOf(
                        "status" to "ended", "endedAt" to HousekeepingServerTime, "endedBy" to actor.id,
                        "roomIds" to emptyList<String>(), "roomNumbers" to emptyList<String>()
                    )
                )
            } else {
                HkWrite.Patch(
                    GROUPS, groupId,
                    mapOf(
                        "roomIds" to keepIds, "roomNumbers" to keepNumbers,
                        "updatedAt" to HousekeepingServerTime, "updatedBy" to actor.id
                    )
                )
            }
        }

        val start = calendarDayInstant(startDate)
        val end = endDate?.let { calendarDayInstant(it) }
        val cleanNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        val newGroupId = store.newId(GROUPS)
        val roomNumbers = selected.map { it.number }
        writes += HkWrite.Put(
            GROUPS, newGroupId,
            linkedMapOf(
                "housekeeperId" to housekeeperId,
                "housekeeperName" to housekeeperName,
                "roomIds" to selected.map { it.id },
                "roomNumbers" to roomNumbers,
                "startDate" to start,
                "endDate" to end,
                "notes" to cleanNotes,
                "status" to "active",
                "createdBy" to actor.id,
                "createdByName" to actor.name,
                "createdAt" to HousekeepingServerTime,
                "updatedAt" to HousekeepingServerTime,
                "isDeleted" to false
            )
        )
        for (room in selected) {
            writes += HkWrite.Put(
                MIRRORS, room.id,
                linkedMapOf(
                    "roomId" to room.id,
                    "roomNumber" to room.number,
                    "housekeeperId" to housekeeperId,
                    "housekeeperName" to housekeeperName,
                    "groupId" to newGroupId,
                    "startDate" to start,
                    "endDate" to end,
                    "status" to "active",
                    "updatedAt" to HousekeepingServerTime
                )
            )
        }
        store.commit(writes)

        // 4. Best-effort audit and notifications.
        bestEffort("assignment audit") {
            audit.log("rooms_assigned", GROUPS, newGroupId, null, mapOf("housekeeperId" to housekeeperId, "roomIds" to selectedIds.toList()))
        }
        bestEffort("assignment notification") {
            notifier.notifyRoomsAssigned(
                actor.name, housekeeperId, housekeeperName, roomNumbers,
                DAY_TEXT.format(startDate), endDate?.let { DAY_TEXT.format(it) }
            )
        }
        for (entry in displaced.values) {
            if (entry.previousHousekeeperId == housekeeperId) continue // same owner: re-pointed, nothing to tell them
            bestEffort("reassigned notification") {
                notifier.notifyRoomsReassigned(entry.previousHousekeeperId, entry.movedRoomNumbers, housekeeperName, actor.name)
            }
        }
        return newGroupId
    }

    override suspend fun endAssignmentGroup(groupId: String, actor: HousekeepingActor) {
        val data = store.getDoc(GROUPS, groupId) ?: throw HousekeepingException(MSG_ASSIGNMENT_NOT_FOUND)
        val group = parseAssignmentGroup(groupId, data)

        val writes = mutableListOf<HkWrite>()
        writes += HkWrite.Patch(GROUPS, groupId, mapOf("status" to "ended", "endedAt" to HousekeepingServerTime, "endedBy" to actor.id))
        group.roomIds.forEach { writes += HkWrite.Remove(MIRRORS, it) }
        store.commit(writes)

        bestEffort("assignment audit") {
            audit.log("room_assignment_ended", GROUPS, groupId, mapOf("status" to group.status), mapOf("status" to "ended"))
        }
        bestEffort("assignment notification") {
            notifier.notifyRoomAssignmentEnded(group.housekeeperId, group.roomNumbers, actor.name)
        }
    }

    override suspend fun updateAssignmentGroup(groupId: String, update: AssignmentGroupUpdate, actor: HousekeepingActor) {
        val data = store.getDoc(GROUPS, groupId) ?: throw HousekeepingException(MSG_ASSIGNMENT_NOT_FOUND)
        val group = parseAssignmentGroup(groupId, data)

        val groupFields = linkedMapOf<String, Any?>("updatedAt" to HousekeepingServerTime, "updatedBy" to actor.id)
        val mirrorFields = linkedMapOf<String, Any?>("updatedAt" to HousekeepingServerTime)
        update.startDate?.let {
            groupFields["startDate"] = calendarDayInstant(it)
            mirrorFields["startDate"] = calendarDayInstant(it)
        }
        if (update.changeEndDate) {
            val end = update.endDate?.let { calendarDayInstant(it) }
            groupFields["endDate"] = end
            mirrorFields["endDate"] = end
        }
        update.notes?.let { groupFields["notes"] = it.trim().takeIf { text -> text.isNotEmpty() } }

        val writes = mutableListOf<HkWrite>(HkWrite.Patch(GROUPS, groupId, groupFields))
        group.roomIds.forEach { writes += HkWrite.Patch(MIRRORS, it, mirrorFields) }
        store.commit(writes)

        bestEffort("assignment audit") {
            audit.log(
                "room_assignment_updated", GROUPS, groupId, null,
                mapOf(
                    "startDate" to update.startDate?.let { DAY_TEXT.format(it) },
                    "endDate" to update.endDate?.let { DAY_TEXT.format(it) },
                    "changeEndDate" to update.changeEndDate,
                    "notes" to update.notes
                )
            )
        }
    }

    override suspend fun assignedRoomIds(housekeeperId: String): List<String> =
        store.activeMirrorRoomIds(housekeeperId)
}
