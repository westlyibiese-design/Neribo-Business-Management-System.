package com.westly.nbms.features.attendance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every pure attendance rule, for the register (28A) and the Record Attendance page (28B).
 * Dates are `yyyy-MM-dd` keys in the business time zone.
 */
object AttendanceRules {

    private val KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** The deterministic document id: one record per staff member per day. */
    fun attendanceDocId(staffId: String, dateKey: String): String = "${staffId}__$dateKey"

    fun dateKeyOf(date: LocalDate): String = date.format(KEY_FORMAT)

    /** Midnight at the start of [dateKey] in [zone]; stored in the `date` field for old readers. */
    fun midnightOf(dateKey: String, zone: ZoneId): Instant =
        LocalDate.parse(dateKey, KEY_FORMAT).atStartOfDay(zone).toInstant()

    /** `dateKey`, or (older records) the `date` timestamp read as a local date in [zone], or null when neither exists. */
    fun recordDateKey(record: AttendanceRecord, zone: ZoneId): String? {
        record.dateKey?.takeIf { it.isNotBlank() }?.let { return it }
        val stamp = record.date ?: return null
        return Instant.ofEpochSecond(stamp.seconds, stamp.nanoseconds.toLong()).atZone(zone).toLocalDate().format(KEY_FORMAT)
    }

    /** Accounts that are not deleted and are "active", A to Z by name. */
    fun activeStaff(users: List<AttendanceUser>): List<AttendanceUser> =
        users.filter { !it.isDeleted && it.status == "active" }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    /** staffId to that person's non-deleted record of [dateKey]. The record with the standard id wins over a stray duplicate. */
    fun existingForDate(records: List<AttendanceRecord>, dateKey: String, zone: ZoneId): Map<String, AttendanceRecord> =
        records
            .filter { !it.isDeleted && recordDateKey(it, zone) == dateKey }
            .sortedBy { it.id == attendanceDocId(it.staffId, dateKey) }
            .associateBy { it.staffId }

    /** Rows for the staff who already have a record that day. Everyone else stays untouched (no row). */
    fun seedRows(staff: List<AttendanceUser>, existingForDate: Map<String, AttendanceRecord>): Map<String, AttendanceRowState> {
        val rows = LinkedHashMap<String, AttendanceRowState>()
        staff.forEach { person ->
            existingForDate[person.id]?.let { rows[person.id] = rowOf(it) }
        }
        return rows
    }

    /**
     * Rows are re-seeded only when the day changed or the attendance data has just finished its first load,
     * never on every realtime update (that would wipe what the person is typing).
     */
    fun shouldReseed(
        previousDateKey: String?,
        newDateKey: String,
        attendanceLoadedBefore: Boolean,
        attendanceLoadedNow: Boolean
    ): Boolean = previousDateKey != newDateKey || (!attendanceLoadedBefore && attendanceLoadedNow)

    /**
     * What a row shows: the edited row when there is one, else the saved record, else the default
     * (Present, no times, no notes); then [overrides] on top. The saved record never replaces an edited row.
     */
    fun effectiveRow(
        existing: AttendanceRecord?,
        edited: AttendanceRowState?,
        overrides: AttendanceRowOverrides? = null
    ): AttendanceRowState {
        val base = edited ?: existing?.let { rowOf(it) } ?: AttendanceRowState()
        if (overrides == null) return base
        return base.copy(
            status = overrides.status ?: base.status,
            clockIn = overrides.clockIn ?: base.clockIn,
            clockOut = overrides.clockOut ?: base.clockOut
        )
    }

    fun auditAction(existing: AttendanceRecord?): String =
        if (existing != null) "attendance_updated" else "attendance_recorded"

    /** The current time as "HH:mm" in the business [zone]. */
    fun nowHHmm(now: Instant, zone: ZoneId): String {
        val t = now.atZone(zone)
        return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    /** Saving is blocked while the staff list or the attendance list failed to load. */
    fun canSave(usersFailed: Boolean, attendanceFailed: Boolean): Boolean = !usersFailed && !attendanceFailed

    // ---- register ----

    /**
     * Not deleted, day within [from]..[to] (both inclusive, compared as text), and the staff name contains [search]
     * (ignoring case and surrounding spaces). A blank [from] / [to] / [search] does not limit anything.
     */
    fun filterRegister(
        records: List<AttendanceRecord>,
        from: String,
        to: String,
        search: String,
        zone: ZoneId
    ): List<AttendanceRecord> {
        val needle = search.trim().lowercase()
        return records.filter { r ->
            if (r.isDeleted) return@filter false
            val key = recordDateKey(r, zone) ?: return@filter false
            if (from.isNotBlank() && key < from) return@filter false
            if (to.isNotBlank() && key > to) return@filter false
            needle.isEmpty() || r.staffName.lowercase().contains(needle)
        }
    }

    /** One group per day, newest day first; inside a day, A to Z by staff name. Records with no day are left out. */
    fun groupByDate(records: List<AttendanceRecord>, zone: ZoneId): List<DayGroup> =
        records
            .mapNotNull { r -> recordDateKey(r, zone)?.let { it to r } }
            .groupBy({ it.first }, { it.second })
            .map { (key, list) ->
                DayGroup(key, list.sortedWith(compareBy({ it.staffName.lowercase() }, { it.staffId })))
            }
            .sortedByDescending { it.dateKey }

    /** Present / Absent / Late counts of the non-deleted records of [todayKey]; [totalStaff] is passed through. */
    fun todaySummary(records: List<AttendanceRecord>, todayKey: String, totalStaff: Int, zone: ZoneId): TodaySummary {
        val today = records.filter { !it.isDeleted && recordDateKey(it, zone) == todayKey }
        return TodaySummary(
            present = today.count { AttendanceStatus.fromKey(it.status) == AttendanceStatus.PRESENT },
            absent = today.count { AttendanceStatus.fromKey(it.status) == AttendanceStatus.ABSENT },
            late = today.count { AttendanceStatus.fromKey(it.status) == AttendanceStatus.LATE },
            totalStaff = totalStaff
        )
    }

    private fun rowOf(record: AttendanceRecord) = AttendanceRowState(
        status = AttendanceStatus.fromKey(record.status),
        clockIn = record.clockIn.orEmpty(),
        clockOut = record.clockOut.orEmpty(),
        notes = record.notes.orEmpty()
    )
}
