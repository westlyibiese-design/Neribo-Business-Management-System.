package com.westly.nbms.features.shifts

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.westly.nbms.core.rbac.Role
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Dates on shifts are plain `yyyy-MM-dd` strings in the business time zone, never UTC instants. */
private val DATE_KEY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun LocalDate.toDateKey(): String = format(DATE_KEY)

/** Parses a `yyyy-MM-dd` key; anything else (blank, wrong shape, impossible date) gives null. */
fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(this, DATE_KEY)
} catch (e: java.time.format.DateTimeParseException) {
    null
}

enum class ShiftStatus(val key: String) { SCHEDULED("scheduled"), CANCELLED("cancelled") }

enum class RecurrenceType { NONE, DAILY, WEEKLY }

/** [daysOfWeek]: 0 = Sunday … 6 = Saturday; empty means every day. [until] is inclusive. */
data class ShiftRecurrence(
    val type: RecurrenceType = RecurrenceType.NONE,
    val daysOfWeek: List<Int> = emptyList(),
    val until: LocalDate? = null
)

/** Firestore `businesses/{bid}/shifts/{id}`. [date] is the day the shift STARTS. */
data class Shift(
    @DocumentId val id: String = "",
    val role: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val date: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val endsNextDay: Boolean = false,
    val label: String = "",
    val notes: String? = null,
    val seriesId: String? = null,
    val status: String = "scheduled",
    val createdBy: String = "",
    val createdByName: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val updatedBy: String? = null,
    val updatedByName: String? = null
)

data class ShiftInput(
    val role: Role,
    val staffId: String,
    val staffName: String,
    val startDate: LocalDate,
    val startTime: String,
    val endTime: String,
    val endsNextDay: Boolean,
    val label: String,
    val notes: String?,
    val recurrence: ShiftRecurrence
)

data class ShiftUpdate(
    val staffId: String,
    val staffName: String,
    val startTime: String,
    val endTime: String,
    val endsNextDay: Boolean,
    val label: String,
    val notes: String?
)

/** [withTime] = "{start}–{end}". */
data class ConflictInfo(val date: String, val withLabel: String, val withTime: String)

/** Built by the screen (27B) from the signed-in user. */
data class ShiftActor(val uid: String, val name: String)

sealed interface ShiftWriteResult {
    data class Success(val count: Int, val firstId: String?) : ShiftWriteResult

    /** Nothing was written. */
    data class Conflicts(val conflicts: List<ConflictInfo>) : ShiftWriteResult
}

enum class ShiftViewMode { DAY, WEEK, MONTH }
