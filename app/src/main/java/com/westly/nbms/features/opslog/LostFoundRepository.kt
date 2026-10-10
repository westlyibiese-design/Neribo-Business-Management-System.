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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val LOST_FOUND_TAG = "LostFound"
internal const val LOST_FOUND_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val LOST_FOUND_POST_STEP_TIMEOUT_MS = 5_000L

/** A problem with a message for the person. */
class LostFoundException(message: String) : Exception(message)

/** The database calls Lost & Found needs. [FirestoreLostFoundStore] is the real one; the unit tests use a fake. */
interface LostFoundStore {
    /** Live `lost_found`, every document parsed with [parseLostFoundItem]. */
    fun observe(): Flow<Resource<List<LostFoundItem>>>

    /** Live rooms for the Room dropdown: not deleted, in room-number order. */
    fun observeRooms(): Flow<Resource<List<LostFoundRoomOption>>>

    /** Creates `lost_found/{new}` from [payload] in ONE transaction and returns the new id. The payload may hold the markers. */
    suspend fun create(payload: Map<String, Any?>): String

    /** Updates ONLY [fields] on the existing item. The fields may hold the markers. */
    suspend fun update(id: String, fields: Map<String, Any?>)
}

class FirestoreLostFoundStore(private val firestore: BusinessFirestore) : LostFoundStore {

    override fun observe(): Flow<Resource<List<LostFoundItem>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection(LOST_FOUND_COLLECTION).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_LOST_FOUND_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // ESTIMATE shows a just-saved item's time at once.
                        val list = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseLostFoundItem(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(list))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_LOST_FOUND_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_LOST_FOUND_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override fun observeRooms(): Flow<Resource<List<LostFoundRoomOption>>> =
        firestore.observeList("rooms", Room::class.java).map { r ->
            when (r) {
                is Resource.Success -> Resource.Success(
                    r.data.filter { !it.isDeleted }
                        .sortedWith(compareBy<Room>({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
                        .map { LostFoundRoomOption(it.id, it.number, it.type) }
                )
                is Resource.Error -> Resource.Error(r.message, r.cause)
                is Resource.Loading -> Resource.Loading
            }
        }

    override suspend fun create(payload: Map<String, Any?>): String {
        val id = firestore.collection(LOST_FOUND_COLLECTION).document().id
        val resolved = resolveLostFoundMap(payload)
        firestore.runTransaction { tx, fs ->
            tx.set(fs.doc(LOST_FOUND_COLLECTION, id), resolved)
            Unit
        }
        return id
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(LOST_FOUND_COLLECTION, id, resolveLostFoundMap(fields))
    }
}

private fun resolveLostFoundMap(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> resolveLostFoundValue(v) }

/** Swaps the markers and times for what Firestore stores. */
private fun resolveLostFoundValue(v: Any?): Any? = when (v) {
    is LostFoundServerTime -> FieldValue.serverTimestamp()
    is LostFoundHistoryAppend -> FieldValue.arrayUnion(resolveLostFoundMap(v.entry))
    is Instant -> Timestamp(v.epochSecond, v.nano)
    is Map<*, *> -> v.entries.associate { (k, value) -> k.toString() to resolveLostFoundValue(value) }
    is List<*> -> v.map { resolveLostFoundValue(it) }
    else -> v
}

/**
 * Logs found items, reads them live and changes their status.
 * The repository checks the role too; the database rules enforce the same.
 */
@Singleton
class LostFoundRepository(
    private val store: LostFoundStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val clock: () -> Instant
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager, audit: AuditLogger, notifier: Notifier) :
        this(FirestoreLostFoundStore(firestore), session, audit, notifier, { Instant.now() })

    /** Live items, soft-deleted ones (`isDeleted == true`) left out. */
    fun observe(): Flow<Resource<List<LostFoundItem>>> = store.observe().map { r ->
        when (r) {
            is Resource.Success -> Resource.Success(r.data.filter { !it.isDeleted })
            else -> r
        }
    }

    /** Rooms for the Room dropdown. */
    fun observeRooms(): Flow<Resource<List<LostFoundRoomOption>>> = store.observeRooms()

    /**
     * Saves the item in ONE transaction (20 s limit), then does the best-effort extras
     * (audit entry `lost_found_item_logged`, "Lost & Found Item Logged" alert). Returns the new id.
     * Throws [LostFoundException] with a message for the person; nothing is saved in that case.
     */
    suspend fun create(input: LogFoundInput): String {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw LostFoundException(MSG_LOST_FOUND_NOT_SIGNED_IN)
        if (!lostFoundCanCreate(signedIn.user.role)) throw LostFoundException(MSG_LOST_FOUND_NO_PERMISSION)
        val myName = signedIn.user.name
        val payload = buildLostFoundCreatePayload(input, signedIn.user.uid, myName, clock())

        val committedId: String? = try {
            withTimeoutOrNull(LOST_FOUND_TRANSACTION_TIMEOUT_MS) { store.create(payload) }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LostFoundException(e.message?.takeIf { it.isNotBlank() } ?: MSG_LOST_FOUND_GENERIC)
        }
        val id = committedId ?: throw LostFoundException(MSG_LOST_FOUND_TIMEOUT)

        supervisorScope {
            launch {
                guarded("audit") {
                    audit.log(
                        "lost_found_item_logged", LOST_FOUND_COLLECTION, id, null,
                        mapOf("itemName" to input.itemName, "roomNumber" to input.roomNumber, "status" to input.status.key)
                    )
                }
            }
            launch { guarded("logged alert") { notifier.notifyLostFoundItem(input.itemName, input.roomNumber, myName) } }
        }
        return id
    }

    /**
     * Moves [item] to [target]: updates the status and APPENDS a history entry in one write, then audits
     * `lost_found_status_changed:{old}→{new}` and, for Returned to Guest / Claimed, sends the claimed alert.
     * Throws [LostFoundException] with a message for the person.
     */
    suspend fun changeStatus(item: LostFoundItem, target: ItemStatus, note: String?) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw LostFoundException(MSG_LOST_FOUND_NOT_SIGNED_IN)
        if (!lostFoundCanManageStatus(signedIn.user.role)) throw LostFoundException(MSG_LOST_FOUND_NO_PERMISSION)
        val myName = signedIn.user.name
        val fields = buildLostFoundStatusPayload(target, note, signedIn.user.uid, myName, clock())

        try {
            store.update(item.id, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LostFoundException(e.message?.takeIf { it.isNotBlank() } ?: MSG_LOST_FOUND_GENERIC)
        }

        supervisorScope {
            launch {
                guarded("audit") {
                    audit.log(
                        lostFoundStatusAuditAction(item.status, target), LOST_FOUND_COLLECTION, item.id,
                        mapOf("status" to item.status.key), mapOf("status" to target.key)
                    )
                }
            }
            if (lostFoundNotifiesClaimed(target)) {
                launch {
                    guarded("claimed alert") { notifier.notifyLostFoundClaimed(item.itemName, item.guestName ?: "the guest", myName) }
                }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(LOST_FOUND_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOST_FOUND_TAG, "Lost & found $what step failed", e)
        }
    }
}
