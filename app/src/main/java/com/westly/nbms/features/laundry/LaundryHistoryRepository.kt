package com.westly.nbms.features.laundry

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val LAUNDRY_HISTORY_COLLECTION = "laundry_requests"

/** The one database call Laundry History needs. [FirestoreLaundryHistoryStore] is the real one; the unit tests use a fake. */
interface LaundryHistoryStore {
    /** Live `laundry_requests`. [valetId] set = only that valet's requests (`laundryValetId == valetId`); null = every request. */
    fun observe(valetId: String?): Flow<Resource<List<LaundryRequest>>>
}

class FirestoreLaundryHistoryStore(private val firestore: BusinessFirestore) : LaundryHistoryStore {

    override fun observe(valetId: String?): Flow<Resource<List<LaundryRequest>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            val base: Query = firestore.collection(LAUNDRY_HISTORY_COLLECTION)
            val query = if (valetId != null) base.whereEqualTo("laundryValetId", valetId) else base
            query.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_LAUNDRY_HISTORY_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // Every document goes through the tolerant parser of Part 22A; ESTIMATE shows a just-saved request's time at once.
                        val requests = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseLaundryRequest(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(requests))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_LAUNDRY_HISTORY_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_LAUNDRY_HISTORY_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }
}

/** Reads `laundry_requests` live for Laundry History. It never writes: History is read-only. */
@Singleton
class LaundryHistoryRepository(private val store: LaundryHistoryStore) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore) : this(FirestoreLaundryHistoryStore(firestore))

    /**
     * [valetId] set = only that valet's requests (the laundry valet role); null = every request of the business.
     * Soft-deleted requests (`isDeleted == true`) are left out.
     */
    fun observe(valetId: String?): Flow<Resource<List<LaundryRequest>>> = store.observe(valetId).map { r ->
        when (r) {
            is Resource.Success -> Resource.Success(r.data.filter { !it.isDeleted })
            else -> r
        }
    }
}
