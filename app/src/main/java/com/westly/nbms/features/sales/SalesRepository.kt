package com.westly.nbms.features.sales

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.inventory.InventoryItem
import com.westly.nbms.features.inventory.InventoryLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Sales"
internal const val TRANSACTION_TIMEOUT_MS = 20_000L
internal const val POST_STEP_TIMEOUT_MS = 5_000L

internal const val MSG_SALE_TIMEOUT = "The sale took too long to save. Check your connection and try again."
internal const val MSG_SALE_GENERIC = "Something went wrong. Please try again."
internal const val MSG_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_EMPTY_CART = "The cart is empty."

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object SalesServerTime

/** The live stock of one inventory document, read inside the transaction. */
data class StockRead(val quantity: Int, val minStock: Int, val unit: String?)

/** A catalog line after the transaction: the quantity that was committed, with what the low-stock alert needs. */
data class StockOutcome(val name: String, val newQuantity: Int, val minStock: Int, val unit: String?)

/** What the transaction can do. Reads happen first, then writes (a Firestore rule). */
interface SaleTx {
    /** The live item, or null when it is missing or deleted. */
    fun readItem(itemId: String): StockRead?
    fun writeStock(itemId: String, newQuantity: Int)
    fun createSale(payload: Map<String, Any?>)
}

/** The database calls Sales needs. [FirestoreSalesStore] is the real one; the unit tests use a fake. */
interface SalesStore {
    fun observeInventory(): Flow<Resource<List<InventoryItem>>>
    fun observeSales(staffId: String?): Flow<Resource<List<Sale>>>

    /** Runs [block] as ONE transaction; everything it writes is saved together or not at all. Returns the new sale id. */
    suspend fun <R> inTransaction(block: (SaleTx, String) -> R): Pair<String, R>
}

@Singleton
class FirestoreSalesStore @Inject constructor(
    private val firestore: BusinessFirestore
) : SalesStore {

    override fun observeInventory(): Flow<Resource<List<InventoryItem>>> =
        firestore.observeList("inventory", InventoryItem::class.java)

    override fun observeSales(staffId: String?): Flow<Resource<List<Sale>>> =
        firestore.observeList("sales", Sale::class.java) { q -> if (staffId != null) q.whereEqualTo("staffId", staffId) else q }

    override suspend fun <R> inTransaction(block: (SaleTx, String) -> R): Pair<String, R> {
        val saleId = firestore.collection("sales").document().id
        try {
            val result = firestore.runTransaction { tx, fs ->
                val real = object : SaleTx {
                    override fun readItem(itemId: String): StockRead? {
                        val snap = tx.get(fs.doc("inventory", itemId))
                        if (!snap.exists() || snap.getBoolean("isDeleted") == true) return null
                        return StockRead(
                            quantity = snap.getLong("quantity")?.toInt() ?: 0,
                            minStock = snap.getLong("minStock")?.toInt() ?: 0,
                            unit = snap.getString("unit")
                        )
                    }

                    override fun writeStock(itemId: String, newQuantity: Int) {
                        tx.update(
                            fs.doc("inventory", itemId),
                            mapOf("quantity" to newQuantity, "updatedAt" to FieldValue.serverTimestamp())
                        )
                    }

                    override fun createSale(payload: Map<String, Any?>) {
                        tx.set(
                            fs.doc("sales", saleId),
                            payload.mapValues { (_, v) -> if (v === SalesServerTime) FieldValue.serverTimestamp() else v }
                        )
                    }
                }
                block(real, saleId)
            }
            return saleId to result
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.findSalesException() ?: e
        }
    }
}

private fun Throwable.findSalesException(): Throwable? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is SalesException) return current
        if (current is IllegalStateException && current.message?.startsWith("Insufficient stock") == true) return current
        current = current.cause
        depth++
    }
    return null
}

// ── the pure transaction body ──

/**
 * Reads every catalog line's live stock first, checks it, and only then writes: the new quantity for each catalog line and
 * the sale. A missing item or too little stock throws before anything is written. Manual lines never touch inventory.
 */
internal fun performSale(tx: SaleTx, saleId: String, cart: List<CartItem>, payload: Map<String, Any?>): List<StockOutcome> {
    val catalog = cart.filter { !it.isManual }
    val reads = catalog.map { line ->
        val live = tx.readItem(line.id) ?: throw SalesException("Item \"${line.name}\" not found in inventory.")
        InventoryLogic.assertSufficientStock(live.quantity, line.quantity, line.name)
        line to live
    }
    val outcomes = reads.map { (line, live) ->
        val newQty = live.quantity - line.quantity
        tx.writeStock(line.id, newQty)
        StockOutcome(line.name, newQty, live.minStock, live.unit)
    }
    tx.createSale(payload)
    return outcomes
}

/** The new `sales/{id}` document, exactly as Westly wrote it. */
internal fun buildSalePayload(
    cart: List<CartItem>,
    customerName: String?,
    method: PaymentMethod,
    notes: String?,
    staffId: String,
    staffName: String
): Map<String, Any?> = mapOf(
    "staffId" to staffId,
    "staffName" to staffName,
    "customerName" to customerName?.trim()?.ifEmpty { null },
    "items" to cart.map {
        mapOf(
            "id" to it.id,
            "name" to it.name,
            "price" to it.price,
            "quantity" to it.quantity,
            "subtotal" to lineTotal(it),
            "isManual" to it.isManual
        )
    },
    "total" to cartTotal(cart),
    "paymentMethod" to method.key,
    "notes" to notes?.trim()?.ifEmpty { null },
    "createdAt" to SalesServerTime,
    "category" to "merchandise",
    "hasManualItems" to cart.any { it.isManual },
    "approvalStatus" to "pending",
    "approvedBy" to null,
    "approvedByName" to null,
    "approvedAt" to null,
    "rejectedReason" to null,
    "isDeleted" to false
)

data class SaleResult(val saleId: String, val total: Double, val itemCount: Int)

@Singleton
class SalesRepository @Inject constructor(
    private val store: SalesStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {

    fun observeInventory(): Flow<Resource<List<InventoryItem>>> = store.observeInventory()

    /** [staffId] set = only that person's sales (the staff role); null = every sale of the business. */
    fun observeSales(staffId: String?): Flow<Resource<List<Sale>>> = store.observeSales(staffId)

    /**
     * Records the sale in ONE transaction (20 s limit), then does the best-effort extras. Throws [SalesException] (or the
     * "Insufficient stock" IllegalStateException) with a message for the person; nothing is saved in that case.
     */
    suspend fun sell(cart: List<CartItem>, customerName: String?, method: PaymentMethod, notes: String?): SaleResult {
        if (cart.isEmpty()) throw SalesException(MSG_EMPTY_CART)
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw SalesException(MSG_NOT_SIGNED_IN)
        val staffId = signedIn.user.uid
        val staffName = signedIn.user.name
        val payload = buildSalePayload(cart, customerName, method, notes, staffId, staffName)
        val total = cartTotal(cart)

        val committed = try {
            withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                store.inTransaction { tx, id -> performSale(tx, id, cart, payload) }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (committed == null) throw SalesException(MSG_SALE_TIMEOUT)

        val (saleId, outcomes) = committed
        afterCommit(saleId, staffName, total, outcomes)
        return SaleResult(saleId, total, cart.size)
    }

    /** Audit, new-sale alert and one low-stock alert per line that fell to its minimum. One failure never hides the others. */
    private suspend fun afterCommit(saleId: String, staffName: String, total: Double, outcomes: List<StockOutcome>) {
        supervisorScope {
            launch { guarded("audit") { audit.log("new_sale", "sales", saleId, null, mapOf("total" to total)) } }
            launch { guarded("sale alert") { notifier.notifyNewSale(staffName, total, "merchandise") } }
            outcomes.filter { it.newQuantity <= it.minStock }.forEach { o ->
                launch {
                    guarded("low stock alert") {
                        notifier.notifyLowInventory(o.name, o.newQuantity, o.unit?.takeIf { it.isNotBlank() } ?: "units")
                    }
                }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sale $what step failed", e)
        }
    }
}
