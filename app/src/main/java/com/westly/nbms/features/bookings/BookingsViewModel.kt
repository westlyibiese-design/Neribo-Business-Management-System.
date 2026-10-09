package com.westly.nbms.features.bookings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class BookingsUiState(
    /** True while a status change runs; the buttons are disabled. */
    val busy: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookingsViewModel @Inject constructor(
    private val repository: BookingsRepository,
    private val toast: ToastController,
    extendStayLaunchers: @JvmSuppressWildcards Set<ExtendStayLauncher>
) : ViewModel() {

    private val _state = MutableStateFlow(BookingsUiState())
    val state: StateFlow<BookingsUiState> = _state.asStateFlow()

    /** The Extend Stay dialog of Phase 14, or null until it is installed (then no Extend Stay button is shown). */
    val extendStay: ExtendStayLauncher? = extendStayLaunchers.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    /** Live bookings without deleted ones, newest first. */
    val bookings: StateFlow<Resource<List<Booking>>> = retryTick
        .flatMapLatest { repository.observeBookings() }
        .map { r -> if (r is Resource.Success) Resource.Success(visibleBookings(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load bookings.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Confirm / reject / cancel / no-show. [onSuccess] runs after a saved change so the dialog can close. */
    fun changeStatus(booking: Booking, newStatus: BookingStatus, onSuccess: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val failure = repository.changeStatus(booking, newStatus).exceptionOrNull()
                if (failure == null) {
                    toast.show(statusChangedMessage(newStatus.key), ToastType.Success, "Status Updated")
                    onSuccess()
                } else {
                    toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Error")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Error")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

internal const val MSG_GENERIC = "Something went wrong. Please try again."
