package com.westly.nbms.features.opslog

import com.google.firebase.Timestamp
import java.time.Instant

// Lost & Found models (Phase 25A). Firestore: businesses/{bid}/lost_found/{id}.

/** The four states of a found item. [key] is what is saved in Firestore. */
enum class ItemStatus(val key: String, val label: String) {
    STORED("stored", "Stored"),
    RETURNED_TO_GUEST("returned_to_guest", "Returned to Guest"),
    CLAIMED("claimed", "Claimed"),
    DISPOSED("disposed", "Disposed");

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): ItemStatus? = entries.firstOrNull { it.key == key }
    }
}

/** One line of an item's history: `{status, changedBy, changedByName, changedAt, note}`. */
data class StatusHistoryEntry(
    /** The saved status key (kept as text so an unknown old value is still shown). */
    val status: String,
    val changedBy: String?,
    val changedByName: String,
    val changedAt: Instant?,
    val note: String?
) {
    /** "Returned to Guest"; an unknown key is shown as it was saved. */
    val statusLabel: String get() = ItemStatus.fromKey(status)?.label ?: status
}

/** A found item as the screens use it. */
data class LostFoundItem(
    val id: String,
    val itemName: String,
    val description: String?,
    val roomId: String?,
    val roomNumber: String,
    val foundAt: Instant?,
    val foundByName: String,
    val foundBy: String?,
    val status: ItemStatus,
    val notes: String?,
    val photoUrl: String?,
    /** Older documents may carry it; this phase only reads it (used in the claimed notification). */
    val guestName: String?,
    val createdBy: String?,
    val createdByName: String,
    val createdAt: Instant?,
    val updatedBy: String?,
    val updatedByName: String?,
    val updatedAt: Instant?,
    val statusHistory: List<StatusHistoryEntry>,
    val isDeleted: Boolean
)

/**
 * Tolerant parser for a `lost_found/{id}` document: missing text is null (blank counts as missing),
 * an unknown or missing status is Stored, a missing name is "—". Never throws.
 */
fun parseLostFoundItem(id: String, data: Map<String, Any?>): LostFoundItem = try {
    LostFoundItem(
        id = id,
        itemName = lfText(data["itemName"]) ?: "—",
        description = lfText(data["description"]),
        roomId = lfText(data["roomId"]),
        roomNumber = lfText(data["roomNumber"]) ?: "—",
        foundAt = lfInstant(data["foundAt"]),
        foundByName = lfText(data["foundByName"]) ?: "—",
        foundBy = lfText(data["foundBy"]),
        status = ItemStatus.fromKey(data["status"] as? String) ?: ItemStatus.STORED,
        notes = lfText(data["notes"]),
        photoUrl = lfText(data["photoUrl"]),
        guestName = lfText(data["guestName"]),
        createdBy = lfText(data["createdBy"]),
        createdByName = lfText(data["createdByName"]) ?: "—",
        createdAt = lfInstant(data["createdAt"]),
        updatedBy = lfText(data["updatedBy"]),
        updatedByName = lfText(data["updatedByName"]),
        updatedAt = lfInstant(data["updatedAt"]),
        statusHistory = lfHistory(data["statusHistory"]),
        isDeleted = data["isDeleted"] as? Boolean ?: false
    )
} catch (e: Exception) {
    LostFoundItem(
        id = id, itemName = "—", description = null, roomId = null, roomNumber = "—", foundAt = null, foundByName = "—",
        foundBy = null, status = ItemStatus.STORED, notes = null, photoUrl = null, guestName = null, createdBy = null,
        createdByName = "—", createdAt = null, updatedBy = null, updatedByName = null, updatedAt = null,
        statusHistory = emptyList(), isDeleted = false
    )
}

private fun lfText(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

private fun lfInstant(value: Any?): Instant? = when (value) {
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is Instant -> value
    is java.util.Date -> value.toInstant()
    else -> null
}

private fun lfHistory(value: Any?): List<StatusHistoryEntry> =
    (value as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        StatusHistoryEntry(
            status = lfText(m["status"]) ?: return@mapNotNull null,
            changedBy = lfText(m["changedBy"]),
            changedByName = lfText(m["changedByName"]) ?: "—",
            changedAt = lfInstant(m["changedAt"]),
            note = lfText(m["note"])
        )
    }
