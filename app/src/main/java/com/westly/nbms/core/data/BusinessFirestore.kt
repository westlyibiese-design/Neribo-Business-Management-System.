package com.westly.nbms.core.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * All Firestore access goes through this. Paths are always businesses/{bid}/{collection}/...
 *
 * Model convention: Kotlin data classes with a default for every field, `@DocumentId val id: String = ""`
 * and `@ServerTimestamp val createdAt: Timestamp? = null` (a null createdAt is filled in by the server on add).
 */
interface BusinessFirestore {
    val businessId: String
    fun collection(name: String): CollectionReference
    fun doc(collection: String, id: String): DocumentReference
    fun <T : Any> observeList(
        collection: String,
        clazz: Class<T>,
        query: (Query) -> Query = { it }
    ): Flow<Resource<List<T>>>
    fun <T : Any> observeDoc(collection: String, id: String, clazz: Class<T>): Flow<Resource<T?>>
    suspend fun <T : Any> add(collection: String, value: T): String
    suspend fun update(collection: String, id: String, fields: Map<String, Any?>)
    suspend fun set(collection: String, id: String, value: Any, merge: Boolean = false)
    suspend fun delete(collection: String, id: String)
    suspend fun <R> runTransaction(block: suspend (Transaction, BusinessFirestore) -> R): R

    /**
     * Like [runTransaction], but it also works with no internet: offline it reads the phone's saved copy and queues
     * the writes, which upload by themselves later. Use this for every save that must work offline.
     * The default only exists so test fakes need no change; the real implementation overrides it.
     */
    suspend fun <R> runTx(block: suspend (TxScope, BusinessFirestore) -> R): R =
        throw UnsupportedOperationException("runTx")
}
