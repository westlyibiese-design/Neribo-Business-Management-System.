package com.westly.nbms.features.sales

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/** How a sale was paid. [key] is what is stored (Westly's values), [label] is what the person reads. */
enum class PaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    CARD("card", "Card"),
    BANK_TRANSFER("bank_transfer", "Bank Transfer"),
    POS("pos", "POS Terminal"),
    ROOM_CHARGE("room_charge", "Charge to Room");

    companion object {
        fun fromKey(key: String?): PaymentMethod? = entries.firstOrNull { it.key == key }
    }
}

/** Manual lines have no stock ceiling. */
const val UNLIMITED_STOCK: Int = Int.MAX_VALUE

/** One line in the cart. [available] is the stock on the shelf when the line was added ([UNLIMITED_STOCK] for manual lines). */
data class CartItem(
    val id: String,
    val name: String,
    val price: Double,
    val quantity: Int,
    val available: Int = UNLIMITED_STOCK,
    val isManual: Boolean = false
)

/** One sold line inside `sales/{id}.items`. */
data class SaleLine(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0,
    val subtotal: Double = 0.0,
    // The annotation keeps the stored field name "isManual" (a Kotlin "is" property would otherwise be read as "manual").
    @get:PropertyName("isManual") val isManual: Boolean = false
)

/** Firestore `businesses/{bid}/sales/{id}`. Money is in naira (major unit); field names are the ones Westly used. */
data class Sale(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val customerName: String? = null,
    val items: List<SaleLine> = emptyList(),
    val total: Double = 0.0,
    val paymentMethod: String = "cash",
    val notes: String? = null,
    val createdAt: Timestamp? = null,
    val category: String = "merchandise",
    val hasManualItems: Boolean = false,
    val approvalStatus: String = "pending",
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** What the success screen shows after a saved sale (a snapshot, because the cart is cleared). */
data class SaleSuccess(
    val lines: List<CartItem>,
    val itemCount: Int,
    val total: Double,
    val timeText: String
)

/** A problem with a clear sentence for the person (shown in the "Sale Failed" toast). */
class SalesException(message: String) : Exception(message)
