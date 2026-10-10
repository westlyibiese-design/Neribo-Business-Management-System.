package com.westly.nbms.features.opslog

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.util.Format
import java.time.Instant
import kotlinx.datetime.Instant as KInstant

// Pure maintenance helpers (Phase 25B): no Android, no Firebase, so they are unit-tested directly.

internal const val MAINTENANCE_PIN_SIGN_OUT_DELAY_MS = 2_500L
internal const val MAINTENANCE_RECENTLY_CLOSED_LIMIT = 5

internal const val MSG_MAINTENANCE_LOAD_FAILED = "We couldn't load maintenance requests."
internal const val MSG_MAINTENANCE_NOT_SIGNED_IN = "You are signed out. Please sign in again."
internal const val MSG_MAINTENANCE_NO_PERMISSION = "You don't have permission to do that."
internal const val MSG_MAINTENANCE_TIMEOUT = "Saving took too long. Check your connection and try again."
internal const val MSG_MAINTENANCE_GENERIC = "Something went wrong. Please try again."
internal const val MSG_MAINTENANCE_EMPTY = "No open maintenance requests"

internal const val TITLE_MAINTENANCE_MISSING = "Missing Information"
internal const val MSG_MAINTENANCE_ROOM_REQUIRED = "Please select a room."
internal const val MSG_MAINTENANCE_TITLE_REQUIRED = "Please enter an issue title."

// ── permissions ──

/**
 * Who can open the Maintenance page (the drawer shows it to the same roles): Super Admin, Manager, Housekeeping and
 * Operations Manager. Log Request and Close are shown to everyone who can open the page.
 */
fun maintenanceCanOpen(role: Role): Boolean =
    role == Role.SUPER_ADMIN || role == Role.MANAGER || role == Role.HOUSEKEEPING || role == Role.OPERATIONS_MANAGER

fun maintenanceCanLog(role: Role): Boolean = maintenanceCanOpen(role)

fun maintenanceCanClose(role: Role): Boolean = maintenanceCanOpen(role)

// ── labels ──

/** The header subtitle: "3 open request(s)". */
fun maintenanceSubtitle(openCount: Int): String = "$openCount open request(s)"

/** "Room 202". */
fun maintenanceRoomLabel(roomNumber: String): String = "Room $roomNumber"

/** "Reported by Ada · 09 Oct 2026". */
fun maintenanceReportedLine(request: MaintenanceRequest): String =
    "Reported by ${request.reportedByName} · ${maintenanceDate(request.createdAt)}"

fun maintenanceDate(at: Instant?): String =
    if (at == null) "—" else Format.date(KInstant.fromEpochMilliseconds(at.toEpochMilli()))

/** The Priority dropdown, in the order Low, Medium, High, Critical. */
val MAINTENANCE_PRIORITY_OPTIONS: List<MaintenancePriority> = listOf(
    MaintenancePriority.LOW, MaintenancePriority.MEDIUM, MaintenancePriority.HIGH, MaintenancePriority.CRITICAL
)

// ── open / closed split and sorting ──

/**
 * Every request that is not closed (so a missing or odd status counts as open), most urgent first
 * (critical, high, medium, low), then oldest first. A request with no time yet goes last in its priority; ties keep a stable order by id.
 */
fun openMaintenance(all: List<MaintenanceRequest>): List<MaintenanceRequest> =
    all.filter { it.isOpen }
        .sortedWith(
            compareBy<MaintenanceRequest> { it.priority.rank }
                .thenBy { it.createdAt == null }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )

/** The closed requests, most recently closed first. A request with no close time goes last. */
fun closedMaintenance(all: List<MaintenanceRequest>): List<MaintenanceRequest> =
    all.filter { it.isClosed }
        .sortedWith(
            compareBy<MaintenanceRequest> { it.closedAt == null }
                .thenByDescending { it.closedAt }
                .thenBy { it.id }
        )

/** The first five closed requests, newest closed first (the "Recently Closed" list). */
fun recentlyClosedMaintenance(all: List<MaintenanceRequest>): List<MaintenanceRequest> =
    closedMaintenance(all).take(MAINTENANCE_RECENTLY_CLOSED_LIMIT)

// ── Log Request form ──

/** A room in the Room dropdown: "Room 202 — Deluxe Room". */
data class MaintenanceRoomOption(val id: String, val number: String, val type: String) {
    val label: String get() = "Room $number — $type"
}

/** What was typed in the Log Maintenance Request sheet. */
data class MaintenanceForm(
    val roomId: String? = null,
    val title: String = "",
    val description: String = "",
    val priority: MaintenancePriority = MaintenancePriority.MEDIUM
)

/** A checked form, ready to save. */
data class MaintenanceInput(
    val roomId: String,
    val roomNumber: String,
    val title: String,
    val description: String,
    val priority: MaintenancePriority
)

sealed interface MaintenanceCheck {
    data class Ready(val input: MaintenanceInput) : MaintenanceCheck
    /** Something required is missing: show this error toast and keep the sheet open. */
    data class Toast(val title: String, val message: String) : MaintenanceCheck
}

/** Room and Issue Title are required; the text is trimmed. The room must be one of [rooms]. */
fun validateMaintenance(form: MaintenanceForm, rooms: List<MaintenanceRoomOption>): MaintenanceCheck {
    val room = rooms.firstOrNull { it.id == form.roomId }
        ?: return MaintenanceCheck.Toast(TITLE_MAINTENANCE_MISSING, MSG_MAINTENANCE_ROOM_REQUIRED)
    val title = form.title.trim()
    if (title.isEmpty()) return MaintenanceCheck.Toast(TITLE_MAINTENANCE_MISSING, MSG_MAINTENANCE_TITLE_REQUIRED)
    return MaintenanceCheck.Ready(
        MaintenanceInput(
            roomId = room.id,
            roomNumber = room.number,
            title = title,
            description = form.description.trim(),
            priority = form.priority
        )
    )
}

// ── toast messages ──

/** A toast: [title] in bold, [message] under it. */
data class MaintenanceToast(val title: String, val message: String, val isError: Boolean = false)

/** After Log Request. [roomError] is the message of the failure when the room could not be put in Maintenance, else null. */
fun logResultToast(input: MaintenanceInput, roomError: String?): MaintenanceToast =
    if (roomError == null) {
        MaintenanceToast("Maintenance Request Logged", "${input.title} reported for ${maintenanceRoomLabel(input.roomNumber)}.")
    } else {
        MaintenanceToast(
            "Request logged",
            "The request was saved, but the room status couldn't be changed: $roomError"
        )
    }

/** After Close. [roomFreed] is false when the room still holds a guest (the guard refused to free it). */
fun closeResultToast(roomNumber: String, roomFreed: Boolean): MaintenanceToast = when {
    roomNumber == "—" -> MaintenanceToast("Maintenance Closed", "The request is closed.")
    roomFreed -> MaintenanceToast("Maintenance Closed", "Room $roomNumber is now available.")
    else -> MaintenanceToast("Maintenance Closed", "Room $roomNumber stays Occupied.")
}

// ── Firestore payloads ──

/** Stands for the server time; the repository swaps it for `FieldValue.serverTimestamp()`. */
data object MaintenanceServerTime

/** The `maintenance/{new}` document. */
fun buildMaintenanceCreatePayload(input: MaintenanceInput, uid: String, name: String): Map<String, Any?> = mapOf(
    "roomId" to input.roomId,
    "roomNumber" to input.roomNumber,
    "title" to input.title,
    "description" to input.description,
    "priority" to input.priority.key,
    "status" to MAINTENANCE_STATUS_OPEN,
    "reportedBy" to uid,
    "reportedByName" to name,
    "createdAt" to MaintenanceServerTime,
    "isDeleted" to false
)

/** The fields written when a request is closed. Nothing else on the document changes. */
fun buildMaintenanceClosePayload(uid: String, name: String): Map<String, Any?> = mapOf(
    "status" to MAINTENANCE_STATUS_CLOSED,
    "closedAt" to MaintenanceServerTime,
    "closedBy" to uid,
    "closedByName" to name
)

/** The audit action for closing a request. */
internal const val MAINTENANCE_CLOSED_AUDIT_ACTION = "maintenance_closed"
