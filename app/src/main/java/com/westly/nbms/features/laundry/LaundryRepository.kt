package com.westly.nbms.features.laundry

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val LAUNDRY_TAG = "Laundry"
private const val LAUNDRY_COLLECTION = "laundry_requests"
internal const val LAUNDRY_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val LAUNDRY_POST_STEP_TIMEOUT_MS = 5_000L

/** A problem with a message for the person. */
class LaundryException(message: String) : Exception(message)

/** The database calls Manage Laundry needs. [FirestoreLaundryStore] is the real one; the unit tests use a fake. */
interface LaundryStore {
    /** Live `laundry_requests`, every document parsed with [parseLaundryRequest]. */
    fun observe(): Flow<Resource<List<LaundryRequest>>>

    /** Creates `laundry_requests/{new}` from [payload] in ONE transaction and returns the new id. */
    suspend fun create(payload: Map<String, Any?>): String

    /** Updates ONLY [fields] on the existing request. The payload may hold [LaundryServerTime]. */
    suspend fun update(id: String, fields: Map<String, Any?>)
}

class FirestoreLaundryStore(private val firestore: BusinessFirestore) : LaundryStore {

    override fun observe(): Flow<Resource<List<LaundryRequest>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection(LAUNDRY_COLLECTION).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_LAUNDRY_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // ESTIMATE shows a just-saved request's time at once.
                        val list = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseLaundryRequest(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(list))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_LAUNDRY_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_LAUNDRY_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override suspend fun create(payload: Map<String, Any?>): String {
        val id = firestore.collection(LAUNDRY_COLLECTION).document().id
        firestore.runTransaction { tx, fs ->
            tx.set(fs.doc(LAUNDRY_COLLECTION, id), payload.resolveLaundryServerTime())
            Unit
        }
        return id
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(LAUNDRY_COLLECTION, id, fields.resolveLaundryServerTime())
    }
}

private fun Map<String, Any?>.resolveLaundryServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === LaundryServerTime) FieldValue.serverTimestamp() else v }

/**
 * Logs laundry requests, reads them live and moves them through the workflow.
 * A request's charge is revenue that starts `approvalStatus: "pending"`; this class never approves or rejects anything.
 */
@Singleton
class LaundryRepository(
    private val store: LaundryStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager, audit: AuditLogger, notifier: Notifier) :
        this(FirestoreLaundryStore(firestore), session, audit, notifier)

    /** Live requests, soft-deleted ones (`isDeleted == true`) left out. */
    fun observe(): Flow<Resource<List<LaundryRequest>>> = store.observe().map { r ->
        when (r) {
            is Resource.Success -> Resource.Success(r.data.filter { !it.isDeleted })
            else -> r
        }
    }

    /**
     * Saves the request in ONE transaction (20 s limit), then does the best-effort extras (audit entry, "New Laundry Request" alert).
     * Throws [LaundryException] with a message for the person; nothing is saved in that case.
     */
    suspend fun create(form: LaundryRequestForm): String {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw LaundryException(MSG_LAUNDRY_NOT_SIGNED_IN)
        val staffName = signedIn.user.name
        val payload = buildCreatePayload(form, signedIn.user.uid, staffName)

        val committedId: String? = try {
            withTimeoutOrNull(LAUNDRY_TRANSACTION_TIMEOUT_MS) { store.create(payload) }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LaundryException(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC)
        }
        val id = committedId ?: throw LaundryException(MSG_LAUNDRY_TIMEOUT)

        val guestName = payload["guestName"] as? String
        val roomNumber = payload["roomNumber"] as? String
        val itemCount = payload["itemCount"] as? Int ?: 1
        val label = guestOrRoomLabel(guestName, roomNumber)
        supervisorScope {
            launch {
                guarded("audit") {
                    audit.log("laundry_request_created", LAUNDRY_COLLECTION, id, null, mapOf("guestName" to guestName, "roomNumber" to roomNumber))
                }
            }
            launch { guarded("request alert") { notifier.notifyNewLaundryRequest(by = staffName, itemCount = itemCount, guestOrRoom = label) } }
        }
        return id
    }

    /** Moves [request] to its next status. Returns the new status; throws [LaundryException] when there is none or the write fails. */
    suspend fun advanceStatus(request: LaundryRequest): LaundryStatus {
        val next = nextStatus(request.status) ?: throw LaundryException(MSG_LAUNDRY_GENERIC)
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw LaundryException(MSG_LAUNDRY_NOT_SIGNED_IN)
        writeUpdate(request.id, buildAdvancePayload(next, signedIn.user.uid))
        supervisorScope {
            launch {
                guarded("audit") {
                    audit.log("laundry_status_updated", LAUNDRY_COLLECTION, request.id, mapOf("status" to request.status.key), mapOf("status" to next.key))
                }
            }
            if (next == LaundryStatus.READY) {
                launch { guarded("ready alert") { notifier.notifyLaundryReady(guestOrRoom = guestOrRoom(request), by = signedIn.user.name) } }
            }
        }
        return next
    }

    /** Sets the service charge. [charge] must already be valid (zero or more). */
    suspend fun updateCharge(request: LaundryRequest, charge: Double) {
        session.state.value as? SessionState.SignedIn ?: throw LaundryException(MSG_LAUNDRY_NOT_SIGNED_IN)
        writeUpdate(request.id, buildChargePayload(charge))
        guarded("audit") {
            audit.log("laundry_charge_updated", LAUNDRY_COLLECTION, request.id, mapOf("charge" to request.charge), mapOf("charge" to charge))
        }
    }

    /** Flips paid / unpaid and returns the new value. Does not touch revenue approval. */
    suspend fun togglePaid(request: LaundryRequest): PaymentStatus {
        session.state.value as? SessionState.SignedIn ?: throw LaundryException(MSG_LAUNDRY_NOT_SIGNED_IN)
        writeUpdate(request.id, buildPaidPayload(request.paymentStatus))
        return togglePayment(request.paymentStatus)
    }

    private suspend fun writeUpdate(id: String, fields: Map<String, Any?>) {
        try {
            store.update(id, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LaundryException(e.message?.takeIf { it.isNotBlank() } ?: MSG_LAUNDRY_GENERIC)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(LAUNDRY_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LAUNDRY_TAG, "Laundry $what step failed", e)
        }
    }
}
