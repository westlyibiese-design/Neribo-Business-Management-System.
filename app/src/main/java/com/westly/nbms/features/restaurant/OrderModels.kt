package com.westly.nbms.features.restaurant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/** The kitchen status of an order. [key] is what is stored; [label] is what the person reads. */
enum class OrderStatus(val key: String, val label: String) {
    PENDING("pending", "Pending"),
    PREPARING("preparing", "Preparing"),
    SERVED("served", "Served"),
    CANCELLED("cancelled", "Cancelled");

    companion object {
        /** The status with this stored key, or null for a missing or unknown key. */
        fun fromKey(key: String?): OrderStatus? = entries.firstOrNull { it.key == key }
    }
}

/** How an order is paid. [key] is what is stored (Westly's values). */
enum class OrderPaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    CARD("card", "Card"),
    BANK_TRANSFER("bank_transfer", "Bank Transfer"),
    POS("pos", "POS Terminal"),
    ROOM_CHARGE("room_charge", "Charge to Room");

    companion object {
        fun fromKey(key: String?): OrderPaymentMethod? = entries.firstOrNull { it.key == key }
    }
}

/** One line of the order being built. Menu lines use the menu item's id; manual lines use `manual-{millis}-{random6}`. */
data class OrderCartLine(
    val id: String,
    val name: String,
    val price: Double,
    val quantity: Int,
    val isManual: Boolean = false
)

/** What the person typed in the order panel (everything except the lines). */
data class OrderForm(
    val roomNumber: String = "",
    val tableNumber: String = "",
    val guestName: String = "",
    val notes: String = "",
    val payment: OrderPaymentMethod = OrderPaymentMethod.CASH
)

/** One ordered line inside `orders/{id}.items`. */
data class OrderLine(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0,
    val subtotal: Double = 0.0,
    // The annotation keeps the stored field name "isManual" (a Kotlin "is" property would otherwise be read as "manual").
    @get:PropertyName("isManual") val isManual: Boolean = false
)

/** Firestore `businesses/{bid}/orders/{id}`. Money is in naira (major unit); field names are the ones Westly used. */
data class Order(
    @DocumentId val id: String = "",
    val waiterId: String = "",
    val waiterName: String = "",
    val customerName: String? = null,
    val roomNumber: String? = null,
    val tableNumber: String? = null,
    val items: List<OrderLine> = emptyList(),
    val total: Double = 0.0,
    val paymentMethod: String = "cash",
    val notes: String? = null,
    val hasManualItems: Boolean = false,
    val status: String = "pending",
    val approvalStatus: String = "pending",
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val updatedBy: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** What the success screen shows after a placed order (a snapshot, because the order panel is cleared). */
data class OrderSuccess(val itemCount: Int, val total: Double)

/** The result of a placed order. */
data class OrderResult(val orderId: String, val total: Double, val itemCount: Int)

/** A problem with a clear sentence for the person (shown in the "Failed" / "Update Failed" toast). */
class OrderException(message: String) : Exception(message)
