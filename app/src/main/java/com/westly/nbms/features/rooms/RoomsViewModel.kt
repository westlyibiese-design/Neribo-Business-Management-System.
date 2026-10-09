package com.westly.nbms.features.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.archive.RecordArchiver
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import com.westly.nbms.core.notify.Notifier
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_NUMBER_REQUIRED = "Room number is required."
internal const val MSG_NUMBER_TAKEN = "A room with this number already exists."
internal const val MSG_PRICE_REQUIRED = "Price per night is required."
internal const val MSG_PRICE_INVALID = "Price must be 0 or more."
internal const val MSG_CAPACITY_INVALID = "Capacity must be a whole number of 1 or more."
internal const val MSG_GENERIC = "Something went wrong. Please try again."

/** The room types of the add/edit form (copied from Westly). */
internal val ROOM_TYPES = listOf("Standard Room", "Deluxe Room", "Junior Suite", "Executive Suite", "Presidential Suite")

/** What the person typed in the Add/Edit Room form. Numbers are still text here. */
data class RoomFormInput(
    val number: String = "",
    val floor: String = "1",
    val type: String = "Standard Room",
    val name: String = "",
    val priceText: String = "",
    val capacityText: String = "2",
    val status: RoomStatus = RoomStatus.AVAILABLE,
    val amenitiesText: String = "",
    val description: String = "",
    val images: List<String> = emptyList()
)

/** One message per field, null when the field is fine. */
data class RoomFormErrors(
    val number: String? = null,
    val price: String? = null,
    val capacity: String? = null
) {
    val hasErrors: Boolean get() = number != null || price != null || capacity != null
}

/** "WiFi, TV, ,AC" -> ["WiFi", "TV", "AC"] (split on commas, trimmed, blanks dropped). */
internal fun splitAmenities(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Capacity text: blank means the default of 2. Returns null when it is not a whole number of at least 1. */
internal fun parseCapacity(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return 2
    val n = t.toIntOrNull() ?: return null
    return if (n >= 1) n else null
}

/**
 * Checks the Add/Edit Room form. [existing] are the business's rooms; deleted ones are ignored, and the room being
 * edited ([editingId]) never clashes with itself. Room numbers are compared ignoring spaces and capitals.
 */
internal fun validateRoomForm(input: RoomFormInput, existing: List<Room>, editingId: String?): RoomFormErrors {
    val number = input.number.trim()
    val numberError = when {
        number.isEmpty() -> MSG_NUMBER_REQUIRED
        existing.any { !it.isDeleted && it.id != editingId && it.number.trim().equals(number, ignoreCase = true) } -> MSG_NUMBER_TAKEN
        else -> null
    }
    val priceText = input.priceText.trim()
    val price = priceText.toDoubleOrNull()
    val priceError = when {
        priceText.isEmpty() -> MSG_PRICE_REQUIRED
        price == null || price < 0.0 || price.isNaN() || price.isInfinite() -> MSG_PRICE_INVALID
        else -> null
    }
    val capacityError = if (parseCapacity(input.capacityText) == null) MSG_CAPACITY_INVALID else null
    return RoomFormErrors(numberError, priceError, capacityError)
}

/**
 * The fields saved for a room: number, name, type, price, capacity, floor, description, amenities, status, images.
 * Call only after [validateRoomForm] found no errors.
 */
internal fun roomFields(input: RoomFormInput): Map<String, Any?> = mapOf(
    "number" to input.number.trim(),
    "name" to input.name.trim().ifEmpty { null },
    "type" to input.type,
    "price" to (input.priceText.trim().toDoubleOrNull() ?: 0.0),
    "capacity" to (parseCapacity(input.capacityText) ?: 2),
    "floor" to input.floor.trim().ifEmpty { "1" },
    "description" to input.description.trim(),
    "amenities" to splitAmenities(input.amenitiesText),
    "status" to input.status.key,
    "images" to input.images.map { it.trim() }.filter { it.isNotEmpty() }
)

/** The document written when a room is created: the form fields plus isDeleted = false and createdAt. */
internal fun newRoomDocument(fields: Map<String, Any?>, createdAt: Any): Map<String, Any?> =
    fields + mapOf("isDeleted" to false, "createdAt" to createdAt)

/** Rooms in number order: "2" before "10", then text order ("A1"). */
internal fun sortRooms(rooms: List<Room>): List<Room> =
    rooms.sortedWith(
        compareBy<Room> { it.number.trim().toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.number.trim().lowercase() }
    )

/** Number of rooms per filter chip; key "all" counts everything. */
internal fun roomCounts(rooms: List<Room>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    counts["all"] = rooms.size
    RoomStatus.entries.forEach { s -> counts[s.key] = rooms.count { it.status == s.key } }
    return counts
}

/** Rooms for a filter chip: "all" or a status key. */
internal fun filterRoomsByStatus(rooms: List<Room>, filterKey: String): List<Room> =
    if (filterKey == "all") rooms else rooms.filter { it.status == filterKey }

/** "out_of_service" -> "out of service" for toasts. */
internal fun statusWords(statusKey: String): String = statusKey.replace('_', ' ')

// ---- ViewModel ------------------------------------------------------------------------------

data class RoomsUiState(
    /** True while an Add / Save runs. */
    val saving: Boolean = false,
    /** Ids of rooms whose status change or delete is running. */
    val busyIds: Set<String> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val realtime: BusinessRealtime,
    private val roomLogic: RoomLogic,
    private val audit: AuditLogger,
    private val archiver: RecordArchiver,
    private val notifier: Notifier,
    private val toast: ToastController,
    imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()

    /** The first image-upload provider, or null while Phase 30 is not installed (forms then use a URL list). */
    val imageProvider: ImageFieldProvider? = imageProviders.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    /** Live rooms of this business, without deleted ones, in room-number order. */
    val rooms: StateFlow<Resource<List<Room>>> = retryTick
        .flatMapLatest { firestore.observeList("rooms", Room::class.java) }
        .map { r -> if (r is Resource.Success) Resource.Success(sortRooms(r.data.filter { !it.isDeleted })) else r }
        .catch { emit(Resource.Error("We couldn't load rooms.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun displayStatus(room: Room): RoomDisplayStatus = roomLogic.getRoomDisplayStatus(room)

    /** Adds a room, or saves changes to [editing]. [onSuccess] runs on success so the form can close. */
    fun saveRoom(editing: Room?, input: RoomFormInput, onSuccess: () -> Unit) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val fields = roomFields(input)
                if (editing == null) createRoom(fields, input.status) else updateRoom(editing, fields, input.status)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Error")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun createRoom(fields: Map<String, Any?>, status: RoomStatus) {
        val id = firestore.add("rooms", newRoomDocument(fields, FieldValue.serverTimestamp()))
        // Live status copy; a slow or failed write is ignored (Firestore is the source of truth).
        try {
            withTimeoutOrNull(8_000L) {
                realtime.set("roomStatus/$id", mapOf("status" to status.key, "updatedAt" to System.currentTimeMillis()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Ignored on purpose: see above.
        }
        audit.log("room_created", "rooms", id, null, fields)
        toast.show("Room ${fields["number"]} is now in your rooms list.", ToastType.Success, "Room Added")
    }

    private suspend fun updateRoom(room: Room, fields: Map<String, Any?>, newStatus: RoomStatus) {
        val statusChanged = room.status != newStatus.key
        var toWrite = fields
        if (statusChanged) {
            // Goes through the room rules so a room with a guest in it cannot be freed, and the live status is kept in step.
            roomLogic.updateRoomStatus(room.id, newStatus, emptyMap(), false)
            toWrite = fields - "status"
        }
        firestore.update("rooms", room.id, toWrite)
        audit.log("room_updated", "rooms", room.id, mapOf("status" to room.status), fields)
        toast.show("Room ${fields["number"]} was saved.", ToastType.Success, "Room Updated")
    }

    /** The status dropdown on a room card (Super Admin). */
    fun changeStatus(room: Room, newStatus: RoomStatus, userName: String) {
        if (room.id in _state.value.busyIds || room.status == newStatus.key) return
        markBusy(room.id, true)
        viewModelScope.launch {
            try {
                roomLogic.updateRoomStatus(room.id, newStatus, emptyMap(), false)
                audit.log(
                    "room_status:${room.status}→${newStatus.key}", "rooms", room.id,
                    mapOf("status" to room.status), mapOf("status" to newStatus.key)
                )
                toast.show("Room ${room.number} → ${statusWords(newStatus.key)}", ToastType.Success, "Status Updated")
                try {
                    notifier.notifyRoomStatusChange(room.number, newStatus.key, userName)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // The status is already saved; a failed alert must not look like a failed change.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Error")
            } finally {
                markBusy(room.id, false)
            }
        }
    }

    /** Soft-deletes the room; it can be restored from Deleted Records. */
    fun deleteRoom(room: Room) {
        if (room.id in _state.value.busyIds) return
        markBusy(room.id, true)
        viewModelScope.launch {
            try {
                val failure = archiver.softDelete("rooms", room.id, "Room ${room.number}", "Deleted from admin panel").exceptionOrNull()
                if (failure == null) {
                    toast.show("Room ${room.number} was moved to Deleted Records.", ToastType.Success, "Room Deleted")
                } else {
                    toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Delete Failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Delete Failed")
            } finally {
                markBusy(room.id, false)
            }
        }
    }

    private fun markBusy(id: String, busy: Boolean) {
        _state.update { it.copy(busyIds = if (busy) it.busyIds + id else it.busyIds - id) }
    }
}
