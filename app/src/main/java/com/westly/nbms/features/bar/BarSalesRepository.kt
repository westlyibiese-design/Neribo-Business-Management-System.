package com.westly.nbms.features.bar

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val BAR_SALES_TAG = "BarSales"
internal const val BAR_SALE_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val BAR_SALE_POST_STEP_TIMEOUT_MS = 5_000L

/** The one database call Part 21A needs for sales. [FirestoreBarSalesStore] is the real one; the unit tests use a fake. */
interface BarSalesStore {
    /** Creates `bar_orders/{new}` from [payload] in ONE transaction and returns the new id. */
    suspend fun createSale(payload: Map<String, Any?>): String
}

class FirestoreBarSalesStore(
    private val firestore: BusinessFirestore
) : BarSalesStore {

    override suspend fun createSale(payload: Map<String, Any?>): String {
        val saleId = firestore.collection(BAR_ORDERS_COLLECTION).document().id
        firestore.runTransaction { tx, fs ->
            tx.set(fs.doc(BAR_ORDERS_COLLECTION, saleId), payload.resolveBarSaleServerTime())
            Unit
        }
        return saleId
    }
}

private fun Map<String, Any?>.resolveBarSaleServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === BarServerTime) FieldValue.serverTimestamp() else v }

/**
 * Records bar sales (`place` only). Observing sales and marking them served belong to Part 21B, in its own file.
 * A sale starts as `status: "pending"` and `approvalStatus: "pending"`; it counts as revenue only once approved in Finance → Approvals.
 */
@Singleton
class BarSalesRepository(
    private val store: BarSalesStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {
    /** The app builds the repository with the real Firestore store; the tests pass a fake store to the primary constructor. */
    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager, audit: AuditLogger, notifier: Notifier) :
        this(FirestoreBarSalesStore(firestore), session, audit, notifier)

    /**
     * Saves the sale in ONE transaction (20 s limit), then does the best-effort extras (audit entry, new-sale alert).
     * Throws [BarSaleException] with a message for the person; nothing is saved in that case.
     */
    suspend fun place(cart: List<BarCartLine>, form: BarSaleForm): BarSaleResult {
        if (cart.isEmpty()) throw BarSaleException(MSG_BAR_SALE_EMPTY)
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw BarSaleException(MSG_BAR_NOT_SIGNED_IN)
        val attendantName = signedIn.user.name
        val payload = buildBarSalePayload(cart, form, signedIn.user.uid, attendantName)
        val total = barCartTotal(cart)

        val committedId: String? = try {
            withTimeoutOrNull(BAR_SALE_TRANSACTION_TIMEOUT_MS) { store.createSale(payload) }
        } catch (e: TimeoutCancellationException) {
            null
        }
        val saleId = committedId ?: throw BarSaleException(MSG_BAR_SALE_TIMEOUT)

        afterPlaced(saleId, attendantName, total)
        return BarSaleResult(saleId, total, cart.size)
    }

    /** Audit entry and "new sale" alert. One failure never hides the other, and neither turns a saved sale into an error. */
    private suspend fun afterPlaced(saleId: String, attendantName: String, total: Double) {
        supervisorScope {
            launch { guarded("audit") { audit.log("new_bar_sale", BAR_ORDERS_COLLECTION, saleId, null, mapOf("total" to total)) } }
            launch { guarded("sale alert") { notifier.notifyNewSale(attendantName, total, "bar", "/admin/bar/sales-history") } }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(BAR_SALE_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(BAR_SALES_TAG, "Bar sale $what step failed", e)
        }
    }
}
