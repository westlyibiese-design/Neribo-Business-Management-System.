package com.westly.nbms.features.shifts

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Pure scheduling rules. No Android, no Firestore. Times are "HH:mm"; dates are in the business time zone. */
object ShiftLogic {
    const val MAX_OCCURRENCES = 60
    private const val MAX_WALK_DAYS = 400
    private const val DAY = 1440

    fun toMinutes(hhmm: String): Int {
        val parts = hhmm.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && h != null && m != null && h in 0..23 && m in 0..59) { "Invalid time \"$hhmm\"" }
        return h * 60 + m
    }

    /** Start and end on a timeline measured from midnight of the start date. The end may pass 1440. */
    private fun span(start: String, end: String, endsNextDay: Boolean): Pair<Int, Int> {
        val s = toMinutes(start)
        var e = toMinutes(end) + if (endsNextDay) DAY else 0
        if (e <= s) e += DAY
        return s to e
    }

    fun shiftsOverlap(aStart: String, aEnd: String, aNext: Boolean, bStart: String, bEnd: String, bNext: Boolean): Boolean {
        val (aS, aE) = span(aStart, aEnd, aNext)
        val (bS, bE) = span(bStart, bEnd, bNext)
        return aS < bE && bS < aE
    }

    fun generateOccurrenceDates(startDate: LocalDate, recurrence: ShiftRecurrence): List<LocalDate> {
        if (recurrence.type == RecurrenceType.NONE) return listOf(startDate)
        val until = recurrence.until ?: startDate.plusMonths(3)
        val result = ArrayList<LocalDate>()
        for (i in 0 until MAX_WALK_DAYS) {
            val day = startDate.plusDays(i.toLong())
            if (day.isAfter(until)) break
            val matches = when (recurrence.type) {
                RecurrenceType.WEEKLY ->
                    recurrence.daysOfWeek.isEmpty() || (day.dayOfWeek.value % 7) in recurrence.daysOfWeek
                else -> true
            }
            if (matches) {
                result += day
                if (result.size >= MAX_OCCURRENCES) break
            }
        }
        return result
    }

    /**
     * Clashes between the candidate times on each candidate date and the person's [existing] shifts. Cancelled shifts
     * and [excludeShiftId] are ignored. Beside same-day shifts, an overnight shift that started the day BEFORE blocks the
     * early hours of the candidate day, and an overnight candidate blocks a shift that starts the day AFTER.
     */
    fun findConflicts(
        candidateDates: List<LocalDate>, startTime: String, endTime: String, endsNextDay: Boolean,
        existing: List<Shift>, excludeShiftId: String? = null
    ): List<ConflictInfo> {
        val (cS, cE) = span(startTime, endTime, endsNextDay)
        val active = existing.filter { it.status != ShiftStatus.CANCELLED.key && (excludeShiftId == null || it.id != excludeShiftId) }
        val byDate = active.groupBy { it.date }
        val out = ArrayList<ConflictInfo>()
        for (date in candidateDates) {
            val key = date.toDateKey()
            fun check(shifts: List<Shift>?, offset: Int) {
                shifts?.forEach { s ->
                    val (sS, sE) = span(s.startTime, s.endTime, s.endsNextDay)
                    if (cS < sE + offset && sS + offset < cE) out += ConflictInfo(key, s.label, "${s.startTime}–${s.endTime}")
                }
            }
            check(byDate[key], 0)
            check(byDate[date.minusDays(1).toDateKey()], -DAY)
            check(byDate[date.plusDays(1).toDateKey()], DAY)
        }
        return out
    }

    /** True while [now] is inside the shift's time span. Start is included, end is not. */
    fun isOnDutyNow(shift: Shift, now: LocalDateTime): Boolean {
        if (shift.status == ShiftStatus.CANCELLED.key) return false
        val date = shift.date.toLocalDateOrNull() ?: return false
        val startAbs = toMinutes(shift.startTime)
        val endRaw = toMinutes(shift.endTime)
        val endAbs = endRaw + if (shift.endsNextDay || endRaw <= startAbs) DAY else 0
        val nowAbs = Duration.between(date.atStartOfDay(), now).toMinutes()
        return nowAbs >= startAbs && nowAbs < endAbs
    }

    /** Inclusive range. Weeks run Sunday to Saturday. */
    fun viewRange(mode: ShiftViewMode, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (mode) {
        ShiftViewMode.DAY -> anchor to anchor
        ShiftViewMode.WEEK -> {
            val sunday = anchor.minusDays((anchor.dayOfWeek.value % 7).toLong())
            sunday to sunday.plusDays(6)
        }
        ShiftViewMode.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
    }

    /** [direction] is -1 or +1. */
    fun step(mode: ShiftViewMode, anchor: LocalDate, direction: Int): LocalDate {
        val d = if (direction < 0) -1L else 1L
        return anchor.plusDays(
            when (mode) { ShiftViewMode.DAY -> d; ShiftViewMode.WEEK -> 7 * d; ShiftViewMode.MONTH -> 30 * d }
        )
    }

    /** The shifts "Cancel Series" must cancel: still scheduled and starting on or after [fromDate]. */
    fun selectSeriesToCancel(series: List<Shift>, fromDate: LocalDate): List<Shift> = series.filter {
        it.status != ShiftStatus.CANCELLED.key && (it.date.toLocalDateOrNull()?.let { d -> !d.isBefore(fromDate) } ?: false)
    }
}
