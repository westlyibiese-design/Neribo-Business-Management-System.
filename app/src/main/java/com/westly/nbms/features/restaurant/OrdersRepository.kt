package com.westly.nbms.features.restaurant

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val ORDERS_TAG = "Orders"
private const val ORDERS_COLLECTION = "orders"
internal const val ORDER_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val ORDER_POST_STEP_TIMEOUT_MS = 5_000L

/** The database calls orders need. [FirestoreOrdersStore] is the real one; the unit tests use a fake. */
interface OrdersStore {
    /** Live orders. [waiterId] set = only that waiter's orders; null = every order of the business. */
    fun observeOrders(waiterId: String?): Flow<Resource<List<Order>>>

    /** Creates `orders/{new}` from [payload] in ONE transaction and returns the new id. */
    suspend fun createOrder(payload: Map<String, Any?>): String

    /** Updates ONLY [fields] on the existing order. */
    suspend fun updateOrder(id: String, fields: Map<String, Any?>)
}

@Singleton
class FirestoreOrdersStore @Inject constructor(
    private val firestore: BusinessFirestore
) : OrdersStore {

    override fun observeOrders(waiterId: String?): Flow<Resource<List<Order>>> =
        firestore.observeList(ORDERS_COLLECTION, Order::class.java) { q ->
            if (waiterId != null) q.whereEqualTo("waiterId", waiterId) else q
        }

    override suspend fun createOrder(payload: Map<String, Any?>): String {
        val orderId = firestore.collection(ORDERS_COLLECTION).document().id
        firestore.runTransaction { tx, fs ->
            tx.set(fs.doc(ORDERS_COLLECTION, orderId), payload.resolveOrderServerTime())
            Unit
        }
        return orderId
    }

    override suspend fun updateOrder(id: String, fields: Map<String, Any?>) {
        firestore.update(ORDERS_COLLECTION, id, fields.resolveOrderServerTime())
    }
}

private fun Map<String, Any?>.resolveOrderServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === OrderServerTime) FieldValue.serverTimestamp() else v }

/** Places restaurant orders, reads them live and moves them through the kitchen statuses. */
@Singleton
class OrdersRepository @Inject constructor(
    private val store: OrdersStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {

    /** [waiterId] set = only that waiter's orders (the waiter role); null = every order of the business. */
    fun observeOrders(waiterId: String?): Flow<Resource<List<Order>>> = store.observeOrders(waiterId)

    /**
     * Saves the order in ONE transaction (20 s limit), then does the best-effort extras (audit entry, new-order alert).
     * Throws [OrderException] with a message for the person; nothing is saved in that case.
     */
    suspend fun place(cart: List<OrderCartLine>, form: OrderForm): OrderResult {
        if (cart.isEmpty()) throw OrderException(MSG_ORDER_EMPTY)
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw OrderException(MSG_ORDER_NOT_SIGNED_IN)
        val waiterName = signedIn.user.name
        val payload = buildOrderPayload(cart, form, signedIn.user.uid, waiterName)
        val total = orderTotal(cart)

        val committedId: String? = try {
            withTimeoutOrNull(ORDER_TRANSACTION_TIMEOUT_MS) { store.createOrder(payload) }
        } catch (e: TimeoutCancellationException) {
            null
        }
        val orderId = committedId ?: throw OrderException(MSG_ORDER_TIMEOUT)

        afterPlaced(orderId, waiterName, total)
        return OrderResult(orderId, total, cart.size)
    }

    /**
     * Moves an order to [to] (`status`, `updatedAt`, `updatedBy`). [current] is the status the person is looking at: a move the
     * rules do not allow throws and writes nothing.
     */
    suspend fun updateStatus(orderId: String, current: String?, to: OrderStatus) {
        if (!canMoveOrder(current, to)) throw OrderException(MSG_ORDER_STATUS_NOT_ALLOWED)
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw OrderException(MSG_ORDER_NOT_SIGNED_IN)
        try {
            store.updateOrder(orderId, buildOrderStatusUpdate(to, signedIn.user.uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OrderException) {
            throw e
        } catch (e: Exception) {
            throw OrderException(e.message?.takeIf { it.isNotBlank() } ?: MSG_ORDER_UPDATE_GENERIC)
        }
    }

    /** Audit entry and "new order" alert. One failure never hides the other, and neither turns a saved order into an error. */
    private suspend fun afterPlaced(orderId: String, waiterName: String, total: Double) {
        supervisorScope {
            launch { guarded("audit") { audit.log("new_order", ORDERS_COLLECTION, orderId, null, mapOf("total" to total)) } }
            launch { guarded("order alert") { notifier.notifyNewSale(waiterName, total, "restaurant", "/admin/orders/history") } }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(ORDER_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ORDERS_TAG, "Order $what step failed", e)
        }
    }
}
