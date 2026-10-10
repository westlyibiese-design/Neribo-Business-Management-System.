package com.westly.nbms.features.shifts

import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure helpers for the Shift Scheduling screen and form (Phase 27B). No Android, no Firestore.
// The scheduling rules themselves (overlaps, repeats, on-duty, ranges) live in 27A's ShiftLogic.

internal val DEFAULT_SHIFT_ROLE: Role = Role.RECEPTIONIST
internal const val DEFAULT_START_TIME = "08:00"
internal const val DEFAULT_END_TIME = "16:00"
internal const val MAX_CONFLICT_LINES = 6
internal const val MSG_SHIFT_LOAD_ERROR = "We couldn't load the shift schedule."
internal const val MSG_SHIFT_GENERIC = "Something went wrong. Please try again."
internal const val MSG_SHIFT_TIMEOUT = "Saving took too long. Check your connection and try again."
internal const val MSG_SHIFT_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_SHIFT_BAD_DATE = "This shift has an invalid date."

/** Sunday first, matching 0 = Sunday … 6 = Saturday in [ShiftRecurrence.daysOfWeek]. */
internal val WEEKDAY_LABELS: List<String> = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** The eleven roles that work shifts, in the order of the Role list. */
internal fun shiftRoleOptions(): List<Role> = Rbac.shiftRoles.sortedBy { it.ordinal }

/** An active staff member who can be put on a shift. */
data class ShiftStaff(val id: String, val name: String)

// ---- Form -------------------------------------------------------------------------------------

/** Everything the shift form holds while the sheet is open. Times are "HH:mm". */
internal data class ShiftFormState(
    val staffId: String? = null,
    val label: String = "",
    val date: LocalDate,
    val startTime: String = DEFAULT_START_TIME,
    val endTime: String = DEFAULT_END_TIME,
    val endsNextDay: Boolean = false,
    val notes: String = "",
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val weekdays: Set<Int> = emptySet(),
    val until: LocalDate? = null
)

/** A new shift on [date]; the first staff member is chosen already. */
internal fun newShiftForm(date: LocalDate, staff: List<ShiftStaff>): ShiftFormState =
    ShiftFormState(staffId = staff.firstOrNull()?.id, date = date)

/** The form filled from an existing shift. [fallbackDate] is used only if the stored date is unreadable. */
internal fun editShiftForm(shift: Shift, fallbackDate: LocalDate): ShiftFormState = ShiftFormState(
    staffId = shift.staffId.ifBlank { null },
    label = shift.label,
    date = shift.date.toLocalDateOrNull() ?: fallbackDate,
    startTime = shift.startTime.ifBlank { DEFAULT_START_TIME },
    endTime = shift.endTime.ifBlank { DEFAULT_END_TIME },
    endsNextDay = shift.endsNextDay,
    notes = shift.notes.orEmpty()
)

/** The person the form points at, or the first in the list when nothing valid is chosen yet. */
internal fun resolveShiftStaff(form: ShiftFormState, staff: List<ShiftStaff>): ShiftStaff? =
    staff.firstOrNull { it.id == form.staffId } ?: if (form.staffId == null) staff.firstOrNull() else null

/** The first thing wrong with the form. [description] is the second line of the toast, when there is one. */
internal enum class ShiftFormIssue(val title: String, val description: String?) {
    NO_STAFF("Select a staff member", null),
    MISSING_LABEL("Give the shift a label", "e.g. Morning Shift, Night Shift"),
    NO_WEEKDAY("Pick at least one weekday", null)
}

/** Order: staff, then label, then (new weekly shifts only) a weekday. null means the form may be sent. */
internal fun validateShiftForm(form: ShiftFormState, staffSelected: Boolean, isEdit: Boolean): ShiftFormIssue? = when {
    !staffSelected -> ShiftFormIssue.NO_STAFF
    form.label.isBlank() -> ShiftFormIssue.MISSING_LABEL
    !isEdit && form.recurrenceType == RecurrenceType.WEEKLY && form.weekdays.isEmpty() -> ShiftFormIssue.NO_WEEKDAY
    else -> null
}

internal fun ShiftFormState.toRecurrence(): ShiftRecurrence = when (recurrenceType) {
    RecurrenceType.NONE -> ShiftRecurrence(RecurrenceType.NONE)
    RecurrenceType.DAILY -> ShiftRecurrence(RecurrenceType.DAILY, emptyList(), until)
    RecurrenceType.WEEKLY -> ShiftRecurrence(RecurrenceType.WEEKLY, weekdays.sorted(), until)
}

internal fun ShiftFormState.toInput(role: Role, person: ShiftStaff): ShiftInput = ShiftInput(
    role = role,
    staffId = person.id,
    staffName = person.name,
    startDate = date,
    startTime = startTime,
    endTime = endTime,
    endsNextDay = endsNextDay,
    label = label.trim(),
    notes = notes.trim().ifEmpty { null },
    recurrence = toRecurrence()
)

internal fun ShiftFormState.toUpdate(person: ShiftStaff): ShiftUpdate = ShiftUpdate(
    staffId = person.id,
    staffName = person.name,
    startTime = startTime,
    endTime = endTime,
    endsNextDay = endsNextDay,
    label = label.trim(),
    notes = notes.trim().ifEmpty { null }
)

internal fun recurrenceLabel(type: RecurrenceType): String = when (type) {
    RecurrenceType.NONE -> "Does not repeat"
    RecurrenceType.DAILY -> "Repeats daily"
    RecurrenceType.WEEKLY -> "Repeats weekly on selected days"
}

internal fun shiftSheetTitle(role: Role, editing: Shift?): String =
    if (editing == null) "Schedule Shift · ${role.label}" else "Edit Shift — ${editing.label}"

// ---- Dates and labels ---------------------------------------------------------------------------

private val EN: Locale = Locale.ENGLISH
private val FULL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", EN)
private val SHORT_MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", EN)
private val SHORT_MONTH_DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", EN)
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", EN)
private val WEEKDAY_SHORT_MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", EN)

/** Day "Monday, March 2, 2026"; week "Mar 1 – Mar 7, 2026"; month "March 2026". */
internal fun rangeLabel(mode: ShiftViewMode, anchor: LocalDate): String = when (mode) {
    ShiftViewMode.DAY -> anchor.format(FULL_DAY)
    ShiftViewMode.WEEK -> {
        val (start, end) = ShiftLogic.viewRange(mode, anchor)
        "${start.format(SHORT_MONTH_DAY)} – ${end.format(SHORT_MONTH_DAY_YEAR)}"
    }
    ShiftViewMode.MONTH -> anchor.format(MONTH_YEAR)
}

/** "Monday, Mar 2"; the compact month grid shows just "Mar 2". */
internal fun dayTitle(date: LocalDate, compact: Boolean): String =
    if (compact) date.format(SHORT_MONTH_DAY) else date.format(WEEKDAY_SHORT_MONTH_DAY)

/** Every date of the view range, in order. */
internal fun daysInRange(mode: ShiftViewMode, anchor: LocalDate): List<LocalDate> {
    val (from, to) = ShiftLogic.viewRange(mode, anchor)
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}

/** "{start}–{end}". */
internal fun shiftTimeRange(shift: Shift): String = "${shift.startTime}–${shift.endTime}"

/** "{start}–{end}" plus "+1" when the shift ends the next day. */
internal fun shiftTimeRangeLabel(shift: Shift): String = shiftTimeRange(shift) + if (shift.endsNextDay) "+1" else ""

/** "{name} · {label}", or just the name in the compact month grid. */
internal fun shiftRowTitle(shift: Shift, compact: Boolean): String =
    if (compact) shift.staffName else "${shift.staffName} · ${shift.label}"

/** "{name} · {start}–{end}". */
internal fun onDutyChipText(shift: Shift): String = "${shift.staffName} · ${shiftTimeRange(shift)}"

// ---- Calendar data ------------------------------------------------------------------------------

/** Cancelled shifts are kept by the repository; the screen never shows them. */
internal fun visibleShifts(all: List<Shift>): List<Shift> = all.filter { it.status != ShiftStatus.CANCELLED.key }

private fun startMinutes(shift: Shift): Int = runCatching { ShiftLogic.toMinutes(shift.startTime) }.getOrDefault(Int.MAX_VALUE)

internal data class DayShifts(val date: LocalDate, val shifts: List<Shift>)

/** One entry per day of [days] with that day's shifts sorted by start time (then by name). Other dates are dropped. */
internal fun groupShiftsByDay(shifts: List<Shift>, days: List<LocalDate>): List<DayShifts> {
    val byDate = shifts.groupBy { it.date }
    return days.map { day ->
        DayShifts(
            date = day,
            shifts = byDate[day.toDateKey()].orEmpty()
                .sortedWith(compareBy<Shift>({ startMinutes(it) }, { it.staffName.lowercase() }))
        )
    }
}

internal data class ShiftStats(val onRoster: Int, val inView: Int, val onDutyNow: Int)

/** Everything the calendar screen draws for one role and one date range. */
internal data class ShiftBoard(
    val role: Role,
    val mode: ShiftViewMode,
    val anchor: LocalDate,
    val today: LocalDate,
    val days: List<DayShifts>,
    val staff: List<ShiftStaff>,
    val onDuty: List<Shift>,
    val stats: ShiftStats
)

/**
 * [shifts] may include cancelled ones and yesterday's overnight shifts (the screen asks for one extra day before the
 * range). "In view" counts only shifts that start inside the range; "on duty" looks at all of them.
 */
internal fun buildShiftBoard(
    role: Role,
    mode: ShiftViewMode,
    anchor: LocalDate,
    shifts: List<Shift>,
    staff: List<ShiftStaff>,
    now: LocalDateTime
): ShiftBoard {
    val live = visibleShifts(shifts)
    val days = groupShiftsByDay(live, daysInRange(mode, anchor))
    val onDuty = live
        .filter { runCatching { ShiftLogic.isOnDutyNow(it, now) }.getOrDefault(false) }
        .sortedWith(compareBy<Shift>({ startMinutes(it) }, { it.staffName.lowercase() }))
    return ShiftBoard(
        role = role,
        mode = mode,
        anchor = anchor,
        today = now.toLocalDate(),
        days = days,
        staff = staff,
        onDuty = onDuty,
        stats = ShiftStats(onRoster = staff.size, inView = days.sumOf { it.shifts.size }, onDutyNow = onDuty.size)
    )
}

// ---- Conflict box -------------------------------------------------------------------------------

/** What the red box shows after a save was refused because of a clash. */
internal data class ConflictBox(
    val heading: String,
    val lines: List<String>,
    val moreLine: String?,
    val footer: String
)

internal fun conflictBox(staffName: String, conflicts: List<ConflictInfo>): ConflictBox = ConflictBox(
    heading = "Scheduling conflict — $staffName is already booked",
    lines = conflicts.take(MAX_CONFLICT_LINES).map { "${it.date}: overlaps \"${it.withLabel}\" (${it.withTime})" },
    moreLine = (conflicts.size - MAX_CONFLICT_LINES).takeIf { it > 0 }?.let { "+ $it more conflicting date(s)" },
    footer = "Pick a different staff member or time and try again."
)

// ---- Toasts -------------------------------------------------------------------------------------

internal data class ShiftToastText(val title: String?, val message: String)

/** "Shift Scheduled", with "{n} shifts created" as the second line when more than one was made. */
internal fun scheduledToast(count: Int): ShiftToastText =
    if (count > 1) ShiftToastText("Shift Scheduled", "$count shifts created") else ShiftToastText(null, "Shift Scheduled")

internal val UPDATED_TOAST = ShiftToastText(null, "Shift Updated")
internal val CANCELLED_TOAST = ShiftToastText(null, "Shift Cancelled")
internal val SERIES_CANCELLED_TOAST =
    ShiftToastText("Series Cancelled", "This and every future shift in the series was cancelled.")

/** A validation problem as a toast: the title line, plus the description line when it has one. */
internal fun ShiftFormIssue.toToast(): ShiftToastText =
    if (description != null) ShiftToastText(title, description) else ShiftToastText(null, title)
