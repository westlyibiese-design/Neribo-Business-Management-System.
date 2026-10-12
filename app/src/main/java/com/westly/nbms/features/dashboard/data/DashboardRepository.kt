package com.westly.nbms.features.dashboard.data

import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.finance.RevenueLedger
import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.inventory.InventoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_DASHBOARD_LOAD_FAILED = "We couldn't load some dashboard data."
internal const val ACTIVITY_FEED_PATH = "activity_feed"

/** Maps the rows of a successful list; Loading and Error pass through unchanged. */
internal fun <T, R> Resource<List<T>>.mapRows(transform: (T) -> R): Resource<List<R>> = when (this) {
    is Resource.Loading -> Resource.Loading
    is Resource.Error -> this
    is Resource.Success -> Resource.Success(data.map(transform))
}

/** Keeps the rows of a successful list that pass [predicate]; Loading and Error pass through unchanged. */
internal fun <T> Resource<List<T>>.filterRows(predicate: (T) -> Boolean): Resource<List<T>> = when (this) {
    is Resource.Loading -> Resource.Loading
    is Resource.Error -> this
    is Resource.Success -> Resource.Success(data.filter(predicate))
}

/** Newest first (by `at`), at most [limit] entries (a limit of 0 or less gives an empty list). */
internal fun newestActivity(items: Collection<ActivityItem>, limit: Int): List<ActivityItem> =
    items.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))

/**
 * The live data the management dashboards read: one cold Flow per collection, tenant-scoped through [BusinessFirestore].
 * Nothing is filtered here except what the revenue ledger rule requires — `DashboardMath` does the isDeleted and status
 * filtering. A flow that has not emitted yet is [Resource.Loading]; a failing collection becomes [Resource.Error]
 * (the activity feed is the one exception: unreadable means an empty list).
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val store: BusinessFirestore,
    private val realtime: BusinessRealtime,
    private val ledger: RevenueLedger,
    private val session: SessionManager
) {

    /** Zone used to read the day of older attendance records that have no `dateKey`. */
    private fun businessZone(): ZoneId =
        dashZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    private fun <R : Any, D> rows(collection: String, clazz: Class<R>, transform: (R) -> D): Flow<Resource<List<D>>> =
        flow { emitAll(store.observeList(collection, clazz).map { it.mapRows(transform) }) }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    fun rooms(): Flow<Resource<List<DashRoom>>> = rows("rooms", RawDashRoom::class.java) { it.toDash() }

    fun bookings(): Flow<Resource<List<DashBooking>>> = rows("bookings", RawDashBooking::class.java) { it.toDash() }

    fun expenses(): Flow<Resource<List<DashExpense>>> = rows("expenses", RawDashExpense::class.java) { it.toDash() }

    /** `InventoryItem` is Phase 18's own model; it already carries `isDeleted`, `quantity` and `minStock`. */
    fun inventory(): Flow<Resource<List<InventoryItem>>> =
        flow { emitAll(store.observeList("inventory", InventoryItem::class.java)) }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    fun orders(): Flow<Resource<List<DashOrder>>> = rows("orders", RawDashOrder::class.java) { it.toDash() }

    fun barOrders(): Flow<Resource<List<DashOrder>>> = rows("bar_orders", RawDashOrder::class.java) { it.toDash() }

    fun laundry(): Flow<Resource<List<DashLaundry>>> = rows("laundry_requests", RawDashLaundry::class.java) { it.toDash() }

    fun maintenance(): Flow<Resource<List<DashMaintenance>>> =
        rows("maintenance", RawDashMaintenance::class.java) { it.toDash() }

    fun attendance(): Flow<Resource<List<DashAttendance>>> =
        flow {
            val zone = businessZone()
            emitAll(store.observeList("attendance", RawDashAttendance::class.java).map { r -> r.mapRows { it.toDash(zone) } })
        }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    fun tasks(): Flow<Resource<List<DashTask>>> = rows("tasks", RawDashTask::class.java) { it.toDash() }

    fun gymMembers(): Flow<Resource<List<DashGymMember>>> =
        rows("gym_members", RawDashGymMember::class.java) { it.toDash() }

    fun gymVisits(): Flow<Resource<List<DashGymVisit>>> =
        rows("gym_attendance", RawDashGymVisit::class.java) { it.toDash() }

    /** The revenue ledger, approved transactions only — the same figure as Revenue, Financial Reports and the Accountant view. */
    fun approvedRevenue(): Flow<Resource<List<RevenueTransaction>>> =
        ledger.observe()
            .map { r -> r.mapAll { RevenueMath.approvedOnly(it) } }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    /** The revenue ledger, transactions still waiting for approval. */
    fun pendingRevenue(): Flow<Resource<List<RevenueTransaction>>> =
        ledger.observe()
            .map { r -> r.mapAll { RevenueMath.pendingOnly(it) } }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Error(MSG_DASHBOARD_LOAD_FAILED, it)) }

    /** Realtime Database `activity_feed`, newest first, at most [limit]. A feed that cannot be read is an empty list, never an error. */
    fun activityFeed(limit: Int): Flow<Resource<List<ActivityItem>>> =
        flow {
            emitAll(
                realtime.observeMap(ACTIVITY_FEED_PATH, ActivityItem::class.java).map { r ->
                    when (r) {
                        is Resource.Loading -> Resource.Loading
                        is Resource.Error -> Resource.Success(emptyList())
                        is Resource.Success -> Resource.Success(newestActivity(r.data.values, limit))
                    }
                }
            )
        }
            .onStart { emit(Resource.Loading) }
            .catch { emit(Resource.Success(emptyList())) }

    private fun <T, R> Resource<List<T>>.mapAll(transform: (List<T>) -> List<R>): Resource<List<R>> = when (this) {
        is Resource.Loading -> Resource.Loading
        is Resource.Error -> this
        is Resource.Success -> Resource.Success(transform(data))
    }
}
