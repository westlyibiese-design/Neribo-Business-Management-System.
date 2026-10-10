package com.westly.nbms.features.opslog

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.rooms.Cleanliness
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomDisplayStatus
import com.westly.nbms.features.rooms.RoomEvent
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

internal val MT_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")

internal fun mtRequest(
    id: String,
    priority: MaintenancePriority = MaintenancePriority.MEDIUM,
    status: String = "open",
    room: String = "202",
    roomId: String? = "r2",
    title: String = "AC not cooling",
    createdAt: Instant? = MT_NOW,
    closedAt: Instant? = null,
    deleted: Boolean = false
) = MaintenanceRequest(
    id = id, roomId = roomId, roomNumber = room, title = title, description = null, priority = priority, status = status,
    reportedBy = "u1", reportedByName = "Ada", createdAt = createdAt, closedAt = closedAt, closedBy = null, closedByName = null,
    isDeleted = deleted
)

internal val MT_ROOMS = listOf(
    MaintenanceRoomOption("r2", "202", "Deluxe Room"),
    MaintenanceRoomOption("r3", "203", "Standard Room")
)

/** In-memory `maintenance`. Records every create and update. */
internal class MtFakeStore : MaintenanceStore {
    val requests = MutableStateFlow<Resource<List<MaintenanceRequest>>>(Resource.Success(emptyList()))
    val rooms = MutableStateFlow<Resource<List<MaintenanceRoomOption>>>(Resource.Success(MT_ROOMS))
    val created = mutableListOf<Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failWith: Exception? = null

    override fun observe(): Flow<Resource<List<MaintenanceRequest>>> = requests
    override fun observeRooms(): Flow<Resource<List<MaintenanceRoomOption>>> = rooms

    override suspend fun create(payload: Map<String, Any?>): String {
        failWith?.let { throw it }
        created += payload
        return "m${created.size}"
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        failWith?.let { throw it }
        updates += id to fields
    }
}

/** Records room status changes; can be told to refuse (like the real guard for an occupied room). */
internal class MtFakeRoomLogic : RoomLogic {
    val statusCalls = mutableListOf<Pair<String, RoomStatus>>()
    var failWith: Exception? = null

    override fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean = aIn < bOut && aOut > bIn
    override suspend fun detectConflict(roomId: String, checkIn: Instant, checkOut: Instant, excludeBookingId: String?): Boolean = false
    override suspend fun findAvailableRooms(roomType: String, checkIn: Instant, checkOut: Instant): List<Room> = emptyList()
    override suspend fun updateRoomStatus(roomId: String, newStatus: RoomStatus, extra: Map<String, Any?>, allowOccupiedOverride: Boolean) {
        statusCalls += roomId to newStatus
        failWith?.let { throw it }
    }
    override suspend fun updateRoomCleanliness(roomId: String, cleanliness: Cleanliness, extra: Map<String, Any?>) = Unit
    override fun getRoomDisplayStatus(room: Room): RoomDisplayStatus = throw UnsupportedOperationException()
    override suspend fun triggerRoomStatus(roomId: String, event: RoomEvent) = Unit
}
