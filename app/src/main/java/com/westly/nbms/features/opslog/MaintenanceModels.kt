package com.westly.nbms.features.opslog

import com.google.firebase.Timestamp
import java.time.Instant

// Maintenance models (Phase 25B). Firestore: businesses/{bid}/maintenance/{id}.

/** How urgent a request is. [key] is what is saved in Firestore; [rank] 0 is the most urgent. */
enum class MaintenancePriority(val key: String, val label: String, val rank: Int) {
    LOW("low", "Low", 3),
    MEDIUM("medium", "Medium", 2),
    HIGH("high", "High", 1),
    CRITICAL("critical", "Critical", 0);

    /** High and critical requests get a red-tinted card border. */
    val isUrgent: Boolean get() = this == HIGH || this == CRITICAL

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): MaintenancePriority? = entries.firstOrNull { it.key == key }
    }
}

/** A maintenance request as the screens use it. Anything whose status is not "closed" counts as open. */
data class MaintenanceRequest(
    val id: String,
    val roomId: String?,
    /** "—" when the request has no room. */
    val roomNumber: String,
    val title: String,
    val description: String?,
    val priority: MaintenancePriority,
    /** The saved status text ("open" or "closed"). */
    val status: String,
    val reportedBy: String?,
    val reportedByName: String,
    val createdAt: Instant?,
    val closedAt: Instant?,
    val closedBy: String?,
    val closedByName: String?,
    val isDeleted: Boolean
) {
    val isClosed: Boolean get() = status == MAINTENANCE_STATUS_CLOSED
    val isOpen: Boolean get() = !isClosed
}

internal const val MAINTENANCE_COLLECTION = "maintenance"
internal const val MAINTENANCE_STATUS_OPEN = "open"
internal const val MAINTENANCE_STATUS_CLOSED = "closed"

/**
 * Tolerant parser for a `maintenance/{id}` document: missing text is null (blank counts as missing),
 * an unknown or missing priority is Medium, a missing status is "open", a missing room number or name is "—".
 * Never throws.
 */
fun parseMaintenanceRequest(id: String, data: Map<String, Any?>): MaintenanceRequest = try {
    MaintenanceRequest(
        id = id,
        roomId = mtText(data["roomId"]),
        roomNumber = mtText(data["roomNumber"]) ?: "—",
        title = mtText(data["title"]) ?: "—",
        description = mtText(data["description"]),
        priority = MaintenancePriority.fromKey(data["priority"] as? String) ?: MaintenancePriority.MEDIUM,
        status = mtText(data["status"]) ?: MAINTENANCE_STATUS_OPEN,
        reportedBy = mtText(data["reportedBy"]),
        reportedByName = mtText(data["reportedByName"]) ?: "—",
        createdAt = mtInstant(data["createdAt"]),
        closedAt = mtInstant(data["closedAt"]),
        closedBy = mtText(data["closedBy"]),
        closedByName = mtText(data["closedByName"]),
        isDeleted = data["isDeleted"] as? Boolean ?: false
    )
} catch (e: Exception) {
    MaintenanceRequest(
        id = id, roomId = null, roomNumber = "—", title = "—", description = null, priority = MaintenancePriority.MEDIUM,
        status = MAINTENANCE_STATUS_OPEN, reportedBy = null, reportedByName = "—", createdAt = null, closedAt = null,
        closedBy = null, closedByName = null, isDeleted = false
    )
}

private fun mtText(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

private fun mtInstant(value: Any?): Instant? = when (value) {
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is Instant -> value
    is java.util.Date -> value.toInstant()
    else -> null
}
