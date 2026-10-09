package com.westly.nbms.features.bar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** Sales History: the live `bar_orders` list with its filters, and Mark Served. */
@HiltViewModel
class BarSalesHistoryViewModel @Inject constructor(
    private val repository: BarSalesHistoryRepository,
    session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val zone: ZoneId = barHistoryZone(signedIn?.business?.timezone)

    /** The role of the person using the page; decides who sees Mark Served. */
    internal val role: Role? = signedIn?.user?.role

    /** The bar attendant sees only their own sales (the database rules allow more, the app does not ask for more). */
    private val attendantScope: String? = signedIn?.let { barHistoryScopeFor(it.user.role, it.user.uid) }

    private val _filters = MutableStateFlow(BarHistoryFilters(month = barHistoryCurrentMonth(zone)))
    internal val filters: StateFlow<BarHistoryFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<BarSale>>> = retryTick
        .flatMapLatest { repository.observe(attendantScope) }
        .catch { emit(Resource.Error(MSG_BAR_HISTORY_LOAD_FAILED, it)) }

    internal val view: StateFlow<BarHistoryView> = combine(live, _filters) { resource, filters ->
        barHistoryViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BarHistoryView.Loading)

    /** Sales whose Mark Served is in flight. A second tap on the same sale does nothing. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun setMonth(month: String) = _filters.update { it.copy(month = month.trim()) }
    fun setSearch(search: String) = _filters.update { it.copy(search = search) }
    fun setStatus(status: String) = _filters.update { it.copy(status = status) }
    fun retry() = retryTick.update { it + 1 }

    /** Marks a pending sale as served. The in-flight guard is set before any suspend call and cleared in `finally`. */
    fun markServed(sale: BarSale) {
        if (!showMarkServed(role, sale)) return
        if (sale.id in _busy.value) return
        _busy.update { it + sale.id }
        viewModelScope.launch {
            try {
                repository.markServed(sale)
                toast.show(MSG_BAR_SALE_UPDATED, ToastType.Success, TITLE_BAR_SALE_UPDATED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(
                    e.message?.takeIf { it.isNotBlank() } ?: MSG_BAR_HISTORY_GENERIC,
                    ToastType.Error,
                    TITLE_BAR_SALE_UPDATE_FAILED
                )
            } finally {
                _busy.update { it - sale.id }
            }
        }
    }
}
