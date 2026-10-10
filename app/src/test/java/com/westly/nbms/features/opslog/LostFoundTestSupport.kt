package com.westly.nbms.features.opslog

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

internal val LF_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun lfItem(
    id: String,
    name: String = "Wristwatch",
    description: String? = "Silver, leather strap",
    room: String = "201",
    foundBy: String = "Ada",
    status: ItemStatus = ItemStatus.STORED,
    foundAt: Instant? = LF_NOW,
    deleted: Boolean = false,
    guestName: String? = null,
    history: List<StatusHistoryEntry> = emptyList()
) = LostFoundItem(
    id = id, itemName = name, description = description, roomId = "r1", roomNumber = room, foundAt = foundAt,
    foundByName = foundBy, foundBy = "u1", status = status, notes = null, photoUrl = null, guestName = guestName,
    createdBy = "u1", createdByName = foundBy, createdAt = foundAt, updatedBy = null, updatedByName = null, updatedAt = null,
    statusHistory = history, isDeleted = deleted
)

internal fun lfHistory(status: ItemStatus, at: Instant?, by: String = "Ada", note: String? = null) =
    StatusHistoryEntry(status.key, "u1", by, at, note)

internal class LfFakeSession(
    role: Role = Role.MANAGER, uid: String = "u1", name: String = "Ada", usesPin: Boolean = false, signedIn: Boolean = true
) : SessionManager {
    var signOuts = 0
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser(uid, "biz1", role, name, "a@x.com", null, "active", usesPin),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() { signOuts++ }
    override suspend fun refresh() {}
}

/** In-memory `lost_found`. Records every create and update. */
internal class LfFakeStore : LostFoundStore {
    val items = MutableStateFlow<Resource<List<LostFoundItem>>>(Resource.Success(emptyList()))
    val rooms = MutableStateFlow<Resource<List<LostFoundRoomOption>>>(Resource.Success(emptyList()))
    val created = mutableListOf<Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null
    var hang = false

    override fun observe(): Flow<Resource<List<LostFoundItem>>> = items
    override fun observeRooms(): Flow<Resource<List<LostFoundRoomOption>>> = rooms

    override suspend fun create(payload: Map<String, Any?>): String {
        if (hang) kotlinx.coroutines.awaitCancellation()
        failWith?.let { throw it }
        created += payload
        return "lf${created.size}"
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        failWith?.let { throw it }
        updates += id to fields
    }
}

internal class LfFakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

internal class LfFakeNotifier : Notifier {
    data class Call(val title: String, val message: String, val link: String?)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(title, message, link)
    }
}
