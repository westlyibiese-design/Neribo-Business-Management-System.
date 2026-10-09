package com.westly.nbms.features.bar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import javax.inject.Inject

/** What the Drinks Menu page is showing. */
internal sealed interface DrinksView {
    data object Loading : DrinksView
    /** The menu failed to load: the page shows the error box and saving is blocked. */
    data class Error(val message: String) : DrinksView
    data class Ready(val items: List<DrinkItem>) : DrinksView
}

internal fun drinksViewOf(resource: Resource<List<DrinkItem>>): DrinksView = when (resource) {
    is Resource.Loading -> DrinksView.Loading
    is Resource.Error -> DrinksView.Error(MSG_DRINKS_LOAD_FAILED)
    is Resource.Success -> DrinksView.Ready(resource.data)
}

/** Reads the drinks menu live and saves one change at a time (add, edit, availability switch, delete). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DrinksMenuViewModel @Inject constructor(
    private val repository: DrinksMenuRepository,
    private val toast: ToastController,
    imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
) : ViewModel() {

    /** The first image-upload provider, or null while the image-upload phase is not installed (the form then has a plain URL box). */
    val imageProvider: ImageFieldProvider? = imageProviders.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    private val live: Flow<Resource<List<DrinkItem>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_DRINKS_LOAD_FAILED, it)) }

    internal val view: StateFlow<DrinksView> = live
        .map { drinksViewOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrinksView.Loading)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Save / Save Changes until the database answers. A second tap in that time does nothing. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of drinks whose availability switch or delete is running. */
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** "Reload": starts listening to the menu again after a failed load. */
    fun reload() {
        retryTick.update { it + 1 }
    }

    /** Adds a new drink (a fresh 8-character id). [onSaved] runs after a successful save so the form can close. */
    fun add(form: DrinkFormState, onSaved: () -> Unit) {
        val item = drinkFromForm(form, generateDrinkId())
        runSave(DrinkChange.Add(item), busyId = null) {
            toast.show(message = item.name, type = ToastType.Success, title = "Drink Added")
            onSaved()
        }
    }

    /** Saves the edited [form] over the drink with [id]. */
    fun update(id: String, form: DrinkFormState, onSaved: () -> Unit) {
        val item = drinkFromForm(form, id)
        runSave(DrinkChange.Replace(item), busyId = null) {
            toast.show(message = item.name, type = ToastType.Success, title = "Drink Updated")
            onSaved()
        }
    }

    /** The availability switch: saves at once. */
    fun setAvailable(item: DrinkItem, available: Boolean) {
        runSave(DrinkChange.SetAvailable(item.id, available), busyId = item.id) {
            toast.show(
                message = item.name,
                type = ToastType.Success,
                title = if (available) "Marked Available" else "Marked Unavailable"
            )
        }
    }

    /** Removes the drink from the menu (it is deleted from the array, not archived). */
    fun delete(item: DrinkItem, onDone: () -> Unit) {
        runSave(DrinkChange.Remove(item.id), busyId = item.id) {
            toast.show(message = item.name, type = ToastType.Success, title = "Drink Deleted")
            onDone()
        }
    }

    /**
     * Every save goes through here. While the menu has failed to load nothing is saved (a save could overwrite the real
     * menu with incomplete data) and the person gets the "Can't save yet" toast. A busy form or drink ignores a second tap.
     */
    private fun runSave(change: DrinkChange, busyId: String?, onSuccess: () -> Unit) {
        if (view.value is DrinksView.Error) {
            toast.show(message = MSG_BAR_CANT_SAVE, type = ToastType.Error, title = TITLE_BAR_CANT_SAVE)
            return
        }
        if (busyId != null) {
            if (busyId in _busyIds.value) return
            _busyIds.update { it + busyId }
        } else if (!_saving.compareAndSet(false, true)) {
            return
        }
        viewModelScope.launch {
            try {
                repository.save(change)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(
                    message = e.message ?: "Something went wrong. Please try again.",
                    type = ToastType.Error,
                    title = "Error"
                )
            } finally {
                if (busyId != null) _busyIds.update { it - busyId } else _saving.value = false
            }
        }
    }
}
