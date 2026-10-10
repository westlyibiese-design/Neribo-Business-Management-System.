package com.westly.nbms.features.opslog

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.util.Format
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaInstant
import java.time.Instant
import kotlinx.datetime.Instant as KInstant

// Pure Lost & Found rules (Phase 25A): labels, search and status filter, permissions, form checks, history order and the exact Firestore payloads.

internal const val LOST_FOUND_COLLECTION = "lost_found"
internal const val LOST_FOUND_PIN_SIGN_OUT_DELAY_MS = 2_500L

internal const val MSG_LOST_FOUND_LOAD_FAILED = "We couldn't load lost & found items."
internal const val MSG_LOST_FOUND_NOT_SIGNED_IN = "You are signed out. Please sign in again."
internal const val MSG_LOST_FOUND_NO_PERMISSION = "You don't have permission to do that."
internal const val MSG_LOST_FOUND_TIMEOUT = "Saving took too long. Check your connection and try again."
internal const val MSG_LOST_FOUND_GENERIC = "Something went wrong. Please try again."

internal const val TITLE_MISSING_DATE_TIME = "Missing Date/Time"
internal const val MSG_MISSING_DATE_TIME = "Please enter when the item was found."
internal const val TITLE_MISSING_DETAILS = "Missing Details"
internal const val MSG_MISSING_DETAILS = "Item name, room, and housekeeper name are required."
internal const val MSG_DESCRIPTION_REQUIRED = "Description is required."

/** Marker the real store swaps for the server's time when it writes. Keeps the payloads testable without Firebase. */
internal object LostFoundServerTime

/** Marker the real store swaps for `FieldValue.arrayUnion(entry)` on `statusHistory`. */
internal data class LostFoundHistoryAppend(val entry: Map<String, Any?>)

// ── permissions ──

/** Who may log a found item: Super Admin, Manager, Housekeeping. */
fun lostFoundCanCreate(role: Role): Boolean =
    role == Role.SUPER_ADMIN || role == Role.MANAGER || role == Role.HOUSEKEEPING

/** Who may change an item's status: Super Admin, Manager (the database enforces the same). */
fun lostFoundCanManageStatus(role: Role): Boolean =
    role == Role.SUPER_ADMIN || role == Role.MANAGER

// ── list: labels, sort, filter ──

/** "{n} item(s)". */
fun lostFoundSubtitle(count: Int): String = "$count item(s)"

/** The status dropdown: All Status first, then the four labels. */
internal const val ALL_STATUS_LABEL = "All Status"

/** Newest `foundAt` first; an item with no time yet goes last; ties keep a stable order by id. */
fun sortLostFound(items: List<LostFoundItem>): List<LostFoundItem> =
    items.sortedWith(
        compareBy<LostFoundItem> { it.foundAt == null }
            .thenByDescending { it.foundAt }
            .thenBy { it.id }
    )

/**
 * Search (case-insensitive over item name, description, room number and found-by name) and the status filter
 * ([status] null = All Status). Soft-deleted items never show. The order of [items] is kept.
 */
fun filterLostFound(items: List<LostFoundItem>, query: String, status: ItemStatus?): List<LostFoundItem> {
    val q = query.trim().lowercase()
    return items.filter { item ->
        if (item.isDeleted) return@filter false
        if (status != null && item.status != status) return@filter false
        if (q.isEmpty()) return@filter true
        item.itemName.lowercase().contains(q) ||
            (item.description?.lowercase()?.contains(q) == true) ||
            item.roomNumber.lowercase().contains(q) ||
            item.foundByName.lowercase().contains(q)
    }
}

// ── detail sheet ──

/** The status buttons of the detail sheet: every status except the current one, in the usual order. */
fun lostFoundMarkTargets(current: ItemStatus): List<ItemStatus> = ItemStatus.entries.filter { it != current }

/** "Mark Returned to Guest". */
fun lostFoundMarkLabel(target: ItemStatus): String = "Mark ${target.label}"

/** History newest first. An entry with no time goes last; entries with the same time show the later-saved one first. */
fun lostFoundHistoryNewestFirst(entries: List<StatusHistoryEntry>): List<StatusHistoryEntry> =
    entries.reversed().sortedWith(
        compareBy<StatusHistoryEntry> { it.changedAt == null }.thenByDescending { it.changedAt }
    )

/** "Returned to Guest by Ada · 12 Mar 2025, 14:30". */
fun lostFoundHistoryLine(entry: StatusHistoryEntry): String =
    "${entry.statusLabel} by ${entry.changedByName} · ${lostFoundDateTime(entry.changedAt)}"

/** "12 Mar 2025, 14:30", or "—" when there is no time yet. */
fun lostFoundDateTime(at: Instant?): String =
    if (at == null) "—" else Format.dateTime(KInstant.fromEpochMilliseconds(at.toEpochMilli()))

// ── Log Found Item form ──

/** A room in the Room dropdown: "Room 201 — Deluxe Room". */
data class LostFoundRoomOption(val id: String, val number: String, val type: String) {
    val label: String get() = "Room $number — $type"
}

/** What was typed in the Log Found Item sheet. */
data class LogFoundForm(
    val itemName: String = "",
    val description: String = "",
    /** The chosen room; while null the manual room number field shows. */
    val roomId: String? = null,
    val manualRoom: String = "",
    val foundDate: LocalDate? = null,
    val foundTime: LocalTime? = null,
    val foundBy: String = "",
    val status: ItemStatus = ItemStatus.STORED,
    val notes: String = "",
    val photoUrl: String = ""
)

/** A checked form, ready to save. */
data class LogFoundInput(
    val itemName: String,
    val description: String,
    val roomId: String?,
    val roomNumber: String,
    val foundAt: Instant,
    val foundBy: String,
    val status: ItemStatus,
    val notes: String?,
    val photoUrl: String?
)

sealed interface LogFoundCheck {
    data class Ready(val input: LogFoundInput) : LogFoundCheck
    /** A destructive toast. */
    data class Toast(val title: String, val message: String) : LogFoundCheck
    /** The Description field shows "Description is required.". */
    data object DescriptionMissing : LogFoundCheck
}

/** The business time zone; Africa/Lagos when the saved name is missing or unknown. */
internal fun lostFoundZone(id: String?): TimeZone = try {
    if (id.isNullOrBlank()) TimeZone.of("Africa/Lagos") else TimeZone.of(id)
} catch (e: Exception) {
    TimeZone.of("Africa/Lagos")
}

/** [date] at [time] in the business time zone. */
internal fun lostFoundInstant(date: LocalDate, time: LocalTime, zoneId: String?): Instant =
    LocalDateTime(date, time).toInstant(lostFoundZone(zoneId)).toJavaInstant()

/**
 * Checks the form in this order: date/time, then item name + room + found-by, then description.
 * The room number is the chosen room's number, or the typed one while no room is chosen.
 */
fun validateLogFound(form: LogFoundForm, rooms: List<LostFoundRoomOption>, zoneId: String?): LogFoundCheck {
    val date = form.foundDate
    val time = form.foundTime
    if (date == null || time == null) return LogFoundCheck.Toast(TITLE_MISSING_DATE_TIME, MSG_MISSING_DATE_TIME)
    val foundAt = try {
        lostFoundInstant(date, time, zoneId)
    } catch (e: Exception) {
        return LogFoundCheck.Toast(TITLE_MISSING_DATE_TIME, MSG_MISSING_DATE_TIME)
    }

    val chosen = form.roomId?.let { id -> rooms.firstOrNull { it.id == id } }
    val roomNumber = (chosen?.number ?: form.manualRoom).trim()
    val itemName = form.itemName.trim()
    val foundBy = form.foundBy.trim()
    if (itemName.isEmpty() || roomNumber.isEmpty() || foundBy.isEmpty()) {
        return LogFoundCheck.Toast(TITLE_MISSING_DETAILS, MSG_MISSING_DETAILS)
    }

    val description = form.description.trim()
    if (description.isEmpty()) return LogFoundCheck.DescriptionMissing

    return LogFoundCheck.Ready(
        LogFoundInput(
            itemName = itemName,
            description = description,
            roomId = chosen?.id,
            roomNumber = roomNumber,
            foundAt = foundAt,
            foundBy = foundBy,
            status = form.status,
            notes = form.notes.nullIfBlank(),
            photoUrl = form.photoUrl.nullIfBlank()
        )
    )
}

private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

// ── payloads ──

/** One `statusHistory` entry. [at] is the phone's clock. */
internal fun lostFoundHistoryEntry(status: ItemStatus, uid: String, name: String, at: Instant, note: String?): Map<String, Any?> =
    mapOf(
        "status" to status.key,
        "changedBy" to uid,
        "changedByName" to name,
        "changedAt" to at,
        "note" to note.nullIfBlank()
    )

/** The whole new `lost_found/{id}` document. Empty description / notes / photo are saved as null. */
internal fun buildLostFoundCreatePayload(input: LogFoundInput, uid: String, name: String, now: Instant): Map<String, Any?> = mapOf(
    "itemName" to input.itemName.trim(),
    "description" to input.description.nullIfBlank(),
    "roomId" to input.roomId,
    "roomNumber" to input.roomNumber.trim(),
    "foundAt" to input.foundAt,
    "foundByName" to input.foundBy.trim(),
    "foundBy" to uid,
    "status" to input.status.key,
    "notes" to input.notes.nullIfBlank(),
    "photoUrl" to input.photoUrl.nullIfBlank(),
    "createdBy" to uid,
    "createdByName" to name,
    "createdAt" to LostFoundServerTime,
    "updatedBy" to uid,
    "updatedByName" to name,
    "updatedAt" to LostFoundServerTime,
    "statusHistory" to listOf(lostFoundHistoryEntry(input.status, uid, name, now, "Item logged")),
    "isDeleted" to false
)

/** ONLY the fields a status change writes; `statusHistory` is appended to, never replaced. */
internal fun buildLostFoundStatusPayload(target: ItemStatus, note: String?, uid: String, name: String, now: Instant): Map<String, Any?> = mapOf(
    "status" to target.key,
    "updatedAt" to LostFoundServerTime,
    "updatedBy" to uid,
    "updatedByName" to name,
    "statusHistory" to LostFoundHistoryAppend(lostFoundHistoryEntry(target, uid, name, now, note))
)

/** The audit action of a status change: `lost_found_status_changed:stored→claimed`. */
internal fun lostFoundStatusAuditAction(old: ItemStatus, new: ItemStatus): String =
    "lost_found_status_changed:${old.key}→${new.key}"

/** Returned to guest and Claimed send the "claimed" notification. */
internal fun lostFoundNotifiesClaimed(target: ItemStatus): Boolean =
    target == ItemStatus.RETURNED_TO_GUEST || target == ItemStatus.CLAIMED
