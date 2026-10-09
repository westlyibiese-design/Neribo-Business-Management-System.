package com.westly.nbms.features.rooms

import kotlinx.datetime.Instant

/** The only message shown when someone tries to free a room that still has a guest in it (copied from Westly). */
const val MSG_ROOM_STILL_OCCUPIED =
    "This room still has a guest checked in and cannot be marked Available. The guest must be checked out first — see Check-Out."

/** Booking-lock statuses that block a room (Westly `roomLogic.ts`). */
val BLOCKING_BOOKING_STATUSES: List<String> = listOf("confirmed", "checked_in", "pending")

/** Port of Westly `roomLogic.ts`. Hilt `@Singleton`. Later phases (bookings, check-in, check-out, housekeeping) call this. */
interface RoomLogic {
    /** `aIn < bOut && aOut > bIn`. */
    fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean

    /** Reads `booking_dates` with status in confirmed | checked_in | pending. [excludeBookingId] is the lock id to ignore. */
    suspend fun detectConflict(roomId: String, checkIn: Instant, checkOut: Instant, excludeBookingId: String? = null): Boolean

    suspend fun findAvailableRooms(roomType: String, checkIn: Instant, checkOut: Instant): List<Room>

    /** Throws [IllegalStateException] with the Westly message if AVAILABLE is requested while `currentBookingId != null`. */
    suspend fun updateRoomStatus(
        roomId: String,
        newStatus: RoomStatus,
        extra: Map<String, Any?> = emptyMap(),
        allowOccupiedOverride: Boolean = false
    )

    suspend fun updateRoomCleanliness(roomId: String, cleanliness: Cleanliness, extra: Map<String, Any?> = emptyMap())

    fun getRoomDisplayStatus(room: Room): RoomDisplayStatus

    suspend fun triggerRoomStatus(roomId: String, event: RoomEvent)
}
