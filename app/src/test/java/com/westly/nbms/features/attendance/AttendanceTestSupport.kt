package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

internal val LAGOS: ZoneId = ZoneId.of("Africa/Lagos")

internal fun att(
    staffId: String,
    name: String = staffId,
    dateKey: String? = "2026-10-09",
    status: String = "present",
    date: Timestamp? = null,
    role: String? = "receptionist",
    clockIn: String? = null,
    clockOut: String? = null,
    notes: String? = null,
    deleted: Boolean = false,
    id: String = if (dateKey != null) "${staffId}__$dateKey" else staffId
) = AttendanceRecord(
    id = id, staffId = staffId, staffName = name, staffRole = role, dateKey = dateKey, date = date, status = status,
    clockIn = clockIn, clockOut = clockOut, notes = notes, isDeleted = deleted
)

internal fun attUser(id: String, name: String = id, status: String = "active", deleted: Boolean = false, role: String? = "receptionist") =
    AttendanceUser(id = id, name = name, role = role, status = status, isDeleted = deleted)

internal class AttFakeSession(
    role: Role = Role.RECEPTIONIST, uid: String = "u1", name: String = "Ada", signedIn: Boolean = true, timezone: String = "Africa/Lagos"
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

/** In-memory attendance store that records every merge-write. */
internal class AttFakeStore : AttendanceStore {
    val records = MutableStateFlow<Resource<List<AttendanceRecord>>>(Resource.Success(emptyList()))
    val users = MutableStateFlow<Resource<List<AttendanceUser>>>(Resource.Success(emptyList()))
    val merges = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null

    override fun observeAll(): Flow<Resource<List<AttendanceRecord>>> = records
    override fun observeUsers(): Flow<Resource<List<AttendanceUser>>> = users
    override suspend fun merge(id: String, fields: Map<String, Any?>) {
        failWith?.let { throw it }
        merges += id to fields
    }
}
