package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/** The five attendance statuses. [key] is what is stored; [label] is what the person reads. */
enum class AttendanceStatus(val key: String, val label: String) {
    PRESENT("present", "Present"),
    ABSENT("absent", "Absent"),
    LATE("late", "Late"),
    LEAVE("leave", "Leave"),
    HALF_DAY("half_day", "Half Day");

    companion object {
        /** An unknown or missing key becomes [PRESENT]. */
        fun fromKey(key: String?): AttendanceStatus = entries.firstOrNull { it.key == key } ?: PRESENT
    }
}

/**
 * `businesses/{bid}/attendance/{staffId}__{yyyy-MM-dd}`: one document per staff member per day.
 * Older records may have no [dateKey]; use `AttendanceRules.recordDateKey` to read the day.
 */
data class AttendanceRecord(
    @DocumentId val id: String = "",
    val staffId: String = "",
    val staffName: String = "",
    val staffRole: String? = null,
    val dateKey: String? = null,
    val date: Timestamp? = null,
    val status: String = "present",
    val clockIn: String? = null,
    val clockOut: String? = null,
    val notes: String? = null,
    val recordedBy: String = "",
    val recordedByName: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    // the annotation keeps the stored field name "isDeleted" that every phase uses
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** A read-only view of one `users` mirror document. */
data class AttendanceUser(
    @DocumentId val id: String = "",
    val name: String = "",
    val role: String? = null,
    val status: String = "",
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** What one row of the Record Attendance page holds. Blank strings mean "nothing entered". */
data class AttendanceRowState(
    val status: AttendanceStatus = AttendanceStatus.PRESENT,
    val clockIn: String = "",
    val clockOut: String = "",
    val notes: String = ""
)

/** Values that win over the edited row (for example the "Check In now" button). A null field changes nothing. */
data class AttendanceRowOverrides(
    val status: AttendanceStatus? = null,
    val clockIn: String? = null,
    val clockOut: String? = null
)

/** All the records of one day, sorted by staff name. */
data class DayGroup(val dateKey: String, val records: List<AttendanceRecord>)

/** The four numbers at the top of the register. */
data class TodaySummary(val present: Int, val absent: Int, val late: Int, val totalStaff: Int)
