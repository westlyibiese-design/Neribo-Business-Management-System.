package com.westly.nbms.features.laundry

import com.google.firebase.Timestamp
import java.time.Instant

// Shared laundry models (Part interface, section 2.0). Part 22B imports these exactly as written and never copies or edits them.

enum class LaundryStatus(val key: String, val label: String) {
    RECEIVED("received", "Received"), WASHING("washing", "Washing"), DRYING("drying", "Drying"),
    IRONING("ironing", "Ironing"), READY("ready", "Ready for Collection"), DELIVERED("delivered", "Delivered"),
    CANCELLED("cancelled", "Cancelled")      // shown/filtered only; no button sets it in this phase
}

enum class PaymentStatus(val key: String, val label: String) { PAID("paid", "Paid"), UNPAID("unpaid", "Unpaid") }

data class LaundryRequest(
    val id: String, val guestName: String?, val roomNumber: String?, val itemsDescription: String?,
    val itemCount: Int, val charge: Double, val paymentMethod: String, val paymentStatus: PaymentStatus,
    val notes: String?, val status: LaundryStatus, val laundryValetId: String?, val laundryValetName: String,
    val createdAt: Instant?, val receivedAt: Instant?, val deliveredAt: Instant?, val isDeleted: Boolean)

/** Tolerant parser for a `laundry_requests/{id}` document: missing numbers are 0 (itemCount falls back to 1),
 *  missing text is null (valet name "—"), unknown or missing status is RECEIVED, unknown paymentStatus is UNPAID. Never throws. */
fun parseLaundryRequest(id: String, data: Map<String, Any?>): LaundryRequest = try {
    LaundryRequest(
        id = id,
        guestName = laundryNullableText(data["guestName"]),
        roomNumber = laundryNullableText(data["roomNumber"]),
        itemsDescription = laundryNullableText(data["itemsDescription"]),
        itemCount = laundryItemCount(data["itemCount"]),
        charge = laundryNumber(data["charge"]),
        paymentMethod = laundryNullableText(data["paymentMethod"]) ?: "",
        paymentStatus = PaymentStatus.entries.firstOrNull { it.key == data["paymentStatus"] } ?: PaymentStatus.UNPAID,
        notes = laundryNullableText(data["notes"]),
        status = LaundryStatus.entries.firstOrNull { it.key == data["status"] } ?: LaundryStatus.RECEIVED,
        laundryValetId = laundryNullableText(data["laundryValetId"]),
        laundryValetName = laundryNullableText(data["laundryValetName"]) ?: "—",
        createdAt = laundryInstant(data["createdAt"]),
        receivedAt = laundryInstant(data["receivedAt"]),
        deliveredAt = laundryInstant(data["deliveredAt"]),
        isDeleted = data["isDeleted"] as? Boolean ?: false
    )
} catch (e: Exception) {
    LaundryRequest(
        id = id, guestName = null, roomNumber = null, itemsDescription = null, itemCount = 1, charge = 0.0,
        paymentMethod = "", paymentStatus = PaymentStatus.UNPAID, notes = null, status = LaundryStatus.RECEIVED,
        laundryValetId = null, laundryValetName = "—", createdAt = null, receivedAt = null, deliveredAt = null, isDeleted = false
    )
}

/** Text as written; blank text counts as missing. */
private fun laundryNullableText(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

/** A number, or numeric text; anything else (missing, NaN, infinite) is 0. */
private fun laundryNumber(value: Any?): Double {
    val d = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    return if (d.isNaN() || d.isInfinite()) 0.0 else d
}

/** A whole number of 1 or more; a missing or unusable count is 1. */
private fun laundryItemCount(value: Any?): Int {
    val n = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
    if (n == null || n.isNaN() || n.isInfinite() || n < 1.0) return 1
    return if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
}

private fun laundryInstant(value: Any?): Instant? = when (value) {
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is Instant -> value
    is java.util.Date -> value.toInstant()
    else -> null
}
