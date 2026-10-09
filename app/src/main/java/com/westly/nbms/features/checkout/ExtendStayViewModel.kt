package com.westly.nbms.features.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.rooms.Room
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class ExtendStayViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val repository: ExtendStayRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _busy = MutableStateFlow(false)

    /** True while an extension is being saved; the dialog cannot be closed. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The synchronous "one extension at a time" flag. */
    private val inFlight = AtomicBoolean(false)

    val role: Role?
        get() = (session.state.value as? SessionState.SignedIn)?.user?.role

    val currencySymbol: String
        get() = (session.state.value as? SessionState.SignedIn)?.business?.currencySymbol ?: "₦"

    val zone: TimeZone
        get() = zoneOrLagos((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    /** Live `settings/hotel.checkOutTime`, "11:00" until it is known. */
    val checkOutTime: StateFlow<String> = firestore.observeDoc("settings", "hotel", CheckOutHotelSettings::class.java)
        .map { r -> if (r is Resource.Success) officialCheckOutTime(r.data?.checkOutTime) else DEFAULT_CHECK_OUT_TIME }
        .catch { emit(DEFAULT_CHECK_OUT_TIME) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_CHECK_OUT_TIME)

    /** The room's own price: the last fallback of the nightly rate. Null while loading or unknown. */
    fun roomPrice(roomId: String): Flow<Double?> =
        firestore.observeDoc("rooms", roomId, Room::class.java)
            .map { r -> if (r is Resource.Success) r.data?.price else null }
            .catch { emit(null) }

    fun confirm(booking: Booking, extraNights: Int, method: ExtendPaymentMethod, onDone: () -> Unit) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return
        if (!inFlight.compareAndSet(false, true)) return
        _busy.value = true
        val request = ExtendRequest(
            extraNights = extraNights,
            method = method,
            role = signedIn.user.role,
            staff = CheckOutStaff(signedIn.user.uid, signedIn.user.name),
            zone = zoneOrLagos(signedIn.business.timezone)
        )
        viewModelScope.launch {
            try {
                when (val result = repository.extend(booking, request)) {
                    is ExtendResult.Done -> {
                        toast.show(extendedMessage(result.roomNumber, result.newCheckOutText), ToastType.Success, TITLE_EXTEND_DONE)
                        onDone()
                    }
                    is ExtendResult.Rejected -> toast.show(result.message, ToastType.Error, result.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC, ToastType.Error, TITLE_EXTEND_FAILED)
            } finally {
                inFlight.set(false)
                _busy.update { false }
            }
        }
    }
}
