package com.westly.nbms.features.performance

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.toDashInstant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_PERFORMANCE_LOAD_FAILED = "We couldn't load staff performance data."

// ─────────────────────────────────────────────────────────────────────────────
// Phase 34B: small row projections for the Staff Performance page (defaults on every field). The `Raw*` classes are what
// Firestore stores; each maps to its row, or to null when the record is soft-deleted (or, for users, not active), so a
// deleted record never reaches PerformanceMath.
// ─────────────────────────────────────────────────────────────────────────────

/** An active member of the `users` mirror. [id] is the person's uid, the id every activity record carries. */
data class PerfUser(val id: String = "", val name: String = "", val role: String? = null)

/** A `checkins` (`checkInAt`) or `checkouts` (`checkOutAt`) record: who did it and when. */
data class PerfStamp(val staffId: String = "", val at: Instant? = null)

/** A `sales` (`staffId`) or `orders` (`waiterId`) record. Counts include pending ones; revenue counts only approved ones. */
data class PerfSale(
    val staffId: String = "",
    val at: Instant? = null,
    val total: Double = 0.0,
    val approvalStatus: String? = null
)

/** An `attendance` record. [dateKey] is `yyyy-MM-dd`; older records without one carry only [date]. */
data class PerfAttendance(
    val staffId: String = "",
    val dateKey: String? = null,
    val date: Instant? = null,
    val status: String? = null
)

/** `users` mirror (written by the server functions). */
internal data class RawPerfUser(
    @DocumentId val id: String = "",
    val uid: String = "",
    val name: String = "",
    val role: String? = null,
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfUser? =
        if (isDeleted || status != "active") null else PerfUser(id = uid.ifBlank { id }, name = name, role = role)
}

/** `checkins`. */
internal data class RawPerfCheckin(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val checkInAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfStamp? = if (isDeleted) null else PerfStamp(staffId = staffId, at = checkInAt.toDashInstant())
}

/** `checkouts`. */
internal data class RawPerfCheckout(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val checkOutAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfStamp? = if (isDeleted) null else PerfStamp(staffId = staffId, at = checkOutAt.toDashInstant())
}

/** `sales` (Phase 19). */
internal data class RawPerfSale(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val createdAt: Timestamp? = null,
    val total: Double = 0.0,
    val approvalStatus: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfSale? =
        if (isDeleted) null else PerfSale(staffId = staffId, at = createdAt.toDashInstant(), total = total, approvalStatus = approvalStatus)
}

/** `orders` (Phase 20): the person is the waiter. */
internal data class RawPerfOrder(
    @DocumentId val id: String = "",
    val waiterId: String = "",
    val createdAt: Timestamp? = null,
    val total: Double = 0.0,
    val approvalStatus: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfSale? =
        if (isDeleted) null else PerfSale(staffId = waiterId, at = createdAt.toDashInstant(), total = total, approvalStatus = approvalStatus)
}

/** `attendance` (Phase 25). */
internal data class RawPerfAttendance(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val dateKey: String? = null,
    val date: Timestamp? = null,
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): PerfAttendance? =
        if (isDeleted) null else PerfAttendance(staffId = staffId, dateKey = dateKey, date = date.toDashInstant(), status = status)
}

/** Maps the rows of a successful list, dropping those that map to null; Loading and Error pass through. */
private fun <T, R : Any> Resource<List<T>>.mapNotNullRows(transform: (T) -> R?): Resource<List<R>> = when (this) {
    is Resource.Loading -> Resource.Loading
    is Resource.Error -> this
    is Resource.Success -> Resource.Success(data.mapNotNull(transform))
}

/**
 * Phase 34B: the live lists of the Staff Performance page. Every flow is cold, tenant-scoped through [BusinessFirestore],
 * excludes soft-deleted records, starts with [Resource.Loading] and turns a failing read into [Resource.Error].
 */
@Singleton
class PerformanceRepository @Inject constructor(
    private val store: BusinessFirestore
) {

    private fun <R : Any, D : Any> rows(collection: String, clazz: Class<R>, transform: (R) -> D?): Flow<Resource<List<D>>> =
        flow { emitAll(store.observeList(collection, clazz).map { it.mapNotNullRows(transform) }) }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_PERFORMANCE_LOAD_FAILED, it)) }

    /** The `users` mirror: active, not deleted. */
    fun users(): Flow<Resource<List<PerfUser>>> = rows("users", RawPerfUser::class.java) { it.toRowOrNull() }

    fun checkins(): Flow<Resource<List<PerfStamp>>> = rows("checkins", RawPerfCheckin::class.java) { it.toRowOrNull() }

    fun checkouts(): Flow<Resource<List<PerfStamp>>> = rows("checkouts", RawPerfCheckout::class.java) { it.toRowOrNull() }

    fun sales(): Flow<Resource<List<PerfSale>>> = rows("sales", RawPerfSale::class.java) { it.toRowOrNull() }

    fun orders(): Flow<Resource<List<PerfSale>>> = rows("orders", RawPerfOrder::class.java) { it.toRowOrNull() }

    fun attendance(): Flow<Resource<List<PerfAttendance>>> =
        rows("attendance", RawPerfAttendance::class.java) { it.toRowOrNull() }
}
