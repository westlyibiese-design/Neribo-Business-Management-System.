package com.westly.nbms.features.bar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.inventory.InventoryItem
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
import javax.inject.Inject

internal const val TITLE_BAR_STOCK_FAILED = "Failed"

/** Bar Inventory: the live drinks stock with a search, Restock (everyone on the page) and Add Item (super admin and manager). */
@HiltViewModel
class BarInventoryViewModel @Inject constructor(
    private val repository: BarInventoryRepository,
    session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val role: Role? = (session.state.value as? SessionState.SignedIn)?.user?.role

    /** Add Item is for super admin and manager only. */
    internal val canAdd: Boolean = canAddBarStock(role)

    /** Restock is for everyone allowed to open the page. */
    internal val canRestock: Boolean = canRestockBarStock(role)

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Add Item / Restock until the database answers. A second tap in that time does nothing. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<InventoryItem>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_BAR_STOCK_LOAD_FAILED, it)) }

    internal val view: StateFlow<BarStockView> = combine(live, _search) { resource, search ->
        barStockViewOf(resource, search)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BarStockView.Loading)

    fun setSearch(search: String) = _search.update { search }
    fun retry() = retryTick.update { it + 1 }

    /** Adds the item. [onSaved] runs after a successful save so the dialog can close. */
    fun addItem(form: BarStockForm, onSaved: () -> Unit) {
        if (!canAdd) return
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                repository.add(form)
                toast.show("Item Added", ToastType.Success)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailed(e)
            } finally {
                _saving.value = false
            }
        }
    }

    /** Adds [amount] to the item's live quantity. [onDone] runs after a successful restock so the dialog can close. */
    fun restock(item: InventoryItem, amount: Int, onDone: () -> Unit) {
        if (!canRestock) return
        if (amount < 1) return
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = repository.restock(item, amount)
                toast.show("${item.name}: ${result.before} → ${result.after}", ToastType.Success, "Restocked")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailed(e)
            } finally {
                _saving.value = false
            }
        }
    }

    /** The server's own message, so a rules refusal is readable. */
    private fun showFailed(e: Exception) {
        toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_BAR_HISTORY_GENERIC, ToastType.Error, TITLE_BAR_STOCK_FAILED)
    }
}
