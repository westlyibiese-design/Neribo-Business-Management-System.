package com.westly.nbms.features.bookings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** What the Guests page shows: the live guests plus every booking (for the stay history). */
data class GuestsData(val guests: List<Guest>, val bookings: List<Booking>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuestsViewModel @Inject constructor(
    private val repository: BookingsRepository
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    val data: StateFlow<Resource<GuestsData>> = retryTick
        .flatMapLatest {
            combine(repository.observeGuests(), repository.observeBookings()) { guests, bookings ->
                when (guests) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> Resource.Error("We couldn't load guests.", guests.cause)
                    is Resource.Success -> Resource.Success(
                        GuestsData(
                            guests = visibleGuests(guests.data),
                            // History is a bonus: if bookings cannot be read, guests still show (with no history).
                            bookings = (bookings as? Resource.Success)?.data?.filter { !it.isDeleted }.orEmpty()
                        )
                    )
                }
            }
        }
        .catch { emit(Resource.Error("We couldn't load guests.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }
}
