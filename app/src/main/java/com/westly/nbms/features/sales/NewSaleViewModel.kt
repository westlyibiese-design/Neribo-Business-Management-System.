package com.westly.nbms.features.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.inventory.InventoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal const val PIN_SIGN_OUT_DELAY_MS = 2_500L

enum class ItemMode { INVENTORY, MANUAL }

data class NewSaleUiState(
    val mode: ItemMode = ItemMode.INVENTORY,
    val category: String = ALL_CATEGORIES,
    val cart: List<CartItem> = emptyList(),
    val customerName: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val manualName: String = "",
    val manualPrice: String = "",
    val manualQuantity: String = "1",
    val showManualErrors: Boolean = false,
    val busy: Boolean = false,
    val success: SaleSuccess? = null
)

/** The inventory as the item grid shows it. */
data class CatalogState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val items: List<InventoryItem> = emptyList()
)

@HiltViewModel
class NewSaleViewModel @Inject internal constructor(
    private val repository: SalesRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(NewSaleUiState())
    val state: StateFlow<NewSaleUiState> = _state.asStateFlow()

    val catalog: StateFlow<CatalogState> = repository.observeInventory()
        .map { r ->
            when (r) {
                is Resource.Loading -> CatalogState(loading = true)
                is Resource.Error -> CatalogState(loading = false, failed = true)
                is Resource.Success -> CatalogState(loading = false, items = r.data.filter { !it.isDeleted })
            }
        }
        .catch { emit(CatalogState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogState())

    /** The synchronous "one submit at a time" flag; a second tap can never get past it. */
    private val inFlight = AtomicBoolean(false)

    fun setMode(mode: ItemMode) = _state.update { it.copy(mode = mode) }
    fun setCategory(category: String) = _state.update { it.copy(category = category) }
    fun setCustomerName(v: String) = _state.update { it.copy(customerName = v) }
    fun setPaymentMethod(v: PaymentMethod) = _state.update { it.copy(paymentMethod = v) }
    fun setManualName(v: String) = _state.update { it.copy(manualName = v) }
    fun setManualPrice(v: String) = _state.update { it.copy(manualPrice = filterPriceInput(v)) }
    fun setManualQuantity(v: String) = _state.update { it.copy(manualQuantity = filterQuantityInput(v)) }

    /** Tap on a catalog card. */
    fun onItemTapped(item: InventoryItem) {
        when (val result = addCatalogItem(_state.value.cart, item)) {
            is CartAdd.Added -> _state.update { it.copy(cart = result.cart) }
            CartAdd.StockLimit -> toast.show(MSG_STOCK_LIMIT, ToastType.Error, "Stock limit")
        }
    }

    fun increment(id: String) = _state.update { it.copy(cart = incrementLine(it.cart, id)) }
    fun decrement(id: String) = _state.update { it.copy(cart = decrementLine(it.cart, id)) }

    /** "Add to Cart" in Manual Entry. */
    fun addManual() {
        val s = _state.value
        val errors = validateManualItem(s.manualName, s.manualPrice, s.manualQuantity)
        if (errors.any) {
            _state.update { it.copy(showManualErrors = true) }
            return
        }
        val price = parseManualPrice(s.manualPrice) ?: return
        val qty = parseManualQuantity(s.manualQuantity) ?: return
        val line = manualCartItem(s.manualName, price, qty, System.currentTimeMillis(), random6())
        _state.update {
            it.copy(
                cart = it.cart + line,
                manualName = "", manualPrice = "", manualQuantity = "1", showManualErrors = false
            )
        }
        toast.show("${line.name} added to cart.", ToastType.Success, "Item added")
    }

    fun manualErrors(s: NewSaleUiState): ManualErrors? =
        if (s.showManualErrors) validateManualItem(s.manualName, s.manualPrice, s.manualQuantity) else null

    fun submit() {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        val s = _state.value
        if (s.cart.isEmpty()) return
        if (!inFlight.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true) }

        val symbol = signedIn.business.currencySymbol
        val zone = zoneOf(signedIn.business.timezone)
        val usesPin = signedIn.user.usesPin
        val cart = s.cart

        viewModelScope.launch {
            try {
                val result = repository.sell(cart, s.customerName, s.paymentMethod, null)
                val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.now().atZone(zone))
                _state.update {
                    it.copy(
                        success = SaleSuccess(cart, result.itemCount, result.total, time),
                        cart = emptyList(), customerName = "", paymentMethod = PaymentMethod.CASH
                    )
                }
                toast.show("${Format.currency(result.total, symbol)} sale saved.", ToastType.Success, "Sale Recorded")
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_SALE_GENERIC, ToastType.Error, "Sale Failed")
            } finally {
                inFlight.set(false)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Shared-device (PIN) sessions end by themselves after the sale, like Westly. */
    private fun scheduleSignOut() {
        viewModelScope.launch {
            delay(PIN_SIGN_OUT_DELAY_MS)
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // The person can still sign out by hand.
            }
        }
    }

    /** "New Sale" on the success screen. */
    fun newSale() = _state.update { it.copy(success = null) }
}
