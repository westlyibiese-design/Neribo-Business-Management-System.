package com.westly.nbms.features.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.features.dashboard.dashClock
import com.westly.nbms.features.dashboard.zoneOfSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** The six live lists the page reads. */
internal class PerformanceFlows(
    val users: Flow<Resource<List<PerfUser>>>,
    val checkins: Flow<Resource<List<PerfStamp>>>,
    val checkouts: Flow<Resource<List<PerfStamp>>>,
    val sales: Flow<Resource<List<PerfSale>>>,
    val orders: Flow<Resource<List<PerfSale>>>,
    val attendance: Flow<Resource<List<PerfAttendance>>>
)

/** Everything the Staff Performance screen shows. [rows] is every active person, sorted; [top] is the chart's slice. */
internal data class PerformanceState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val rows: List<StaffPerformance> = emptyList(),
    val top: List<StaffPerformance> = emptyList()
) {
    /** True when nobody did anything this month, so the chart shows its empty text. */
    val chartIsEmpty: Boolean get() = top.none { it.totalActivities > 0 }
}

private fun anyError(vararg r: Resource<*>): Boolean = r.any { it is Resource.Error }

private fun anyPending(vararg r: Resource<*>): Boolean = r.any { it is Resource.Loading }

private fun <T> Resource<List<T>>.listOrEmpty(): List<T> = (this as? Resource.Success)?.data ?: emptyList()

/** Works out the page from the six lists. Pure: every number comes from [PerformanceMath]. */
internal fun performanceStateOf(
    users: Resource<List<PerfUser>>,
    checkins: Resource<List<PerfStamp>>,
    checkouts: Resource<List<PerfStamp>>,
    sales: Resource<List<PerfSale>>,
    orders: Resource<List<PerfSale>>,
    attendance: Resource<List<PerfAttendance>>,
    now: Instant,
    zone: ZoneId
): PerformanceState {
    val failed = anyError(users, checkins, checkouts, sales, orders, attendance)
    val loading = !failed && anyPending(users, checkins, checkouts, sales, orders, attendance)
    if (failed || loading) return PerformanceState(loading = loading, failed = failed, now = now)
    val rows = PerformanceMath.compute(
        users = users.listOrEmpty(),
        checkins = checkins.listOrEmpty(),
        checkouts = checkouts.listOrEmpty(),
        sales = sales.listOrEmpty(),
        orders = orders.listOrEmpty(),
        attendance = attendance.listOrEmpty(),
        now = now,
        zone = zone
    )
    return PerformanceState(loading = false, failed = false, now = now, rows = rows, top = PerformanceMath.top(rows))
}

/** Reads the six lists live and turns them into this month's activity chart and leaderboard. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModel internal constructor(
    private val flows: () -> PerformanceFlows,
    private val zone: ZoneId,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(
        repository: PerformanceRepository,
        session: SessionManager
    ) : this(
        flows = {
            PerformanceFlows(
                users = repository.users(),
                checkins = repository.checkins(),
                checkouts = repository.checkouts(),
                sales = repository.sales(),
                orders = repository.orders(),
                attendance = repository.attendance()
            )
        },
        zone = zoneOfSession(session),
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<PerformanceState> = attempt
        .flatMapLatest {
            val f = flows()
            val people = combine(f.users, f.checkins, f.checkouts) { u, ci, co -> Triple(u, ci, co) }
            val money = combine(f.sales, f.orders, f.attendance) { s, o, a -> Triple(s, o, a) }
            combine(people, money, clock) { p, m, now ->
                performanceStateOf(p.first, p.second, p.third, m.first, m.second, m.third, now, zone)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerformanceState())

    /** Retry after an error: collects all six lists again. */
    fun retry() { attempt.value += 1 }
}
