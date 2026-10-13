package com.westly.nbms.core.data

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.util.concurrent.ExecutionException

/**
 * What a "transaction" block may do: read documents, then write. It has the same method names as Firestore's
 * own Transaction, so the existing blocks work unchanged. Online it is a real Firestore transaction.
 * Offline it reads from the copy saved on the phone and queues every write in one batch; the batch uploads by
 * itself when the internet returns. Every record that is created gets its id on the phone first, so a retry
 * overwrites the same document instead of making a second one.
 */
interface TxScope {
    fun get(ref: DocumentReference): DocumentSnapshot
    fun set(ref: DocumentReference, data: Any)
    fun set(ref: DocumentReference, data: Any, options: SetOptions)
    fun update(ref: DocumentReference, fields: Map<String, Any?>)
    fun delete(ref: DocumentReference)
}

/** Thrown offline when a block asks for a record this phone has never downloaded. */
class OfflineDataMissingException(message: String = MSG_OFFLINE_DATA_MISSING) : Exception(message)

internal const val MSG_OFFLINE_DATA_MISSING =
    "This record has not been saved on this phone yet. Connect to the internet once to load it, then try again."

internal class OnlineTx(private val tx: Transaction) : TxScope {
    override fun get(ref: DocumentReference): DocumentSnapshot = tx.get(ref)
    override fun set(ref: DocumentReference, data: Any) { tx.set(ref, data) }
    override fun set(ref: DocumentReference, data: Any, options: SetOptions) { tx.set(ref, data, options) }
    override fun update(ref: DocumentReference, fields: Map<String, Any?>) { tx.update(ref, fields) }
    override fun delete(ref: DocumentReference) { tx.delete(ref) }
}

internal class OfflineTx(db: FirebaseFirestore) : TxScope {
    private val batch = db.batch()
    private var writes = 0

    override fun get(ref: DocumentReference): DocumentSnapshot {
        try {
            return Tasks.await(ref.get(Source.CACHE))
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is FirebaseFirestoreException && cause.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                throw OfflineDataMissingException()
            }
            throw (cause ?: e)
        }
    }

    override fun set(ref: DocumentReference, data: Any) { batch.set(ref, data); writes++ }
    override fun set(ref: DocumentReference, data: Any, options: SetOptions) { batch.set(ref, data, options); writes++ }
    override fun update(ref: DocumentReference, fields: Map<String, Any?>) { batch.update(ref, fields); writes++ }
    override fun delete(ref: DocumentReference) { batch.delete(ref); writes++ }

    /** Queues everything in ONE batch (saved together or not at all). Returns null when nothing was written. */
    fun commit(): Task<Void>? = if (writes == 0) null else batch.commit()
}
