package com.westly.nbms.features.reservations

import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus

/** What the Room Reservations screen calls for every write. @Singleton, bound to [ReservationsRepository]. */
interface ReservationsService {
    /** settings/hotel.checkOutTime, "11:00" when missing or unreadable; never throws. */
    suspend fun loadCheckOutTime(): String

    /** Allowed: CONFIRMED, REJECTED, CANCELLED, NO_SHOW (anything else -> failure IllegalArgumentException). Failure message is user-facing text. */
    suspend fun changeStatus(booking: Booking, newStatus: BookingStatus): Result<Unit>

    /** Runs spec 2.7 steps 2-3 only (transaction + best-effort steps). Failure message is user-facing text. */
    suspend fun checkIn(booking: Booking, form: CheckInForm): Result<CheckInOutcome>
}
