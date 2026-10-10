package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

internal const val ATTENDANCE_COLLECTION = "attendance"
internal const val ATTENDANCE_USERS_COLLECTION = "users"

/** Marker for "server time"; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object AttendanceServerTime

/** The database calls attendance needs. [FirestoreAttendanceStore] is the real one; unit tests use a fake. */
interface AttendanceStore {
    /** The whole `attendance` collection, live. */
    fun observeAll(): Flow<Resource<List<AttendanceRecord>>>

    /** The `users` mirror, live. */
    fun observeUsers(): Flow<Resource<List<AttendanceUser>>>

    /** Merge-writes [fields] to `attendance/{id}`. The fields may hold [AttendanceServerTime]. */
    suspend fun merge(id: String, fields: Map<String, Any?>)
}

class FirestoreAttendanceStore(private val firestore: BusinessFirestore) : AttendanceStore {
    override fun observeAll(): Flow<Resource<List<AttendanceRecord>>> =
        firestore.observeList(ATTENDANCE_COLLECTION, AttendanceRecord::class.java)

    override fun observeUsers(): Flow<Resource<List<AttendanceUser>>> =
        firestore.observeList(ATTENDANCE_USERS_COLLECTION, AttendanceUser::class.java)

    override suspend fun merge(id: String, fields: Map<String, Any?>) {
        val resolved = fields.mapValues { (_, v) -> if (v is AttendanceServerTime) FieldValue.serverTimestamp() else v }
        firestore.set(ATTENDANCE_COLLECTION, id, resolved, merge = true)
    }
}

/**
 * The fields written for one attendance row. Blank strings are stored as null; `createdAt` is added ONLY when the
 * person had no record that day yet.
 */
internal fun attendanceSavePayload(
    row: AttendanceRowState,
    staff: AttendanceUser,
    dateKey: String,
    existing: AttendanceRecord?,
    recordedBy: String,
    recordedByName: String,
    zone: ZoneId
): Map<String, Any?> {
    val midnight = AttendanceRules.midnightOf(dateKey, zone)
    val payload = linkedMapOf<String, Any?>(
        "staffId" to staff.id,
        "staffName" to staff.name,
        "staffRole" to staff.role,
        "dateKey" to dateKey,
        "date" to Timestamp(midnight.epochSecond, 0),
        "status" to row.status.key,
        "clockIn" to row.clockIn.trim().ifBlank { null },
        "clockOut" to row.clockOut.trim().ifBlank { null },
        "notes" to row.notes.trim().ifBlank { null },
        "recordedBy" to recordedBy,
        "recordedByName" to recordedByName,
        "updatedAt" to AttendanceServerTime,
        "isDeleted" to false
    )
    if (existing == null) payload["createdAt"] = AttendanceServerTime
    return payload
}

internal fun attendanceZoneOf(business: com.westly.nbms.core.session.Business): ZoneId = try {
    ZoneId.of(business.timezone)
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

/**
 * Reads and writes `attendance` and reads the `users` mirror. It only talks to Firestore: it does not audit, show
 * toasts or end PIN sessions (the Record Attendance screen does that).
 */
@Singleton
class AttendanceRepository(
    private val store: AttendanceStore,
    private val session: SessionManager
) {
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager) :
        this(FirestoreAttendanceStore(firestore), session)

    /** The whole `attendance` collection, live. Callers drop the deleted records. */
    fun observeAll(): Flow<Resource<List<AttendanceRecord>>> = store.observeAll()

    /** Live `users` that are not deleted. Callers keep the active ones. */
    fun observeUsers(): Flow<Resource<List<AttendanceUser>>> = store.observeUsers().map { r ->
        when (r) {
            is Resource.Success -> Resource.Success(r.data.filter { !it.isDeleted })
            is Resource.Error -> Resource.Error(r.message, r.cause)
            is Resource.Loading -> Resource.Loading
        }
    }

    /**
     * Merge-writes the row to `attendance/{staffId}__{dateKey}`. Throws when signed out or when the write fails.
     * [existing] is the saved record of that person and day, or null when there is none (then `createdAt` is added).
     */
    suspend fun save(row: AttendanceRowState, staff: AttendanceUser, dateKey: String, existing: AttendanceRecord?) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw IllegalStateException("Not signed in")
        val payload = attendanceSavePayload(
            row = row,
            staff = staff,
            dateKey = dateKey,
            existing = existing,
            recordedBy = signedIn.user.uid,
            recordedByName = signedIn.user.name,
            zone = attendanceZoneOf(signedIn.business)
        )
        store.merge(AttendanceRules.attendanceDocId(staff.id, dateKey), payload)
    }
}
