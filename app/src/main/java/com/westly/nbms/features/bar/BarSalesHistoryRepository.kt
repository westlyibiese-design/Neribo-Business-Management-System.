package com.westly.nbms.features.bar

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

internal const val BAR_HISTORY_UPDATE_TIMEOUT_MS = 20_000L

/** The database calls Sales History needs. [FirestoreBarSalesHistoryStore] is the real one; the unit tests use a fake. */
interface BarSalesHistoryStore {
    /** Live `bar_orders`. [attendantId] set = only that attendant's sales (`barAttendantId == attendantId`); null = every sale. */
    fun observe(attendantId: String?): Flow<Resource<List<BarSale>>>

    /** Updates ONLY [fields] on the existing `bar_orders/{saleId}` document. The payload may hold [BarHistoryServerTime]. */
    suspend fun update(saleId: String, fields: Map<String, Any?>)
}

class FirestoreBarSalesHistoryStore(
    private val firestore: BusinessFirestore
) : BarSalesHistoryStore {

    override fun observe(attendantId: String?): Flow<Resource<List<BarSale>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            val base: Query = firestore.collection(BAR_HISTORY_ORDERS_PATH)
            val query = if (attendantId != null) base.whereEqualTo("barAttendantId", attendantId) else base
            query.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_BAR_HISTORY_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // Every document goes through the tolerant parser of Part 21A; ESTIMATE shows a just-saved sale's time at once.
                        val sales = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseBarSale(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(sales))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_BAR_HISTORY_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_BAR_HISTORY_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override suspend fun update(saleId: String, fields: Map<String, Any?>) {
        firestore.update(
            BAR_HISTORY_ORDERS_PATH,
            saleId,
            fields.mapValues { (_, v) -> if (v === BarHistoryServerTime) FieldValue.serverTimestamp() else v }
        )
    }
}

/** Reads `bar_orders` live and marks pending sales as served. Approving a sale belongs to Finance → Approvals, never to this page. */
@Singleton
class BarSalesHistoryRepository(
    private val store: BarSalesHistoryStore,
    private val session: SessionManager
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager) :
        this(FirestoreBarSalesHistoryStore(firestore), session)

    /** [attendantId] set = only that attendant's sales (the bar attendant role); null = every sale of the business. */
    fun observe(attendantId: String?): Flow<Resource<List<BarSale>>> = store.observe(attendantId)

    /**
     * Sets `{status: "served", updatedAt, updatedBy}` on a pending sale. Throws [BarStockException] with a message for the person when
     * the sale is not pending, the role may not do this, nobody is signed in, or the update takes longer than 20 seconds.
     */
    suspend fun markServed(sale: BarSale) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw BarStockException(MSG_BAR_HISTORY_NOT_SIGNED_IN)
        if (!canMarkBarSaleServed(signedIn.user.role)) throw BarStockException(MSG_BAR_HISTORY_NOT_ALLOWED)
        if (sale.status != BarSaleStatus.PENDING) throw BarStockException(MSG_BAR_HISTORY_NOT_PENDING)
        try {
            withTimeout(BAR_HISTORY_UPDATE_TIMEOUT_MS) { store.update(sale.id, buildMarkServedFields(signedIn.user.uid)) }
        } catch (e: TimeoutCancellationException) {
            throw BarStockException(MSG_BAR_HISTORY_TIMEOUT)
        }
    }
}
