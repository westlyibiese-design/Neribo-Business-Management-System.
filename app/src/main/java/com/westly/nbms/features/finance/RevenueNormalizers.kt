package com.westly.nbms.features.finance

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import java.time.Instant

/*
 * Raw Firestore shapes of the five income collections (Westly field names). Every field has a default so a
 * missing field never crashes a read. They are read-only views: the ledger only ever writes the approval fields.
 */

internal data class RawPayment(
    @DocumentId val id: String = "",
    val type: String? = null,
    val guestName: String? = null,
    val amount: Double = 0.0,
    val paymentMethod: String? = null,
    val createdAt: Timestamp? = null,
    val recordedBy: String? = null,
    val recordedByName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

internal data class RawSale(
    @DocumentId val id: String = "",
    val customerName: String? = null,
    val total: Double = 0.0,
    val paymentMethod: String? = null,
    val createdAt: Timestamp? = null,
    val staffId: String? = null,
    val staffName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

internal data class RawOrder(
    @DocumentId val id: String = "",
    val status: String? = null,
    val customerName: String? = null,
    val roomNumber: String? = null,
    val tableNumber: String? = null,
    val total: Double = 0.0,
    val paymentMethod: String? = null,
    val createdAt: Timestamp? = null,
    val waiterId: String? = null,
    val waiterName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

internal data class RawBarOrder(
    @DocumentId val id: String = "",
    val status: String? = null,
    val customerName: String? = null,
    val roomNumber: String? = null,
    val tableNumber: String? = null,
    val total: Double = 0.0,
    val paymentMethod: String? = null,
    val createdAt: Timestamp? = null,
    val barAttendantId: String? = null,
    val barAttendantName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

internal data class RawLaundry(
    @DocumentId val id: String = "",
    val status: String? = null,
    val guestName: String? = null,
    val roomNumber: String? = null,
    val charge: Double = 0.0,
    val paymentMethod: String? = null,
    val createdAt: Timestamp? = null,
    val laundryValetId: String? = null,
    val laundryValetName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

internal const val NO_VALUE = "—"

internal fun Timestamp?.toJavaInstant(): Instant? = this?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

/** "approved" / "rejected"; anything else, including a missing value, is pending. */
internal fun parseApprovalStatus(raw: String?): ApprovalStatus = when (raw?.trim()?.lowercase()) {
    "approved" -> ApprovalStatus.APPROVED
    "rejected" -> ApprovalStatus.REJECTED
    else -> ApprovalStatus.PENDING
}

internal fun ApprovalStatus.key(): String = name.lowercase()

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun orderGuest(customerName: String?, roomNumber: String?, tableNumber: String?): String =
    customerName.clean()
        ?: roomNumber.clean()?.let { "Room $it" }
        ?: tableNumber.clean()?.let { "Table $it" }
        ?: "Guest"

private val ROOM_PAYMENT_TYPES = setOf("room_payment", "walk_in_payment", "deposit", "stay_extension")

private fun paymentTypeLabel(type: String?): String = when (type) {
    "room_payment" -> "Room Payment"
    "walk_in_payment" -> "Walk-In Payment"
    "deposit" -> "Deposit"
    "refund" -> "Refund"
    "stay_extension" -> "Stay Extension"
    "other" -> "Other"
    else -> type.clean() ?: "Payment"
}

// ── exclusion rules ──

internal fun RawPayment.isIncluded(): Boolean = !isDeleted
internal fun RawSale.isIncluded(): Boolean = !isDeleted
internal fun RawOrder.isIncluded(): Boolean = !isDeleted && status != "cancelled"
internal fun RawBarOrder.isIncluded(): Boolean = !isDeleted && status != "cancelled"
internal fun RawLaundry.isIncluded(): Boolean = !isDeleted && status != "cancelled" && charge > 0.0

// ── normalisers: one per source collection ──

internal fun normalizePayment(r: RawPayment) = RevenueTransaction(
    id = r.id,
    source = SourceCollection.PAYMENTS,
    category = if (r.type in ROOM_PAYMENT_TYPES) RevenueCategory.ROOM else RevenueCategory.OTHER,
    typeLabel = paymentTypeLabel(r.type),
    guestName = r.guestName.clean() ?: NO_VALUE,
    amount = r.amount,
    paymentMethod = r.paymentMethod.clean() ?: NO_VALUE,
    date = r.createdAt.toJavaInstant(),
    recordedBy = r.recordedBy,
    recordedByName = r.recordedByName.clean() ?: NO_VALUE,
    approvalStatus = parseApprovalStatus(r.approvalStatus),
    approvedBy = r.approvedBy,
    approvedByName = r.approvedByName,
    approvedAt = r.approvedAt.toJavaInstant(),
    rejectedReason = r.rejectedReason
)

internal fun normalizeSale(r: RawSale) = RevenueTransaction(
    id = r.id,
    source = SourceCollection.SALES,
    category = RevenueCategory.SALES,
    typeLabel = "Retail Sale",
    guestName = r.customerName.clean() ?: "Walk-in customer",
    amount = r.total,
    paymentMethod = r.paymentMethod.clean() ?: NO_VALUE,
    date = r.createdAt.toJavaInstant(),
    recordedBy = r.staffId,
    recordedByName = r.staffName.clean() ?: NO_VALUE,
    approvalStatus = parseApprovalStatus(r.approvalStatus),
    approvedBy = r.approvedBy,
    approvedByName = r.approvedByName,
    approvedAt = r.approvedAt.toJavaInstant(),
    rejectedReason = r.rejectedReason
)

internal fun normalizeOrder(r: RawOrder) = RevenueTransaction(
    id = r.id,
    source = SourceCollection.ORDERS,
    category = RevenueCategory.RESTAURANT,
    typeLabel = "Restaurant Order",
    guestName = orderGuest(r.customerName, r.roomNumber, r.tableNumber),
    amount = r.total,
    paymentMethod = r.paymentMethod.clean() ?: NO_VALUE,
    date = r.createdAt.toJavaInstant(),
    recordedBy = r.waiterId,
    recordedByName = r.waiterName.clean() ?: NO_VALUE,
    approvalStatus = parseApprovalStatus(r.approvalStatus),
    approvedBy = r.approvedBy,
    approvedByName = r.approvedByName,
    approvedAt = r.approvedAt.toJavaInstant(),
    rejectedReason = r.rejectedReason
)

internal fun normalizeBarOrder(r: RawBarOrder) = RevenueTransaction(
    id = r.id,
    source = SourceCollection.BAR_ORDERS,
    category = RevenueCategory.BAR,
    typeLabel = "Bar Sale",
    guestName = orderGuest(r.customerName, r.roomNumber, r.tableNumber),
    amount = r.total,
    paymentMethod = r.paymentMethod.clean() ?: NO_VALUE,
    date = r.createdAt.toJavaInstant(),
    recordedBy = r.barAttendantId,
    recordedByName = r.barAttendantName.clean() ?: NO_VALUE,
    approvalStatus = parseApprovalStatus(r.approvalStatus),
    approvedBy = r.approvedBy,
    approvedByName = r.approvedByName,
    approvedAt = r.approvedAt.toJavaInstant(),
    rejectedReason = r.rejectedReason
)

internal fun normalizeLaundry(r: RawLaundry) = RevenueTransaction(
    id = r.id,
    source = SourceCollection.LAUNDRY_REQUESTS,
    category = RevenueCategory.LAUNDRY,
    typeLabel = "Laundry Service",
    guestName = r.guestName.clean() ?: r.roomNumber.clean()?.let { "Room $it" } ?: "Guest",
    amount = r.charge,
    paymentMethod = r.paymentMethod.clean() ?: NO_VALUE,
    date = r.createdAt.toJavaInstant(),
    recordedBy = r.laundryValetId,
    recordedByName = r.laundryValetName.clean() ?: NO_VALUE,
    approvalStatus = parseApprovalStatus(r.approvalStatus),
    approvedBy = r.approvedBy,
    approvedByName = r.approvedByName,
    approvedAt = r.approvedAt.toJavaInstant(),
    rejectedReason = r.rejectedReason
)
