package com.westly.nbms.features.restaurant

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

/** What the Menu Management page is showing. */
internal sealed interface MenuView {
    data object Loading : MenuView
    /** The menu failed to load: the page shows the error box and saving is blocked. */
    data class Error(val message: String) : MenuView
    data class Ready(val items: List<MenuItem>) : MenuView
}

internal fun menuViewOf(resource: Resource<List<MenuItem>>): MenuView = when (resource) {
    is Resource.Loading -> MenuView.Loading
    is Resource.Error -> MenuView.Error(MSG_MENU_LOAD_FAILED)
    is Resource.Success -> MenuView.Ready(resource.data)
}

/** Reads the menu live and saves one change at a time (add, edit, availability switch, delete). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MenuManagementViewModel @Inject constructor(
    private val repository: MenuRepository,
    private val toast: ToastController,
    imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
) : ViewModel() {

    /** The first image-upload provider, or null while the image-upload phase is not installed (the form then has a plain URL box). */
    val imageProvider: ImageFieldProvider? = imageProviders.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    private val live: Flow<Resource<List<MenuItem>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_MENU_LOAD_FAILED, it)) }

    internal val view: StateFlow<MenuView> = live
        .map { menuViewOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MenuView.Loading)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Save / Save Changes until the database answers. A second tap in that time does nothing. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of items whose availability switch or delete is running. */
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** "Reload": starts listening to the menu again after a failed load. */
    fun reload() {
        retryTick.update { it + 1 }
    }

    /** Adds a new item (a fresh 8-character id). [onSaved] runs after a successful save so the form can close. */
    fun add(form: MenuForm, onSaved: () -> Unit) {
        val item = menuItemFromForm(form, generateMenuItemId())
        runSave(MenuChange.Add(item), busyId = null) {
            toast.show(message = item.name, type = ToastType.Success, title = "Item Added")
            onSaved()
        }
    }

    /** Saves the edited [form] over the item with [id]. */
    fun update(id: String, form: MenuForm, onSaved: () -> Unit) {
        val item = menuItemFromForm(form, id)
        runSave(MenuChange.Replace(item), busyId = null) {
            toast.show(message = item.name, type = ToastType.Success, title = "Item Updated")
            onSaved()
        }
    }

    /** The availability switch: saves at once. */
    fun setAvailable(item: MenuItem, available: Boolean) {
        runSave(MenuChange.SetAvailable(item.id, available), busyId = item.id) {
            toast.show(
                message = item.name,
                type = ToastType.Success,
                title = if (available) "Marked Available" else "Marked Unavailable"
            )
        }
    }

    /** Removes the item from the menu (like Westly it is deleted from the array, not archived). */
    fun delete(item: MenuItem, onDone: () -> Unit) {
        runSave(MenuChange.Remove(item.id), busyId = item.id) {
            toast.show(message = item.name, type = ToastType.Success, title = "Item Deleted")
            onDone()
        }
    }

    /**
     * Every save goes through here. While the menu has failed to load nothing is saved (a save could overwrite the real
     * menu with incomplete data) and the person gets the "Can't save yet" toast. A busy form or item ignores a second tap.
     */
    private fun runSave(change: MenuChange, busyId: String?, onSuccess: () -> Unit) {
        if (view.value is MenuView.Error) {
            toast.show(message = MSG_CANT_SAVE, type = ToastType.Error, title = TITLE_CANT_SAVE)
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
