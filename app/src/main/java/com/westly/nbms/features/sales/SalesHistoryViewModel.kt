package com.westly.nbms.features.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
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
import java.time.ZoneId
import javax.inject.Inject

/** The two controls above the list. [month] is "yyyy-MM", or empty for every month. */
data class SalesFilters(val month: String, val search: String)

sealed interface SalesHistoryView {
    data object Loading : SalesHistoryView
    data class Error(val message: String) : SalesHistoryView
    data class Ready(val rows: List<Sale>, val total: Double) : SalesHistoryView
}

internal fun salesViewOf(resource: Resource<List<Sale>>, filters: SalesFilters, zone: ZoneId): SalesHistoryView = when (resource) {
    is Resource.Loading -> SalesHistoryView.Loading
    is Resource.Error -> SalesHistoryView.Error(MSG_SALES_LOAD_FAILED)
    is Resource.Success -> {
        val rows = filterSales(resource.data, filters.search, filters.month, zone)
        SalesHistoryView.Ready(rows, salesTotal(rows))
    }
}

@HiltViewModel
class SalesHistoryViewModel @Inject internal constructor(
    private val repository: SalesRepository,
    session: SessionManager
) : ViewModel() {

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val zone: ZoneId = zoneOf(signedIn?.business?.timezone)

    /** The staff role sees only its own sales (the database enforces the same). */
    private val onlyStaffId: String? = signedIn?.takeIf { it.user.role == Role.STAFF }?.user?.uid

    private val _filters = MutableStateFlow(SalesFilters(month = currentMonth(zone), search = ""))
    val filters: StateFlow<SalesFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<Sale>>> = retryTick
        .flatMapLatest { repository.observeSales(onlyStaffId) }
        .catch { emit(Resource.Error(MSG_SALES_LOAD_FAILED, it)) }

    val view: StateFlow<SalesHistoryView> = combine(live, _filters) { resource, filters ->
        salesViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SalesHistoryView.Loading)

    fun setMonth(month: String) = _filters.update { it.copy(month = month.trim()) }
    fun setSearch(search: String) = _filters.update { it.copy(search = search) }
    fun retry() = retryTick.update { it + 1 }
}
