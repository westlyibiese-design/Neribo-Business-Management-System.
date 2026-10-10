package com.westly.nbms.features.rooms

import android.util.Log
import com.google.firebase.Timestamp
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.design.BadgeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoomLogic"

/** Realtime Database writes give up after this long; Firestore stays the source of truth. */
internal const val LIVE_WRITE_TIMEOUT_MS = 8_000L

/** One per-night lock document of `booking_dates` (written by Phase 12). The document id is the booking id. */
data class BookingLock(
    val id: String,
    val roomId: String,
    val checkIn: Instant?,
    val checkOut: Instant?,
    val status: String
)

/**
 * The few database calls the room rules need. [FirestoreRoomLogicStore] is the real one;
 * the unit tests use a fake. Time values inside maps are [Instant]s; the real store turns them into Firestore Timestamps.
 */
interface RoomLogicStore {
    /** `booking_dates` where roomId == [roomId] and status in [statuses]. */
    suspend fun activeBookingLocks(roomId: String, statuses: List<String>): List<BookingLock>
    suspend fun allRooms(): List<Room>
    suspend fun getRoom(roomId: String): Room?
    /** Firestore `rooms/{roomId}` update. */
    suspend fun updateRoom(roomId: String, fields: Map<String, Any?>)
    /** Realtime Database `roomStatus/{roomId}` set (replaces the whole node). */
    suspend fun setLiveStatus(roomId: String, fields: Map<String, Any?>)
    /** Realtime Database `roomStatus/{roomId}` update (merges). */
    suspend fun updateLiveStatus(roomId: String, fields: Map<String, Any?>)
}

@Singleton
class FirestoreRoomLogicStore @Inject constructor(
    private val firestore: BusinessFirestore,
    private val realtime: BusinessRealtime
) : RoomLogicStore {

    override suspend fun activeBookingLocks(roomId: String, statuses: List<String>): List<BookingLock> {
        val snapshot = firestore.collection("booking_dates")
            .whereEqualTo("roomId", roomId)
            .whereIn("status", statuses)
            .get()
            .await()
        return snapshot.documents.map { d ->
            BookingLock(
                id = d.id,
                roomId = d.getString("roomId") ?: roomId,
                checkIn = d.getTimestamp("checkIn").asInstant(),
                checkOut = d.getTimestamp("checkOut").asInstant(),
                status = d.getString("status").orEmpty()
            )
        }
    }

    override suspend fun allRooms(): List<Room> =
        firestore.collection("rooms").get().await().toObjects(Room::class.java)

    override suspend fun getRoom(roomId: String): Room? =
        firestore.doc("rooms", roomId).get().await().toObject(Room::class.java)

    override suspend fun updateRoom(roomId: String, fields: Map<String, Any?>) {
        firestore.update("rooms", roomId, fields.mapValues { (_, v) -> if (v is Instant) v.toTimestamp() else v })
    }

    override suspend fun setLiveStatus(roomId: String, fields: Map<String, Any?>) {
        realtime.set("roomStatus/$roomId", fields)
    }

    override suspend fun updateLiveStatus(roomId: String, fields: Map<String, Any?>) {
        realtime.update("roomStatus/$roomId", fields)
    }
}

/** Not named toInstant: Timestamp has its own toInstant() (java.time) that would win over an extension. */
private fun Timestamp?.asInstant(): Instant? = this?.let { Instant.fromEpochSeconds(it.seconds, it.nanoseconds.toLong()) }

private fun Instant.toTimestamp(): Timestamp = Timestamp(epochSeconds, nanosecondsOfSecond)

/**
 * Keeps only values the Realtime Database can hold (text, numbers, true/false, null). Times become epoch milliseconds;
 * Firestore-only values (such as server-timestamp markers) are dropped.
 */
internal fun liveFields(base: Map<String, Any?>, extra: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out.putAll(base)
    for ((key, value) in extra) {
        when (value) {
            null, is String, is Boolean, is Number -> out[key] = value
            is Instant -> out[key] = value.toEpochMilliseconds()
            is Timestamp -> out[key] = value.seconds * 1000L + value.nanoseconds / 1_000_000L
            else -> Unit
        }
    }
    return out
}

/** The room status each event moves a room to. Check-out always goes to Cleaning first. */
internal fun statusForEvent(event: RoomEvent): RoomStatus = when (event) {
    RoomEvent.CHECK_IN -> RoomStatus.OCCUPIED
    RoomEvent.CHECK_OUT -> RoomStatus.CLEANING
    RoomEvent.START_CLEANING -> RoomStatus.CLEANING
    RoomEvent.FINISH_CLEANING -> RoomStatus.AVAILABLE
    RoomEvent.MAINTENANCE -> RoomStatus.MAINTENANCE
    RoomEvent.BACK_TO_SERVICE -> RoomStatus.AVAILABLE
}

@Singleton
class RoomLogicImpl @Inject constructor(
    private val store: RoomLogicStore
) : RoomLogic {

    /** Replaced in unit tests. */
    internal var clock: () -> Instant = { Clock.System.now() }

    override fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean =
        aIn < bOut && aOut > bIn

    override suspend fun detectConflict(
        roomId: String,
        checkIn: Instant,
        checkOut: Instant,
        excludeBookingId: String?
    ): Boolean {
        val locks = store.activeBookingLocks(roomId, BLOCKING_BOOKING_STATUSES)
        return locks.any { lock ->
            val lockIn = lock.checkIn
            val lockOut = lock.checkOut
            lock.roomId == roomId &&
                lock.status in BLOCKING_BOOKING_STATUSES &&
                lock.id != excludeBookingId &&
                lockIn != null && lockOut != null &&
                datesOverlap(checkIn, checkOut, lockIn, lockOut)
        }
    }

    override suspend fun findAvailableRooms(roomType: String, checkIn: Instant, checkOut: Instant): List<Room> =
        store.allRooms()
            .filter { !it.isDeleted && it.type == roomType }
            .filter { !detectConflict(it.id, checkIn, checkOut, null) }

    override suspend fun updateRoomStatus(
        roomId: String,
        newStatus: RoomStatus,
        extra: Map<String, Any?>,
        allowOccupiedOverride: Boolean
    ) {
        if (newStatus == RoomStatus.AVAILABLE && !allowOccupiedOverride) {
            val room = store.getRoom(roomId)
            // A blank id ("") means no guest, exactly like null; Housekeeping already reads it that way.
            if (!room?.currentBookingId.isNullOrBlank()) throw IllegalStateException(MSG_ROOM_STILL_OCCUPIED)
        }
        val now = clock()
        val firestoreFields = LinkedHashMap<String, Any?>()
        firestoreFields["status"] = newStatus.key
        firestoreFields["statusUpdatedAt"] = now
        firestoreFields.putAll(extra)
        store.updateRoom(roomId, firestoreFields)

        val live = liveFields(
            base = mapOf("status" to newStatus.key, "updatedAt" to now.toEpochMilliseconds()),
            extra = extra
        )
        bestEffortLive { store.setLiveStatus(roomId, live) }
    }

    override suspend fun updateRoomCleanliness(roomId: String, cleanliness: Cleanliness, extra: Map<String, Any?>) {
        val now = clock()
        val firestoreFields = LinkedHashMap<String, Any?>()
        firestoreFields["cleanliness"] = cleanliness.key
        firestoreFields["cleanlinessUpdatedAt"] = now
        firestoreFields.putAll(extra)
        store.updateRoom(roomId, firestoreFields)

        val live = liveFields(
            base = mapOf("cleanliness" to cleanliness.key, "cleanlinessUpdatedAt" to now.toEpochMilliseconds()),
            extra = extra
        )
        bestEffortLive { store.updateLiveStatus(roomId, live) }
    }

    override suspend fun triggerRoomStatus(roomId: String, event: RoomEvent) {
        updateRoomStatus(roomId, statusForEvent(event))
    }

    override fun getRoomDisplayStatus(room: Room): RoomDisplayStatus {
        val status = room.statusOrDefault()
        val cleanliness = Cleanliness.fromKey(room.cleanliness)
            ?: if (status == RoomStatus.CLEANING) Cleanliness.DIRTY else Cleanliness.CLEAN
        val overdue = room.checkoutOverdue

        fun result(label: String, tone: BadgeTone) = RoomDisplayStatus(label, tone, status, cleanliness, overdue)

        return when (status) {
            RoomStatus.MAINTENANCE -> result(RoomLabels.MAINTENANCE, BadgeTone.Warning)
            RoomStatus.OUT_OF_SERVICE -> result(RoomLabels.OUT_OF_SERVICE, BadgeTone.Outline)
            RoomStatus.RESERVED -> result(RoomLabels.RESERVED, BadgeTone.Info)
            RoomStatus.OCCUPIED -> when {
                overdue -> result(RoomLabels.OCCUPIED_OVERDUE, BadgeTone.Destructive)
                cleanliness == Cleanliness.CLEANING_IN_PROGRESS ->
                    result(RoomLabels.OCCUPIED_CLEANING_IN_PROGRESS, BadgeTone.Gold)
                cleanliness == Cleanliness.DAILY_CLEANING_DUE || cleanliness == Cleanliness.CHECKOUT_CLEANING_DUE ->
                    result(RoomLabels.OCCUPIED_CLEANING_DUE, BadgeTone.Warning)
                else -> result(RoomLabels.OCCUPIED_CLEAN, BadgeTone.Info)
            }
            RoomStatus.CLEANING ->
                if (cleanliness == Cleanliness.CLEANING_IN_PROGRESS) result(RoomLabels.CLEANING_IN_PROGRESS, BadgeTone.Gold)
                else result(RoomLabels.DIRTY, BadgeTone.Warning)
            RoomStatus.AVAILABLE -> result(RoomLabels.AVAILABLE, BadgeTone.Success)
        }
    }

    /** Live status is a convenience copy: a slow or failed write is ignored (Firestore remains the source of truth). */
    private suspend fun bestEffortLive(block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(LIVE_WRITE_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live room status write failed: ${e.javaClass.simpleName}")
        }
    }
}
