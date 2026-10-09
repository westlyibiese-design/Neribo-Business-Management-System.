package com.westly.nbms.features.bar

import com.google.firebase.Timestamp
import java.time.Instant

// Shared bar models (Part interface, section 2.0). Part 21B imports these exactly as written and never copies or edits them.

enum class DrinkCategory(val key: String, val label: String) {
    BEER("beer", "Beer"), WINE("wine", "Wine"), SPIRITS("spirits", "Spirits"),
    COCKTAILS("cocktails", "Cocktails"), SOFT_DRINKS("soft_drinks", "Soft Drinks"), OTHER("other", "Other")
}

data class DrinkItem(val id: String, val name: String, val image: String, val description: String,
                     val price: Double, val category: DrinkCategory, val available: Boolean)

enum class BarSaleStatus(val key: String, val label: String) {
    PENDING("pending", "Pending"), SERVED("served", "Served"), CANCELLED("cancelled", "Cancelled")
}

enum class BarPaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"), CARD("card", "Card"), BANK_TRANSFER("bank_transfer", "Bank Transfer"),
    POS("pos", "POS Terminal"), ROOM_CHARGE("room_charge", "Charge to Room")
}

data class BarSaleLine(val id: String, val name: String, val price: Double, val quantity: Int,
                       val subtotal: Double, val isManual: Boolean)

data class BarSale(
    val id: String, val barAttendantId: String, val barAttendantName: String,
    val customerName: String?, val roomNumber: String?, val tableNumber: String?,
    val items: List<BarSaleLine>, val total: Double, val paymentMethod: String, val notes: String?,
    val hasManualItems: Boolean, val status: BarSaleStatus, val createdAt: Instant?, val isDeleted: Boolean)

/** Tolerant parser for a `bar_orders/{id}` document: missing numbers are 0, missing text is "" (or null for the nullable fields),
 *  an unknown or missing status is PENDING, a missing items list is empty. Never throws. */
fun parseBarSale(id: String, data: Map<String, Any?>): BarSale = try {
    val items = (data["items"] as? List<*>)?.mapNotNull { parseBarSaleLine(it) } ?: emptyList()
    BarSale(
        id = id,
        barAttendantId = barText(data["barAttendantId"]),
        barAttendantName = barText(data["barAttendantName"]),
        customerName = barNullableText(data["customerName"]),
        roomNumber = barNullableText(data["roomNumber"]),
        tableNumber = barNullableText(data["tableNumber"]),
        items = items,
        total = barNumber(data["total"]),
        paymentMethod = barText(data["paymentMethod"]),
        notes = barNullableText(data["notes"]),
        hasManualItems = data["hasManualItems"] as? Boolean ?: false,
        status = BarSaleStatus.entries.firstOrNull { it.key == data["status"] } ?: BarSaleStatus.PENDING,
        createdAt = barInstant(data["createdAt"]),
        isDeleted = data["isDeleted"] as? Boolean ?: false
    )
} catch (e: Exception) {
    BarSale(id, "", "", null, null, null, emptyList(), 0.0, "", null, false, BarSaleStatus.PENDING, null, false)
}

private fun parseBarSaleLine(raw: Any?): BarSaleLine? {
    val map = raw as? Map<*, *> ?: return null
    return BarSaleLine(
        id = barText(map["id"]),
        name = barText(map["name"]),
        price = barNumber(map["price"]),
        quantity = barNumber(map["quantity"]).toInt(),
        subtotal = barNumber(map["subtotal"]),
        isManual = map["isManual"] as? Boolean ?: false
    )
}

private fun barText(value: Any?): String = value as? String ?: ""

private fun barNullableText(value: Any?): String? = value as? String

/** A number, or numeric text; anything else (missing, NaN, infinite) is 0. */
private fun barNumber(value: Any?): Double {
    val d = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    return if (d.isNaN() || d.isInfinite()) 0.0 else d
}

private fun barInstant(value: Any?): Instant? = when (value) {
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is Instant -> value
    else -> null
}
