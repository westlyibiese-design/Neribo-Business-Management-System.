package com.westly.nbms.features.opslog

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val MAINTENANCE_TAG = "Maintenance"
internal const val MAINTENANCE_SAVE_TIMEOUT_MS = 20_000L
internal const val MAINTENANCE_POST_STEP_TIMEOUT_MS = 5_000L

/** A problem with a message for the person. */
class MaintenanceException(message: String) : Exception(message)

/** The database calls Maintenance needs. [FirestoreMaintenanceStore] is the real one; the unit tests use a fake. */
interface MaintenanceStore {
    /** Live `maintenance`, every document parsed with [parseMaintenanceRequest]. */
    fun observe(): Flow<Resource<List<MaintenanceRequest>>>

    /** Live rooms for the Room dropdown: not deleted, in room-number order. */
    fun observeRooms(): Flow<Resource<List<MaintenanceRoomOption>>>

    /** Creates `maintenance/{new}` from [payload] and returns the new id. The payload may hold [MaintenanceServerTime]. */
    suspend fun create(payload: Map<String, Any?>): String

    /** Updates ONLY [fields] on the existing request. The fields may hold [MaintenanceServerTime]. */
    suspend fun update(id: String, fields: Map<String, Any?>)
}

class FirestoreMaintenanceStore(private val firestore: BusinessFirestore) : MaintenanceStore {

    override fun observe(): Flow<Resource<List<MaintenanceRequest>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection(MAINTENANCE_COLLECTION).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_MAINTENANCE_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // ESTIMATE shows a just-saved request's time at once.
                        val list = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseMaintenanceRequest(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(list))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_MAINTENANCE_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_MAINTENANCE_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override fun observeRooms(): Flow<Resource<List<MaintenanceRoomOption>>> =
        firestore.observeList("rooms", Room::class.java).map { r ->
            when (r) {
                is Resource.Success -> Resource.Success(
                    r.data.filter { !it.isDeleted }
                        .sortedWith(compareBy<Room>({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
                        .map { MaintenanceRoomOption(it.id, it.number, it.type) }
                )
                is Resource.Error -> Resource.Error(r.message, r.cause)
                is Resource.Loading -> Resource.Loading
            }
        }

    override suspend fun create(payload: Map<String, Any?>): String {
        val id = firestore.collection(MAINTENANCE_COLLECTION).document().id
        firestore.set(MAINTENANCE_COLLECTION, id, resolveMaintenanceMap(payload))
        return id
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(MAINTENANCE_COLLECTION, id, resolveMaintenanceMap(fields))
    }
}

private fun resolveMaintenanceMap(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> resolveMaintenanceValue(v) }

/** Swaps the server-time marker and times for what Firestore stores. */
private fun resolveMaintenanceValue(v: Any?): Any? = when (v) {
    is MaintenanceServerTime -> FieldValue.serverTimestamp()
    is Instant -> Timestamp(v.epochSecond, v.nano)
    else -> v
}

/** What happened when a request was logged. */
data class MaintenanceLogResult(
    val id: String,
    /** The message of the failure when the room could not be put in Maintenance; null when it worked. The request is saved either way. */
    val roomError: String?
)

/**
 * Logs maintenance requests, reads them live and closes them.
 * The repository checks the role too; the database rules enforce the same.
 */
@Singleton
class MaintenanceRepository(
    private val store: MaintenanceStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val rooms: RoomLogic
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager, audit: AuditLogger, notifier: Notifier, rooms: RoomLogic) :
        this(FirestoreMaintenanceStore(firestore), session, audit, notifier, rooms)

    /** Live requests, soft-deleted ones (`isDeleted == true`) left out. */
    fun observe(): Flow<Resource<List<MaintenanceRequest>>> = store.observe().map { r ->
        when (r) {
            is Resource.Success -> Resource.Success(r.data.filter { !it.isDeleted })
            else -> r
        }
    }

    /** Rooms for the Room dropdown. */
    fun observeRooms(): Flow<Resource<List<MaintenanceRoomOption>>> = store.observeRooms()

    /**
     * Saves the request, then puts the room in Maintenance, then sends the "logged" alert (best effort).
     * If the room change fails the request is KEPT and [MaintenanceLogResult.roomError] carries the reason.
     * Throws [MaintenanceException] with a message for the person when the request itself could not be saved.
     */
    suspend fun create(input: MaintenanceInput): MaintenanceLogResult {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw MaintenanceException(MSG_MAINTENANCE_NOT_SIGNED_IN)
        if (!maintenanceCanLog(signedIn.user.role)) throw MaintenanceException(MSG_MAINTENANCE_NO_PERMISSION)
        val myName = signedIn.user.name
        val payload = buildMaintenanceCreatePayload(input, signedIn.user.uid, myName)

        val committedId: String? = try {
            withTimeoutOrNull(MAINTENANCE_SAVE_TIMEOUT_MS) { store.create(payload) }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MaintenanceException(e.message?.takeIf { it.isNotBlank() } ?: MSG_MAINTENANCE_GENERIC)
        }
        val id = committedId ?: throw MaintenanceException(MSG_MAINTENANCE_TIMEOUT)

        // The request is saved. A failure from here on must not undo it.
        val roomError: String? = try {
            rooms.updateRoomStatus(input.roomId, RoomStatus.MAINTENANCE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: MSG_MAINTENANCE_GENERIC
        }

        guarded("logged alert") {
            notifier.notifyMaintenanceRequest(input.title, maintenanceRoomLabel(input.roomNumber), myName)
        }
        return MaintenanceLogResult(id, roomError)
    }

    /**
     * Closes [request]: updates the request, then tries to put the room back to Available (a failure is swallowed:
     * the guard refuses to free an occupied room), audits `maintenance_closed`, and sends the "resolved" alert.
     * Returns true when the room is Available again, false when it could not be freed (it still holds a guest).
     * Throws [MaintenanceException] with a message for the person when the request itself could not be closed.
     */
    suspend fun close(request: MaintenanceRequest): Boolean {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw MaintenanceException(MSG_MAINTENANCE_NOT_SIGNED_IN)
        if (!maintenanceCanClose(signedIn.user.role)) throw MaintenanceException(MSG_MAINTENANCE_NO_PERMISSION)
        val myName = signedIn.user.name
        val fields = buildMaintenanceClosePayload(signedIn.user.uid, myName)

        try {
            store.update(request.id, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MaintenanceException(e.message?.takeIf { it.isNotBlank() } ?: MSG_MAINTENANCE_GENERIC)
        }

        val roomId = request.roomId
        val freed: Boolean = if (roomId.isNullOrBlank()) {
            true
        } else {
            try {
                rooms.updateRoomStatus(roomId, RoomStatus.AVAILABLE)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

        guarded("audit") {
            audit.log(
                MAINTENANCE_CLOSED_AUDIT_ACTION, MAINTENANCE_COLLECTION, request.id,
                mapOf("status" to MAINTENANCE_STATUS_OPEN), mapOf("status" to MAINTENANCE_STATUS_CLOSED)
            )
        }
        guarded("resolved alert") {
            notifier.notifyMaintenanceResolved(maintenanceRoomLabel(request.roomNumber), myName)
        }
        return freed
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(MAINTENANCE_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(MAINTENANCE_TAG, "Maintenance $what step failed", e)
        }
    }
}
