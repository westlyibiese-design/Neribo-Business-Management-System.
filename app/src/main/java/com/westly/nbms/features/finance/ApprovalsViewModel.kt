package com.westly.nbms.features.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_APPROVALS_PENDING_LOAD_FAILED = "We couldn't load pending payments."
internal const val MSG_APPROVALS_HISTORY_LOAD_FAILED = "We couldn't load transactions."
internal const val MSG_APPROVALS_FALLBACK_ERROR = "Something went wrong. Please try again."

/** The Status dropdown. [status] is null for "All Statuses". */
internal enum class ApprovalsStatusFilter(val label: String, val status: ApprovalStatus?) {
    ALL("All Statuses", null),
    PENDING("Pending", ApprovalStatus.PENDING),
    APPROVED("Approved", ApprovalStatus.APPROVED),
    REJECTED("Rejected", ApprovalStatus.REJECTED)
}

/** The labels of the Range dropdown, in order. */
internal fun approvalsRangeLabel(preset: DateRangePreset): String = when (preset) {
    DateRangePreset.TODAY -> "Today"
    DateRangePreset.WEEK -> "This Week"
    DateRangePreset.MONTH -> "This Month"
    DateRangePreset.YEAR -> "This Year"
    DateRangePreset.CUSTOM -> "Custom Range"
}

/** The filter bar shared by Transaction History and Daily Records. Search only affects History. */
internal data class ApprovalsFilters(
    val range: DateRangePreset = DateRangePreset.MONTH,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val status: ApprovalsStatusFilter = ApprovalsStatusFilter.ALL,
    val search: String = ""
)

/** The four tiles above the History list. They are computed over the date range only, never over the status or search. */
internal data class ApprovalsStats(
    val approvedRevenue: Double,
    val transactions: Int,
    val pendingCount: Int,
    val pendingAmount: Double,
    val rejectedCount: Int,
    val rejectedAmount: Double
)

/** What the Approvals page is showing. */
internal sealed interface ApprovalsView {
    data object Loading : ApprovalsView
    data object Error : ApprovalsView
    data class Ready(
        /** Every pending transaction of the business, newest first. Not limited by the range. */
        val pending: List<RevenueTransaction>,
        /** The rows of Transaction History: range, then status, then search; newest first. */
        val history: List<RevenueTransaction>,
        val stats: ApprovalsStats,
        /** One record per day for the range, narrowed by the status filter. */
        val daily: List<DailyRecord>,
        val rangeStart: LocalDate,
        val rangeEnd: LocalDate
    ) : ApprovalsView
}

// ── pure helpers (unit-tested without Android) ──

/** Only the Super Admin and the Accountant may approve or reject. The Manager sees the page read-only. */
internal fun approvalsCanReview(role: Role): Boolean = role == Role.SUPER_ADMIN || role == Role.ACCOUNTANT

/** A key that is unique per transaction across the five source collections. */
internal fun approvalsTxnKey(txn: RevenueTransaction): String = "${txn.source.path}/${txn.id}"

private fun newestFirst(rows: List<RevenueTransaction>): List<RevenueTransaction> =
    rows.sortedByDescending { it.date ?: Instant.MIN }

/** Matches the guest name or the type label, ignoring case. A blank search matches everything. */
internal fun approvalsMatchesSearch(txn: RevenueTransaction, search: String): Boolean {
    val q = search.trim().lowercase()
    return q.isEmpty() || txn.guestName.lowercase().contains(q) || txn.typeLabel.lowercase().contains(q)
}

/** The tiles for [rangeRows] (the transactions already limited to the date range). */
internal fun approvalsStats(rangeRows: List<RevenueTransaction>): ApprovalsStats {
    val pending = RevenueMath.pendingOnly(rangeRows)
    val rejected = RevenueMath.rejectedOnly(rangeRows)
    return ApprovalsStats(
        approvedRevenue = RevenueMath.sum(RevenueMath.approvedOnly(rangeRows)),
        transactions = rangeRows.size,
        pendingCount = pending.size,
        pendingAmount = RevenueMath.sum(pending),
        rejectedCount = rejected.size,
        rejectedAmount = RevenueMath.sum(rejected)
    )
}

/** The business time zone, or Africa/Lagos when the stored name is missing or not a real zone. */
internal fun approvalsZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

/** Turns the ledger's answer into a page state. [today] is the current date in the business time zone. */
internal fun approvalsViewOf(
    resource: Resource<List<RevenueTransaction>>,
    filters: ApprovalsFilters,
    today: LocalDate,
    zone: ZoneId
): ApprovalsView = when (resource) {
    is Resource.Loading -> ApprovalsView.Loading
    is Resource.Error -> ApprovalsView.Error
    is Resource.Success -> {
        val all = resource.data
        val (start, end) = RevenueMath.resolveRange(filters.range, today, filters.customStart, filters.customEnd)
        val rangeRows = RevenueMath.inRange(all, start, end)
        val byStatus = filters.status.status?.let { wanted -> rangeRows.filter { it.approvalStatus == wanted } } ?: rangeRows
        ApprovalsView.Ready(
            pending = newestFirst(RevenueMath.pendingOnly(all)),
            history = newestFirst(byStatus.filter { approvalsMatchesSearch(it, filters.search) }),
            stats = approvalsStats(rangeRows),
            daily = RevenueMath.groupByDay(byStatus),
            rangeStart = start.atZone(zone).toLocalDate(),
            rangeEnd = end.atZone(zone).toLocalDate()
        )
    }
}

/** Reads transactions only through [RevenueLedger.observe]; approving and rejecting only call [RevenueLedger]. */
@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    private val ledger: RevenueLedger,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val signedIn: SessionState.SignedIn? get() = session.state.value as? SessionState.SignedIn

    private val zone: ZoneId get() = approvalsZoneOf(signedIn?.business?.timezone)

    private val _filters = MutableStateFlow(ApprovalsFilters())
    internal val filters: StateFlow<ApprovalsFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live = retryTick
        .flatMapLatest { ledger.observe() }
        .catch { emit(Resource.Error(MSG_APPROVALS_PENDING_LOAD_FAILED, it)) }

    internal val view: StateFlow<ApprovalsView> = combine(live, _filters) { resource, filters ->
        val z = zone
        approvalsViewOf(resource, filters, LocalDate.now(z), z)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApprovalsView.Loading)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** The keys ([approvalsTxnKey]) of the transactions being approved or rejected right now. */
    internal val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    internal fun setRange(range: DateRangePreset) {
        _filters.update { it.copy(range = range) }
    }

    internal fun setCustomStart(date: LocalDate) {
        _filters.update { it.copy(customStart = date) }
    }

    internal fun setCustomEnd(date: LocalDate) {
        _filters.update { it.copy(customEnd = date) }
    }

    internal fun setStatus(status: ApprovalsStatusFilter) {
        _filters.update { it.copy(status = status) }
    }

    internal fun setSearch(search: String) {
        _filters.update { it.copy(search = search) }
    }

    internal fun retry() {
        retryTick.update { it + 1 }
    }

    /** Marks [key] as busy. Returns false when it already is, so a second tap does nothing. Runs before any suspend call. */
    private fun lock(key: String): Boolean {
        while (true) {
            val current = _busy.value
            if (key in current) return false
            if (_busy.compareAndSet(current, current + key)) return true
        }
    }

    private fun unlock(key: String) {
        _busy.update { it - key }
    }

    /** Approves [txn] through the ledger. Does nothing for a role that may not approve. */
    internal fun approve(txn: RevenueTransaction) {
        val user = signedIn ?: return
        if (!approvalsCanReview(user.user.role)) return
        val key = approvalsTxnKey(txn)
        if (!lock(key)) return
        val symbol = user.business.currencySymbol
        viewModelScope.launch {
            try {
                ledger.approve(txn)
                toast.show(
                    message = "${Format.currency(txn.amount, symbol)} from ${txn.guestName} is now counted as revenue.",
                    type = ToastType.Success,
                    title = "Payment Approved"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_APPROVALS_FALLBACK_ERROR, type = ToastType.Error, title = "Error")
            } finally {
                unlock(key)
            }
        }
    }

    /**
     * Rejects [txn] through the ledger. [reason] is trimmed and a blank one is sent as null.
     * [onDone] runs after a successful rejection so the dialog can close; on a failure the dialog stays open.
     */
    internal fun reject(txn: RevenueTransaction, reason: String, onDone: () -> Unit) {
        val user = signedIn ?: return
        if (!approvalsCanReview(user.user.role)) return
        val key = approvalsTxnKey(txn)
        if (!lock(key)) return
        val symbol = user.business.currencySymbol
        viewModelScope.launch {
            try {
                ledger.reject(txn, reason.trim().ifEmpty { null })
                toast.show(
                    message = "${Format.currency(txn.amount, symbol)} from ${txn.guestName} was excluded from revenue.",
                    type = ToastType.Success,
                    title = "Payment Rejected"
                )
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_APPROVALS_FALLBACK_ERROR, type = ToastType.Error, title = "Error")
            } finally {
                unlock(key)
            }
        }
    }
}
