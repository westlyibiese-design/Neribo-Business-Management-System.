package com.westly.nbms.features.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
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

/** The two controls above the list. [categoryKey] is a stored category key, or empty for "All Categories". */
internal data class InventoryFilters(val search: String = "", val categoryKey: String = "")

/**
 * What the Inventory page is showing.
 * [Ready.totalCount] and [Ready.lowItems] are worked out from ALL non-deleted items (before the filters);
 * [Ready.rows] is the filtered list.
 */
internal sealed interface InventoryView {
    data object Loading : InventoryView
    data class Error(val message: String) : InventoryView
    data class Ready(
        val totalCount: Int,
        val lowItems: List<InventoryItem>,
        val rows: List<InventoryItem>,
        /** Every non-deleted item, unfiltered (the Restock dialog looks its item up here). */
        val all: List<InventoryItem> = emptyList()
    ) : InventoryView
}

// ── pure helpers (unit-tested without Android) ──

/** Items whose name contains [search] (trimmed, ignoring case) and, when [categoryKey] is not empty, that category. Order is kept. */
internal fun filterInventory(items: List<InventoryItem>, search: String, categoryKey: String): List<InventoryItem> {
    val needle = search.trim()
    return items.filter { item ->
        (categoryKey.isEmpty() || item.category == categoryKey) &&
            (needle.isEmpty() || item.name.contains(needle, ignoreCase = true))
    }
}

/** Every item at or below its minimum stock, in list order. */
internal fun lowStockItems(items: List<InventoryItem>): List<InventoryItem> = items.filter { InventoryLogic.isLow(it) }

/** "Bottled Water: 3/5 bottles". */
internal fun lowStockChipText(item: InventoryItem): String = "${item.name}: ${item.quantity}/${item.minStock} ${item.unit}"

/** "Low Stock Alert (1 items)": Westly prints "items" even for one. */
internal fun lowStockHeading(count: Int): String = "Low Stock Alert ($count items)"

internal fun inventoryViewOf(resource: Resource<List<InventoryItem>>, filters: InventoryFilters): InventoryView = when (resource) {
    is Resource.Loading -> InventoryView.Loading
    is Resource.Error -> InventoryView.Error(MSG_INVENTORY_LOAD_FAILED)
    is Resource.Success -> {
        val all = resource.data.filter { !it.isDeleted }
        InventoryView.Ready(
            totalCount = all.size,
            lowItems = lowStockItems(all),
            rows = filterInventory(all, filters.search, filters.categoryKey),
            all = all
        )
    }
}

/** Reads `inventory` live, adds items and restocks them. */
@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val toast: ToastController
) : ViewModel() {

    private val _filters = MutableStateFlow(InventoryFilters())
    internal val filters: StateFlow<InventoryFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Add Item / Restock until the database answers. A second tap in that time does nothing. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<InventoryItem>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_INVENTORY_LOAD_FAILED, it)) }

    internal val view: StateFlow<InventoryView> = combine(live, _filters) { resource, filters ->
        inventoryViewOf(resource, filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryView.Loading)

    fun setSearch(search: String) {
        _filters.update { it.copy(search = search) }
    }

    fun setCategory(categoryKey: String) {
        _filters.update { it.copy(categoryKey = categoryKey) }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Adds the item. [onSaved] runs after a successful save so the dialog can close and reset. */
    fun addItem(form: AddItemForm, onSaved: () -> Unit) {
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                repository.add(form)
                toast.show(message = "Item Added", type = ToastType.Success)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                _saving.value = false
            }
        }
    }

    /** Adds [amount] to the item's live quantity. [onDone] runs after a successful restock so the dialog can close. */
    fun restock(item: InventoryItem, amount: Int, onDone: () -> Unit) {
        if (amount < 1) return
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = repository.restock(item, amount)
                toast.show(
                    message = "${item.name}: ${result.before} → ${result.after}",
                    type = ToastType.Success,
                    title = "Restocked"
                )
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                _saving.value = false
            }
        }
    }

    private fun showError(e: Exception) {
        toast.show(
            message = e.message ?: "Something went wrong. Please try again.",
            type = ToastType.Error,
            title = "Error"
        )
    }
}
