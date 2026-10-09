package com.westly.nbms.features.laundry

import androidx.lifecycle.ViewModel
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import javax.inject.Inject

/** Laundry History: the live `laundry_requests` list with its filters, and the CSV export. Read-only. */
@HiltViewModel
class LaundryHistoryViewModel @Inject constructor(
    private val repository: LaundryHistoryRepository,
    session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val zone: ZoneId = laundryHistoryZone(signedIn?.business?.timezone)

    /** The laundry valet sees only their own requests (the database rules allow more, the app does not ask for more). */
    private val valetScope: String? = signedIn?.let { laundryHistoryScopeFor(it.user.role, it.user.uid) }

    private val _filters = MutableStateFlow(LaundryHistoryFilters(month = laundryHistoryCurrentMonth(zone)))
    internal val filters: StateFlow<LaundryHistoryFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<LaundryRequest>>> = retryTick
        .flatMapLatest { repository.observe(valetScope) }
        .catch { emit(Resource.Error(MSG_LAUNDRY_HISTORY_LOAD_FAILED, it)) }

    internal val view: StateFlow<LaundryHistoryView> = combine(live, _filters) { resource, filters ->
        laundryHistoryViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaundryHistoryView.Loading)

    fun setMonth(month: String) = _filters.update { it.copy(month = month.trim()) }
    fun setSearch(search: String) = _filters.update { it.copy(search = search) }
    fun setStatus(status: String) = _filters.update { it.copy(status = status) }
    fun retry() = retryTick.update { it + 1 }

    /** The file for Export CSV: the rows that are showing right now. Null while the page has no data to export. */
    internal fun exportFile(): LaundryHistoryExport? {
        val ready = view.value as? LaundryHistoryView.Ready ?: return null
        return laundryHistoryExportOf(ready.rows, _filters.value, zone)
    }

    /** The share sheet could not be opened: tell the person. */
    internal fun shareFailed(problem: Throwable) {
        toast.show(
            message = problem.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_HISTORY_SHARE_FAILED,
            type = ToastType.Error,
            title = "Error"
        )
    }
}
