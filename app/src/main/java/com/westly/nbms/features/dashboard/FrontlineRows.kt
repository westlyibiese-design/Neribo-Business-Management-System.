package com.westly.nbms.features.dashboard

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.features.dashboard.data.toDashInstant
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Phase 34A1: small row projections for the front-line dashboards.
//
// The four public rows below are the contract with Phases 34A2 and 34B (defaults on every field). The `Raw*` classes are what
// Firestore actually stores (Timestamps, owning phases' field names); each maps to its row, or to null when the record is
// soft-deleted, so FrontlineRepository never hands a deleted record to a dashboard.
// ─────────────────────────────────────────────────────────────────────────────

/** `sales` / generic "my activity" amount. */
data class AmountRow(val total: Double = 0.0, val createdAt: Instant? = null, val approvalStatus: String? = null)

/** `orders` (waiter) and `bar_orders` (bar attendant). */
data class OrderRow(val status: String? = null, val total: Double = 0.0, val createdAt: Instant? = null)

/** `laundry_requests` (Phase 33's DashLaundry has no deliveredAt, so Phase 34 uses its own row). */
data class FrontlineLaundryRow(val status: String? = null, val deliveredAt: Instant? = null)

/** `housekeeping_tasks`. */
data class HkTaskRow(val roomNumber: String? = null, val priority: String? = null, val status: String? = null)

/** `sales` (Phase 19): `staffId`, `total`, `createdAt`, `approvalStatus`. */
internal data class RawAmountRow(
    @DocumentId val id: String = "",
    val total: Double = 0.0,
    val createdAt: Timestamp? = null,
    val approvalStatus: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): AmountRow? =
        if (isDeleted) null else AmountRow(total = total, createdAt = createdAt.toDashInstant(), approvalStatus = approvalStatus)
}

/** `orders` (Phase 20) and `bar_orders` (Phase 21). */
internal data class RawOrderRow(
    @DocumentId val id: String = "",
    val status: String? = null,
    val total: Double = 0.0,
    val createdAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): OrderRow? =
        if (isDeleted) null else OrderRow(status = status, total = total, createdAt = createdAt.toDashInstant())
}

/** `laundry_requests` (Phase 22). */
internal data class RawFrontlineLaundryRow(
    @DocumentId val id: String = "",
    val status: String? = null,
    val deliveredAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): FrontlineLaundryRow? =
        if (isDeleted) null else FrontlineLaundryRow(status = status, deliveredAt = deliveredAt.toDashInstant())
}

/** `housekeeping_tasks` (Phase 23). */
internal data class RawHkTaskRow(
    @DocumentId val id: String = "",
    val roomNumber: String? = null,
    val priority: String? = null,
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toRowOrNull(): HkTaskRow? =
        if (isDeleted) null else HkTaskRow(roomNumber = roomNumber, priority = priority, status = status)
}

/** `room_assignments` (Phase 23): one document per room of an assignment group; only the status matters here. */
internal data class RawRoomAssignmentRow(
    @DocumentId val id: String = "",
    val status: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun isActive(): Boolean = !isDeleted && status == "active"
}
