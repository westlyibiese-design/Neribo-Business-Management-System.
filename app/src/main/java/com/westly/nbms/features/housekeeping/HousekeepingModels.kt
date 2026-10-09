package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms
import androidx.compose.material3.MaterialTheme
import java.time.Instant

// ---- Enums (Part interface, section 2.0) ----------------------------------------------------

enum class TaskType(val key: String, val label: String) {
    CHECKOUT_CLEANING("checkout_cleaning", "Check-out Cleaning"), OCCUPIED_SERVICE("occupied_service", "Occupied Room Service"),
    MANUAL("manual", "Manual / Ad-hoc"), MAINTENANCE_FOLLOWUP("maintenance_followup", "Maintenance Follow-up"), CLEANING("cleaning", "Cleaning")
}

enum class TaskStatus(val key: String) { PENDING("pending"), IN_PROGRESS("in_progress"), COMPLETED("completed"), SKIPPED("skipped") }

enum class TaskPriority(val key: String, val label: String) { LOW("low", "Low"), MEDIUM("medium", "Medium"), HIGH("high", "High"), URGENT("urgent", "Urgent") }

// ---- Models ---------------------------------------------------------------------------------

data class HousekeepingTask(
    val id: String, val roomId: String, val roomNumber: String, val type: TaskType, val status: TaskStatus, val priority: TaskPriority,
    val instructions: String?, val assignedTo: String?, val assignedToName: String?, val assignedBy: String?, val assignedByName: String?,
    val scheduledFor: Instant?, val source: String, val bookingId: String?, val dayKey: String, val weight: Double,
    val homeOwnerId: String?, val homeOwnerName: String?, val rebalanced: Boolean,
    val createdAt: Instant?, val updatedAt: Instant?, val startedAt: Instant?, val completedAt: Instant?,
    val completedBy: String?, val completedByName: String?, val skipReason: String?, val isDeleted: Boolean)

data class RoomAssignmentGroup(
    val id: String, val housekeeperId: String, val housekeeperName: String, val roomIds: List<String>, val roomNumbers: List<String>,
    val startDate: Instant?, val endDate: Instant?, val notes: String?, val status: String /* "active" | "ended" */,
    val createdBy: String?, val createdByName: String?, val createdAt: Instant?, val updatedAt: Instant?, val isDeleted: Boolean)

data class RoomAssignment(val roomId: String, val roomNumber: String, val housekeeperId: String, val housekeeperName: String,
    val groupId: String, val startDate: Instant?, val endDate: Instant?, val status: String)

// ---- Tolerant readers (never throw) ---------------------------------------------------------

/** Reads a stored time as an [Instant]: Firestore Timestamp, java.util.Date, java.time.Instant or kotlinx Instant; anything else is null. */
private fun readInstant(value: Any?): Instant? = when (value) {
    null -> null
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    is java.util.Date -> value.toInstant()
    is Instant -> value
    is kotlinx.datetime.Instant -> Instant.ofEpochSecond(value.epochSeconds, value.nanosecondsOfSecond.toLong())
    else -> null
}

/** Text, or null when the field is missing or not text. */
private fun readText(value: Any?): String? = (value as? String)

private fun readTextOrEmpty(value: Any?): String = readText(value) ?: ""

private fun readDouble(value: Any?): Double = (value as? Number)?.toDouble() ?: 0.0

private fun readBoolean(value: Any?): Boolean = value as? Boolean ?: false

private fun readStringList(value: Any?): List<String> =
    (value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

private fun taskTypeOf(key: String?): TaskType = TaskType.entries.firstOrNull { it.key == key } ?: TaskType.CLEANING
private fun taskStatusOf(key: String?): TaskStatus = TaskStatus.entries.firstOrNull { it.key == key } ?: TaskStatus.PENDING
private fun taskPriorityOf(key: String?): TaskPriority = TaskPriority.entries.firstOrNull { it.key == key } ?: TaskPriority.MEDIUM

/**
 * Tolerant parsers (Phase 24 writes the same shapes): missing numbers 0, missing text null or "", unknown type -> CLEANING,
 * unknown status -> PENDING, unknown priority -> MEDIUM, missing lists empty. Never throw.
 */
fun parseHousekeepingTask(id: String, data: Map<String, Any?>): HousekeepingTask = HousekeepingTask(
    id = id,
    roomId = readTextOrEmpty(data["roomId"]),
    roomNumber = readTextOrEmpty(data["roomNumber"]),
    type = taskTypeOf(readText(data["type"])),
    status = taskStatusOf(readText(data["status"])),
    priority = taskPriorityOf(readText(data["priority"])),
    instructions = readText(data["instructions"]),
    assignedTo = readText(data["assignedTo"]),
    assignedToName = readText(data["assignedToName"]),
    assignedBy = readText(data["assignedBy"]),
    assignedByName = readText(data["assignedByName"]),
    scheduledFor = readInstant(data["scheduledFor"]),
    source = readTextOrEmpty(data["source"]),
    bookingId = readText(data["bookingId"]),
    dayKey = readTextOrEmpty(data["dayKey"]),
    weight = readDouble(data["weight"]),
    homeOwnerId = readText(data["homeOwnerId"]),
    homeOwnerName = readText(data["homeOwnerName"]),
    rebalanced = readBoolean(data["rebalanced"]),
    createdAt = readInstant(data["createdAt"]),
    updatedAt = readInstant(data["updatedAt"]),
    startedAt = readInstant(data["startedAt"]),
    completedAt = readInstant(data["completedAt"]),
    completedBy = readText(data["completedBy"]),
    completedByName = readText(data["completedByName"]),
    skipReason = readText(data["skipReason"]),
    isDeleted = readBoolean(data["isDeleted"])
)

fun parseAssignmentGroup(id: String, data: Map<String, Any?>): RoomAssignmentGroup = RoomAssignmentGroup(
    id = id,
    housekeeperId = readTextOrEmpty(data["housekeeperId"]),
    housekeeperName = readTextOrEmpty(data["housekeeperName"]),
    roomIds = readStringList(data["roomIds"]),
    roomNumbers = readStringList(data["roomNumbers"]),
    startDate = readInstant(data["startDate"]),
    endDate = readInstant(data["endDate"]),
    notes = readText(data["notes"]),
    status = readTextOrEmpty(data["status"]),
    createdBy = readText(data["createdBy"]),
    createdByName = readText(data["createdByName"]),
    createdAt = readInstant(data["createdAt"]),
    updatedAt = readInstant(data["updatedAt"]),
    isDeleted = readBoolean(data["isDeleted"])
)

fun parseRoomAssignment(roomId: String, data: Map<String, Any?>): RoomAssignment = RoomAssignment(
    roomId = roomId,
    roomNumber = readTextOrEmpty(data["roomNumber"]),
    housekeeperId = readTextOrEmpty(data["housekeeperId"]),
    housekeeperName = readTextOrEmpty(data["housekeeperName"]),
    groupId = readTextOrEmpty(data["groupId"]),
    startDate = readInstant(data["startDate"]),
    endDate = readInstant(data["endDate"]),
    status = readTextOrEmpty(data["status"])
)

// ---- Priority pill --------------------------------------------------------------------------

private fun pillColors(lightBg: Long, lightFg: Long, darkBg: Long, darkFg: Long): Pair<PillColors, PillColors> =
    PillColors(Color(0xFF000000 or lightBg), Color(0xFF000000 or lightFg)) to
        PillColors(Color(0xFF000000 or darkBg).copy(alpha = 0.30f), Color(0xFF000000 or darkFg))

/** low slate, medium blue, high orange, urgent red (Tailwind 100/800 light, 900@30%/400 dark). */
private val PRIORITY_PILLS: Map<TaskPriority, Pair<PillColors, PillColors>> = mapOf(
    TaskPriority.LOW to pillColors(0xF1F5F9, 0x334155, 0x334155, 0x94A3B8),
    TaskPriority.MEDIUM to pillColors(0xDBEAFE, 0x1E40AF, 0x1E3A8A, 0x60A5FA),
    TaskPriority.HIGH to pillColors(0xFFEDD5, 0x9A3412, 0x7C2D12, 0xFB923C),
    TaskPriority.URGENT to pillColors(0xFEE2E2, 0x991B1B, 0x7F1D1D, 0xF87171)
)

@Composable
fun PriorityPill(priority: TaskPriority, modifier: Modifier = Modifier) {
    val pair = PRIORITY_PILLS.getValue(priority)
    val colors = if (MaterialTheme.nbms.isDark) pair.second else pair.first
    Text(
        text = priority.label,
        color = colors.content,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
