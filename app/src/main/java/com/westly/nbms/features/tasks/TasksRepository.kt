package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal const val TASKS_COLLECTION = "tasks"

/** Marker for "server time"; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object TaskServerTime

/** The database calls the tasks code needs. [FirestoreTasksStore] is the real one; the unit tests use a fake. */
interface TasksStore {
    /** Live `tasks` (managers: all of them). */
    fun observeAll(): Flow<Resource<List<StaffTask>>>

    /** Live tasks whose `assignedToIds` contains [uid]. */
    fun observeMine(uid: String): Flow<Resource<List<StaffTask>>>

    /** Creates `tasks/{new}` from [fields] (which may hold [TaskServerTime]) and returns the new id. */
    suspend fun create(fields: Map<String, Any?>): String

    /** Updates ONLY [fields] on the existing task. The fields may hold [TaskServerTime]. */
    suspend fun update(id: String, fields: Map<String, Any?>)
}

class FirestoreTasksStore(private val firestore: BusinessFirestore) : TasksStore {
    override fun observeAll(): Flow<Resource<List<StaffTask>>> =
        firestore.observeList(TASKS_COLLECTION, StaffTask::class.java)

    override fun observeMine(uid: String): Flow<Resource<List<StaffTask>>> =
        firestore.observeList(TASKS_COLLECTION, StaffTask::class.java) { it.whereArrayContains("assignedToIds", uid) }

    override suspend fun create(fields: Map<String, Any?>): String =
        firestore.add(TASKS_COLLECTION, resolveTaskValues(fields))

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(TASKS_COLLECTION, id, resolveTaskValues(fields))
    }
}

private fun resolveTaskValues(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) ->
    when (v) {
        is TaskServerTime -> FieldValue.serverTimestamp()
        is Instant -> Timestamp(v.epochSecond, v.nano)
        else -> v
    }
}

/** The new-task document as Firestore stores it. Accepted/completed fields start null; `createdAt` is server time. */
internal fun StaffTask.toCreateFields(): Map<String, Any?> = mapOf(
    "title" to title,
    "type" to type,
    "description" to description,
    "priority" to priority,
    "assignedToIds" to assignedToIds,
    "assignedToNames" to assignedToNames,
    "assignedBy" to assignedBy,
    "assignedByName" to assignedByName,
    "dueAt" to dueAt,
    "status" to status,
    "relatedCollection" to relatedCollection,
    "relatedId" to relatedId,
    "relatedLabel" to relatedLabel,
    "acceptedBy" to null,
    "acceptedByName" to null,
    "acceptedAt" to null,
    "completedAt" to null,
    "createdAt" to TaskServerTime,
    "isDeleted" to false
)

/**
 * Reads and writes `tasks`. It does NOT audit, notify or show toasts; the ViewModels and the assign dialog do that.
 * The database rules decide who may do what; this class only builds the writes.
 */
@Singleton
class TasksRepository(
    private val store: TasksStore,
    private val session: SessionManager
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager) : this(FirestoreTasksStore(firestore), session)

    /** Every task of the business (deleted ones included; use [TaskRules.visibleTasks]). */
    fun observeAll(): Flow<Resource<List<StaffTask>>> = store.observeAll()

    /** Tasks assigned to [uid] (`assignedToIds array-contains uid`). */
    fun observeMine(uid: String): Flow<Resource<List<StaffTask>>> = store.observeMine(uid)

    /** Saves a new task and returns its id. `createdAt` is server time; the accepted/completed fields start null. */
    suspend fun create(task: StaffTask): String = store.create(task.toCreateFields())

    /** Hands the task to new people: back to pending, nobody has accepted it. */
    suspend fun reassign(taskId: String, assignedToIds: List<String>, assignedToNames: List<String>) {
        val user = signedInUser()
        store.update(taskId, reassignFields(assignedToIds, assignedToNames, user.uid, user.name))
    }

    suspend fun cancel(taskId: String) {
        store.update(taskId, cancelFields())
    }

    suspend fun accept(taskId: String) {
        val user = signedInUser()
        store.update(taskId, acceptFields(user.uid, user.name))
    }

    suspend fun start(taskId: String) {
        store.update(taskId, startFields())
    }

    suspend fun complete(taskId: String) {
        store.update(taskId, completeFields())
    }

    private fun signedInUser() =
        (session.state.value as? SessionState.SignedIn)?.user ?: throw IllegalStateException("Not signed in")
}

internal fun reassignFields(ids: List<String>, names: List<String>, byUid: String, byName: String): Map<String, Any?> = mapOf(
    "assignedToIds" to ids,
    "assignedToNames" to names,
    "status" to TaskStatus.PENDING.key,
    "acceptedBy" to null,
    "acceptedByName" to null,
    "acceptedAt" to null,
    "reassignedAt" to TaskServerTime,
    "reassignedBy" to byUid,
    "reassignedByName" to byName,
    "updatedAt" to TaskServerTime
)

internal fun cancelFields(): Map<String, Any?> = mapOf(
    "status" to TaskStatus.CANCELLED.key,
    "updatedAt" to TaskServerTime
)

internal fun acceptFields(uid: String, name: String): Map<String, Any?> = mapOf(
    "status" to TaskStatus.ACCEPTED.key,
    "acceptedBy" to uid,
    "acceptedByName" to name,
    "acceptedAt" to TaskServerTime,
    "updatedAt" to TaskServerTime
)

internal fun startFields(): Map<String, Any?> = mapOf(
    "status" to TaskStatus.IN_PROGRESS.key,
    "updatedAt" to TaskServerTime
)

internal fun completeFields(): Map<String, Any?> = mapOf(
    "status" to TaskStatus.COMPLETED.key,
    "completedAt" to TaskServerTime,
    "updatedAt" to TaskServerTime
)
