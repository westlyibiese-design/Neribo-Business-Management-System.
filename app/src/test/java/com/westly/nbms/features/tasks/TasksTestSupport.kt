package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

internal val TASK_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun ts(i: Instant): Timestamp = Timestamp(i.epochSecond, i.nano)

internal fun taskOf(
    id: String,
    title: String = "Clean room",
    type: TaskType = TaskType.OTHER,
    status: TaskStatus = TaskStatus.PENDING,
    dueAt: Instant? = null,
    createdAt: Instant? = null,
    completedAt: Instant? = null,
    names: List<String> = listOf("Ada"),
    deleted: Boolean = false
) = StaffTask(
    id = id, title = title, type = type.key, status = status.key,
    dueAt = dueAt?.let(::ts), createdAt = createdAt?.let(::ts), completedAt = completedAt?.let(::ts),
    assignedToIds = names.map { "id-$it" }, assignedToNames = names, isDeleted = deleted
)

internal class FakeTasksSession(
    role: Role = Role.OPERATIONS_MANAGER, uid: String = "me", name: String = "Boss", signedIn: Boolean = true,
    timezone: String = "Africa/Lagos"
) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser(uid, "biz1", role, name, "a@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** In-memory tasks store that records every create and update. */
internal class FakeTasksStore : TasksStore {
    val all = MutableStateFlow<Resource<List<StaffTask>>>(Resource.Success(emptyList()))
    val mineQueries = mutableListOf<String>()
    val created = mutableListOf<Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null
    var hang = false

    override fun observeAll(): Flow<Resource<List<StaffTask>>> = all
    override fun observeMine(uid: String): Flow<Resource<List<StaffTask>>> { mineQueries += uid; return all }

    override suspend fun create(fields: Map<String, Any?>): String {
        if (hang) kotlinx.coroutines.awaitCancellation()
        failWith?.let { throw it }
        created += fields
        return "task${created.size}"
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        if (hang) kotlinx.coroutines.awaitCancellation()
        failWith?.let { throw it }
        updates += id to fields
    }
}

internal class FakeTaskStaffSource(users: List<TaskStaffUser> = emptyList()) : TaskStaffSource {
    val flow = MutableStateFlow<Resource<List<TaskStaffUser>>>(Resource.Success(users))
    override fun observe(): Flow<Resource<List<TaskStaffUser>>> = flow
}

internal class FakeTaskAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class FakeTaskNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String, val severity: String, val link: String?, val userIds: List<String>)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message, severity, link, forUserIds)
    }
}
