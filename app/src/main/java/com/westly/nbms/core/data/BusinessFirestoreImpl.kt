package com.westly.nbms.core.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.sync.SyncTracker
import com.westly.nbms.core.sync.awaitWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BusinessFirestoreImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: SessionManager
) : BusinessFirestore {

    init {
        SyncTracker.attach(firestore)
    }

    override val businessId: String
        get() = (session.state.value as? SessionState.SignedIn)?.business?.id
            ?: throw IllegalStateException("Not signed in")

    override fun collection(name: String): CollectionReference =
        firestore.collection("businesses").document(businessId).collection(name)

    override fun doc(collection: String, id: String): DocumentReference =
        this@BusinessFirestoreImpl.collection(collection).document(id)

    override fun <T : Any> observeList(
        collection: String,
        clazz: Class<T>,
        query: (Query) -> Query
    ): Flow<Resource<List<T>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            query(this@BusinessFirestoreImpl.collection(collection)).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(friendlyMessage(error), error))
                } else if (snapshot != null) {
                    try {
                        trySend(Resource.Success(snapshot.toObjects(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)))
                    } catch (e: Exception) {
                        trySend(Resource.Error("Could not read this data.", e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(friendlyMessage(e), e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override fun <T : Any> observeDoc(
        collection: String,
        id: String,
        clazz: Class<T>
    ): Flow<Resource<T?>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            doc(collection, id).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(friendlyMessage(error), error))
                } else if (snapshot != null) {
                    try {
                        trySend(Resource.Success(if (snapshot.exists()) snapshot.toObject(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) else null))
                    } catch (e: Exception) {
                        trySend(Resource.Error("Could not read this data.", e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(friendlyMessage(e), e))
            null
        }
        awaitClose { registration?.remove() }
    }

    /**
     * Adds a document and returns its new id. A `createdAt` server timestamp is added when the value is a
     * Map without `createdAt`. Data-class models declare `@ServerTimestamp val createdAt: Timestamp? = null`,
     * which Firestore fills in with the server time when it is null.
     */
    override suspend fun <T : Any> add(collection: String, value: T): String {
        val payload: Any = if (value is Map<*, *> && !value.containsKey("createdAt")) {
            value.toMutableMap().apply { put("createdAt", FieldValue.serverTimestamp()) }
        } else {
            value
        }
        // The id is made on the phone first, so the save never has to wait for the server to hand it back.
        val ref = this@BusinessFirestoreImpl.collection(collection).document()
        ref.set(payload).awaitWrite("New record in $collection")
        return ref.id
    }

    override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) {
        doc(collection, id).update(fields).awaitWrite("Change to $collection")
    }

    override suspend fun set(collection: String, id: String, value: Any, merge: Boolean) {
        val ref = doc(collection, id)
        val task = if (merge) ref.set(value, SetOptions.merge()) else ref.set(value)
        task.awaitWrite("Change to $collection")
    }

    override suspend fun delete(collection: String, id: String) {
        doc(collection, id).delete().awaitWrite("Removal from $collection")
    }

    /**
     * Wraps Firestore's transaction. Firestore runs the function on a background thread (never the main
     * thread), so the suspend [block] is run to completion there. It may run more than once on contention.
     */
    override suspend fun <R> runTransaction(block: suspend (Transaction, BusinessFirestore) -> R): R {
        val self = this
        return firestore.runTransaction(Transaction.Function<R> { tx ->
            runBlocking { block(tx, self) }
        }).await()
    }

    /**
     * Online: a real Firestore transaction (nothing changes about how it behaves today).
     * Offline, or if the connection fails before anything was saved: the block reads the phone's saved copy and its
     * writes are queued as ONE batch that uploads when the internet returns.
     */
    override suspend fun <R> runTx(block: suspend (TxScope, BusinessFirestore) -> R): R {
        if (!NetworkState.isOnline) return runQueued(block)
        try {
            val self = this
            return firestore.runTransaction(Transaction.Function<R> { tx ->
                runBlocking { block(OnlineTx(tx), self) }
            }).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isConnectionProblem()) return runQueued(block)
            throw e
        }
    }

    private suspend fun <R> runQueued(block: suspend (TxScope, BusinessFirestore) -> R): R =
        withContext(Dispatchers.IO) {
            val scope = OfflineTx(firestore)
            val result = block(scope, this@BusinessFirestoreImpl)
            scope.commit()?.let { SyncTracker.trackDetached(it, "Saved while offline") }
            result
        }

    /** Only "the server could not be reached" counts; nothing was saved in that case, so queueing is safe. */
    private fun Throwable.isConnectionProblem(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is FirebaseFirestoreException && current.code == FirebaseFirestoreException.Code.UNAVAILABLE) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun friendlyMessage(e: Exception): String = when {
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "You don't have access to this data."
        e is IllegalStateException && e.message == "Not signed in" -> "Not signed in"
        else -> e.message ?: "Something went wrong while loading this data."
    }
}
