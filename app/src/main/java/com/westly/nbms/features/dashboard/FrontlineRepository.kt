package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.MSG_DASHBOARD_LOAD_FAILED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/** Maps the rows of a successful list, dropping those that map to null (soft-deleted records); Loading and Error pass through. */
internal fun <T, R : Any> Resource<List<T>>.mapRowsNotNull(transform: (T) -> R?): Resource<List<R>> = when (this) {
    is Resource.Loading -> Resource.Loading
    is Resource.Error -> this
    is Resource.Success -> Resource.Success(data.mapNotNull(transform))
}

/** Active assignments only (status "active", not deleted). */
internal fun countActiveAssignments(rows: List<RawRoomAssignmentRow>): Int = rows.count { it.isActive() }

/** Housekeeping queue: only pending and in_progress tasks count. */
internal fun isQueued(row: HkTaskRow): Boolean = row.status == "pending" || row.status == "in_progress"

/**
 * Phase 34A1: the "my own work" queries of the front-line dashboards. Every flow is cold, tenant-scoped through
 * [BusinessFirestore], excludes soft-deleted records, starts with [Resource.Loading] and turns a failing read into
 * [Resource.Error]. A blank [uid] reads nothing and gives an empty list (never someone else's records).
 * Bookings, inventory, gym members and gym visits come from Phase 33's DashboardRepository.
 */
@Singleton
class FrontlineRepository @Inject constructor(
    private val store: BusinessFirestore
) {

    private fun <R : Any, D : Any> mine(
        collection: String,
        clazz: Class<R>,
        field: String,
        uid: String,
        transform: (R) -> D?,
        finish: (List<D>) -> List<D> = { it }
    ): Flow<Resource<List<D>>> =
        if (uid.isBlank()) flow { emit(Resource.Success(emptyList<D>())) }
        else flow {
            emitAll(
                store.observeList(collection, clazz) { it.whereEqualTo(field, uid) }
                    .map { r -> r.mapRowsNotNull(transform).let { m -> if (m is Resource.Success) Resource.Success(finish(m.data)) else m } }
            )
        }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    /** `sales` where `staffId == uid`. */
    fun mySales(uid: String): Flow<Resource<List<AmountRow>>> =
        mine("sales", RawAmountRow::class.java, "staffId", uid, { it.toRowOrNull() })

    /** `orders` where `waiterId == uid`. */
    fun myOrders(uid: String): Flow<Resource<List<OrderRow>>> =
        mine("orders", RawOrderRow::class.java, "waiterId", uid, { it.toRowOrNull() })

    /** `bar_orders` where `barAttendantId == uid`. */
    fun myBarOrders(uid: String): Flow<Resource<List<OrderRow>>> =
        mine("bar_orders", RawOrderRow::class.java, "barAttendantId", uid, { it.toRowOrNull() })

    /** `laundry_requests` where `laundryValetId == uid`. */
    fun myLaundry(uid: String): Flow<Resource<List<FrontlineLaundryRow>>> =
        mine("laundry_requests", RawFrontlineLaundryRow::class.java, "laundryValetId", uid, { it.toRowOrNull() })

    /** `housekeeping_tasks` where `assignedTo == uid` and status is pending or in_progress (status is checked here, so no composite index is needed). */
    fun myHousekeepingQueue(uid: String): Flow<Resource<List<HkTaskRow>>> =
        mine("housekeeping_tasks", RawHkTaskRow::class.java, "assignedTo", uid, { it.toRowOrNull() }, { rows -> rows.filter(::isQueued) })

    /** Active `room_assignments` where `housekeeperId == uid`. */
    fun myRoomAssignmentCount(uid: String): Flow<Resource<Int>> =
        if (uid.isBlank()) flow { emit(Resource.Success(0)) }
        else flow {
            emitAll(
                store.observeList("room_assignments", RawRoomAssignmentRow::class.java) { it.whereEqualTo("housekeeperId", uid) }
                    .map { r ->
                        when (r) {
                            is Resource.Loading -> Resource.Loading
                            is Resource.Error -> r
                            is Resource.Success -> Resource.Success(countActiveAssignments(r.data))
                        }
                    }
            )
        }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }
}
