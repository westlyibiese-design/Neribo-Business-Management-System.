package com.westly.nbms.features.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the inbox shows before the list itself. */
enum class InboxLoad { LOADING, READY, FAILED }

data class MessagesUiState(
    val load: InboxLoad = InboxLoad.LOADING,
    /** True while a pull-down or resume refresh runs. */
    val refreshing: Boolean = false,
    /** True while a reply-status change or removal runs; the dialog buttons are disabled. */
    val busy: Boolean = false
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val api: MessagesApi,
    private val toast: ToastController
) : ViewModel() {

    /** Shared with the drawer badge, so both always agree. */
    val messages: StateFlow<List<InboxMessage>> = api.messages
    val unreadCount: StateFlow<Int> = api.unreadCount

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        // Same tracker as the drawer badge (idempotent): live updates while this screen is open.
        api.ensureTracking()
    }

    /** First load, Retry, pull-down and screen resume all come here. Always re-fetches (one at a time). */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val firstLoad = _state.value.load != InboxLoad.READY
            _state.update { it.copy(refreshing = !firstLoad, load = if (firstLoad) InboxLoad.LOADING else it.load) }
            api.list()
                .onSuccess { _state.update { s -> s.copy(load = InboxLoad.READY, refreshing = false) } }
                .onFailure {
                    // A failed refresh of an already loaded list keeps the list; a failed first load shows the error.
                    _state.update { s ->
                        s.copy(load = if (s.load == InboxLoad.READY) InboxLoad.READY else InboxLoad.FAILED, refreshing = false)
                    }
                }
        }
    }

    /** Opening a new message shows it at once as read; the server call is silent and its failure is ignored. */
    fun opened(message: InboxMessage) {
        if (!message.isNew) return
        viewModelScope.launch { api.markRead(message.id) }
    }

    fun setReplyStatus(id: String, status: ReplyStatus) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            api.setReplyStatus(id, status)
                .onSuccess { toast.show("Marked as \"${status.label}\".", ToastType.Success, title = "Updated") }
                .onFailure { toast.show("Please try again.", ToastType.Error, title = "Couldn't update") }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Removes the message (soft delete). [onDone] runs only after it worked, so the dialog can close. */
    fun remove(id: String, onDone: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            api.softDelete(id)
                .onSuccess {
                    toast.show("Message removed", ToastType.Success)
                    onDone()
                }
                .onFailure { toast.show("Couldn't remove message", ToastType.Error) }
            _state.update { it.copy(busy = false) }
        }
    }

    fun showError(message: String) {
        toast.show(message, ToastType.Error)
    }
}
