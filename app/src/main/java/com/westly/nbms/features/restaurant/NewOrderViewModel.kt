package com.westly.nbms.features.restaurant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal const val ORDER_PIN_SIGN_OUT_DELAY_MS = 2_500L

enum class OrderMode { FROM_MENU, MANUAL }

data class NewOrderUiState(
    val mode: OrderMode = OrderMode.FROM_MENU,
    val categoryKey: String = ORDER_FILTER_ALL,
    val cart: List<OrderCartLine> = emptyList(),
    val form: OrderForm = OrderForm(),
    val manualName: String = "",
    val manualPrice: String = "",
    val manualQuantity: String = "1",
    val showManualErrors: Boolean = false,
    val busy: Boolean = false,
    val success: OrderSuccess? = null
)

/** The menu as the item grid shows it. */
data class OrderMenuState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val items: List<MenuItem> = emptyList()
)

@HiltViewModel
class NewOrderViewModel @Inject internal constructor(
    private val orders: OrdersRepository,
    menuRepository: MenuRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(NewOrderUiState())
    val state: StateFlow<NewOrderUiState> = _state.asStateFlow()

    private val menuRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val menu: StateFlow<OrderMenuState> = menuRetry
        .flatMapLatest { menuRepository.observe() }
        .map { r ->
            when (r) {
                is Resource.Loading -> OrderMenuState(loading = true)
                is Resource.Error -> OrderMenuState(loading = false, failed = true)
                is Resource.Success -> OrderMenuState(loading = false, items = r.data)
            }
        }
        .catch { emit(OrderMenuState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrderMenuState())

    /** The synchronous "one submit at a time" flag; a second tap can never get past it. */
    private val inFlight = AtomicBoolean(false)

    fun retryMenu() = menuRetry.update { it + 1 }

    fun setMode(mode: OrderMode) = _state.update { it.copy(mode = mode) }
    fun setCategory(key: String) = _state.update { it.copy(categoryKey = key) }
    fun setRoomNumber(v: String) = _state.update { it.copy(form = it.form.copy(roomNumber = v)) }
    fun setTableNumber(v: String) = _state.update { it.copy(form = it.form.copy(tableNumber = v)) }
    fun setGuestName(v: String) = _state.update { it.copy(form = it.form.copy(guestName = v)) }
    fun setNotes(v: String) = _state.update { it.copy(form = it.form.copy(notes = v)) }
    fun setPayment(v: OrderPaymentMethod) = _state.update { it.copy(form = it.form.copy(payment = v)) }
    fun setManualName(v: String) = _state.update { it.copy(manualName = v) }
    fun setManualPrice(v: String) = _state.update { it.copy(manualPrice = filterOrderPriceInput(v)) }
    fun setManualQuantity(v: String) = _state.update { it.copy(manualQuantity = filterOrderQuantityInput(v)) }

    /** Tap on a menu card. */
    fun onItemTapped(item: MenuItem) = _state.update { it.copy(cart = addMenuItemToOrder(it.cart, item)) }

    fun increment(id: String) = _state.update { it.copy(cart = incrementOrderLine(it.cart, id)) }
    fun decrement(id: String) = _state.update { it.copy(cart = decrementOrderLine(it.cart, id)) }

    /** "Add to Order" in Manual Entry. */
    fun addManual() {
        val s = _state.value
        val errors = validateOrderManualItem(s.manualName, s.manualPrice, s.manualQuantity)
        if (errors.any) {
            _state.update { it.copy(showManualErrors = true) }
            return
        }
        val price = parseOrderPrice(s.manualPrice) ?: return
        val qty = parseOrderQuantity(s.manualQuantity) ?: return
        val line = manualOrderLine(s.manualName, price, qty, System.currentTimeMillis(), orderRandom6())
        _state.update {
            it.copy(
                cart = it.cart + line,
                manualName = "", manualPrice = "", manualQuantity = "1", showManualErrors = false
            )
        }
        toast.show("${line.name} added to order.", ToastType.Success, "Item added")
    }

    fun manualErrors(s: NewOrderUiState): OrderManualErrors? =
        if (s.showManualErrors) validateOrderManualItem(s.manualName, s.manualPrice, s.manualQuantity) else null

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val s = _state.value
        if (s.cart.isEmpty()) return
        if (!inFlight.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true) }

        val symbol = signedIn.business.currencySymbol
        val usesPin = signedIn.user.usesPin
        val cart = s.cart
        val form = s.form

        viewModelScope.launch {
            try {
                val result = orders.place(cart, form)
                _state.update {
                    it.copy(
                        success = OrderSuccess(result.itemCount, result.total),
                        cart = emptyList(), form = OrderForm() // the payment goes back to Cash with the rest
                    )
                }
                toast.show("${Format.currency(result.total, symbol)} order sent to kitchen.", ToastType.Success, "Order Placed!")
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_ORDER_GENERIC, ToastType.Error, "Failed")
            } finally {
                inFlight.set(false)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Shared-device (PIN) sessions end by themselves after the order, like Westly. */
    private fun scheduleSignOut() {
        viewModelScope.launch {
            delay(ORDER_PIN_SIGN_OUT_DELAY_MS)
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The person can still sign out by hand.
            }
        }
    }

    /** "New Order" on the success screen. */
    fun newOrder() = _state.update { it.copy(success = null) }
}
