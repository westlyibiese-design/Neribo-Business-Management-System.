package com.westly.nbms.features.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.notify.AppNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NotificationsTab { All, Unread }

data class NotificationsUiState(
    val tab: NotificationsTab = NotificationsTab.All,
    val feed: FeedState = FeedState.Loading,
    val markingAll: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
    private val navigator: ShellNavigator,
    private val toast: ToastController
) : ViewModel() {

    private val tab = MutableStateFlow(NotificationsTab.All)
    private val markingAll = MutableStateFlow(false)
    private val retryTick = MutableStateFlow(0)

    val state: StateFlow<NotificationsUiState> = combine(
        tab,
        retryTick.flatMapLatest { repository.feedState(PAGE_LIMIT) },
        markingAll
    ) { t, feed, busy -> NotificationsUiState(t, feed, busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    fun selectTab(value: NotificationsTab) {
        tab.value = value
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Marks the row read and, when it has a link, opens that screen. */
    fun open(n: AppNotification, uid: String) {
        if (isUnread(n, uid)) viewModelScope.launch { repository.markRead(n.id) }
        n.link?.takeIf { it.isNotBlank() }?.let { navigator.open(it) }
    }

    fun markAllRead() {
        if (markingAll.value) return
        markingAll.value = true
        viewModelScope.launch {
            repository.markAllRead().onFailure {
                toast.show("Couldn't mark notifications as read. Please try again.", ToastType.Error)
            }
            markingAll.value = false
        }
    }

    fun removeForMe(n: AppNotification) {
        viewModelScope.launch {
            repository.removeForMe(n.id).onFailure {
                toast.show("Couldn't remove this notification. Please try again.", ToastType.Error)
            }
        }
    }
}
