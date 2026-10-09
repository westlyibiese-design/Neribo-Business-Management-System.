package com.westly.nbms.features.bar

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

internal const val BAR_PIN_SIGN_OUT_DELAY_MS = 2_500L

enum class BarSaleMode { FROM_MENU, MANUAL }

data class BarNewSaleUiState(
    val mode: BarSaleMode = BarSaleMode.FROM_MENU,
    val categoryKey: String = BAR_FILTER_ALL,
    val cart: List<BarCartLine> = emptyList(),
    val form: BarSaleForm = BarSaleForm(),
    val manualName: String = "",
    val manualPrice: String = "",
    val manualQuantity: String = "1",
    val showManualErrors: Boolean = false,
    val busy: Boolean = false,
    val success: BarSaleSuccess? = null
)

/** The drinks menu as the New Sale grid shows it. */
data class BarMenuState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val items: List<DrinkItem> = emptyList()
)

@HiltViewModel
class BarNewSaleViewModel @Inject internal constructor(
    private val sales: BarSalesRepository,
    menuRepository: DrinksMenuRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(BarNewSaleUiState())
    val state: StateFlow<BarNewSaleUiState> = _state.asStateFlow()

    private val menuRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val menu: StateFlow<BarMenuState> = menuRetry
        .flatMapLatest { menuRepository.observe() }
        .map { r ->
            when (r) {
                is Resource.Loading -> BarMenuState(loading = true)
                is Resource.Error -> BarMenuState(loading = false, failed = true)
                is Resource.Success -> BarMenuState(loading = false, items = r.data)
            }
        }
        .catch { emit(BarMenuState(loading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BarMenuState())

    /** The synchronous "one submit at a time" flag; a second tap can never get past it. */
    private val inFlight = AtomicBoolean(false)

    fun retryMenu() = menuRetry.update { it + 1 }

    fun setMode(mode: BarSaleMode) = _state.update { it.copy(mode = mode) }
    fun setCategory(key: String) = _state.update { it.copy(categoryKey = key) }
    fun setRoomNumber(v: String) = _state.update { it.copy(form = it.form.copy(roomNumber = v)) }
    fun setTableNumber(v: String) = _state.update { it.copy(form = it.form.copy(tableNumber = v)) }
    fun setGuestName(v: String) = _state.update { it.copy(form = it.form.copy(guestName = v)) }
    fun setNotes(v: String) = _state.update { it.copy(form = it.form.copy(notes = v)) }
    fun setPayment(v: BarPaymentMethod) = _state.update { it.copy(form = it.form.copy(payment = v)) }
    fun setManualName(v: String) = _state.update { it.copy(manualName = v) }
    fun setManualPrice(v: String) = _state.update { it.copy(manualPrice = filterBarPriceInput(v)) }
    fun setManualQuantity(v: String) = _state.update { it.copy(manualQuantity = filterBarQuantityInput(v)) }

    /** Tap on a drink card. */
    fun onDrinkTapped(item: DrinkItem) = _state.update { it.copy(cart = addDrinkToCart(it.cart, item)) }

    fun increment(id: String) = _state.update { it.copy(cart = incrementBarLine(it.cart, id)) }
    fun decrement(id: String) = _state.update { it.copy(cart = decrementBarLine(it.cart, id)) }

    /** "Add to Sale" in Manual Entry. */
    fun addManual() {
        val s = _state.value
        val errors = validateBarManualItem(s.manualName, s.manualPrice, s.manualQuantity)
        if (errors.any) {
            _state.update { it.copy(showManualErrors = true) }
            return
        }
        val price = parseBarPrice(s.manualPrice) ?: return
        val qty = parseBarQuantity(s.manualQuantity) ?: return
        val line = manualBarLine(s.manualName, price, qty, System.currentTimeMillis(), barRandom6())
        _state.update {
            it.copy(
                cart = it.cart + line,
                manualName = "", manualPrice = "", manualQuantity = "1", showManualErrors = false
            )
        }
        toast.show("${line.name} added to sale.", ToastType.Success, "Item added")
    }

    fun manualErrors(s: BarNewSaleUiState): BarManualErrors? =
        if (s.showManualErrors) validateBarManualItem(s.manualName, s.manualPrice, s.manualQuantity) else null

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
                val result = sales.place(cart, form)
                _state.update {
                    it.copy(
                        success = BarSaleSuccess(result.itemCount, result.total),
                        cart = emptyList(), form = BarSaleForm() // the payment goes back to Cash with the rest
                    )
                }
                toast.show("${Format.currency(result.total, symbol)} bar sale recorded.", ToastType.Success, "Sale Recorded!")
                if (usesPin) scheduleSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_BAR_SALE_GENERIC, ToastType.Error, "Failed")
            } finally {
                inFlight.set(false)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Shared-device (PIN) sessions end by themselves after the sale. */
    private fun scheduleSignOut() {
        viewModelScope.launch {
            delay(BAR_PIN_SIGN_OUT_DELAY_MS)
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
