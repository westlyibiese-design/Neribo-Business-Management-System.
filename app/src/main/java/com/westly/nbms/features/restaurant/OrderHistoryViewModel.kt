package com.westly.nbms.features.restaurant

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

/** The three controls above the list. [month] is "yyyy-MM" (empty = every month); [status] is a status key or "all". */
data class OrderFilters(val month: String, val search: String, val status: String = ORDER_FILTER_ALL)

sealed interface OrderHistoryView {
    data object Loading : OrderHistoryView
    data class Error(val message: String) : OrderHistoryView
    data class Ready(val rows: List<Order>, val total: Double) : OrderHistoryView
}

internal fun orderViewOf(resource: Resource<List<Order>>, filters: OrderFilters, zone: ZoneId): OrderHistoryView = when (resource) {
    is Resource.Loading -> OrderHistoryView.Loading
    is Resource.Error -> OrderHistoryView.Error(MSG_ORDERS_LOAD_FAILED)
    is Resource.Success -> {
        val rows = filterOrders(resource.data, filters.search, filters.status, filters.month, zone)
        OrderHistoryView.Ready(rows, ordersTotal(rows))
    }
}

@HiltViewModel
class OrderHistoryViewModel @Inject internal constructor(
    private val repository: OrdersRepository,
    session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val zone: ZoneId = orderZoneOf(signedIn?.business?.timezone)

    /** The waiter role sees only its own orders (the database enforces the same). */
    private val onlyWaiterId: String? = signedIn?.takeIf { it.user.role == Role.WAITER }?.user?.uid

    /** Only Super Admin, Waiter and Manager may change kitchen status. */
    private val mayChangeStatus: Boolean = signedIn?.let { canChangeOrderStatus(it.user.role) } == true

    private val _filters = MutableStateFlow(OrderFilters(month = orderCurrentMonth(zone), search = ""))
    val filters: StateFlow<OrderFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<Order>>> = retryTick
        .flatMapLatest { repository.observeOrders(onlyWaiterId) }
        .catch { emit(Resource.Error(MSG_ORDERS_LOAD_FAILED, it)) }

    val view: StateFlow<OrderHistoryView> = combine(live, _filters) { resource, filters ->
        orderViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrderHistoryView.Loading)

    /** Rows whose status change is in flight: order id -> the status it is moving to. */
    private val _busy = MutableStateFlow<Map<String, OrderStatus>>(emptyMap())
    val busy: StateFlow<Map<String, OrderStatus>> = _busy.asStateFlow()

    fun setMonth(month: String) = _filters.update { it.copy(month = month.trim()) }
    fun setSearch(search: String) = _filters.update { it.copy(search = search) }
    fun setStatus(status: String) = _filters.update { it.copy(status = status) }
    fun retry() = retryTick.update { it + 1 }

    /** A status button on a row. One change per row at a time. */
    fun changeStatus(order: Order, to: OrderStatus) {
        if (!mayChangeStatus) return
        if (_busy.value.containsKey(order.id)) return
        _busy.update { it + (order.id to to) }
        viewModelScope.launch {
            try {
                repository.updateStatus(order.id, order.status, to)
                toast.show(orderStatusToast(to), ToastType.Success, "Order Updated")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_ORDER_UPDATE_GENERIC, ToastType.Error, "Update Failed")
            } finally {
                _busy.update { it - order.id }
            }
        }
    }
}
