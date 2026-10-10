package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many finished tasks the "Recently Finished" list shows. */
internal const val MY_TASKS_RECENT_LIMIT = 20

/** How many upcoming shifts the "My Upcoming Shifts" card shows. */
internal const val MY_SHIFTS_LIMIT = 8

/**
 * Read-only shape of a `shifts/{id}` document, as My Tasks needs it. The Shifts phase owns the real model;
 * this one only reads, and every field has a default so partial documents still read.
 */
internal data class MyShiftDoc(
    @DocumentId val id: String = "",
    val staffId: String = "",
    /** "yyyy-MM-dd". */
    val date: String = "",
    /** "HH:mm". */
    val startTime: String = "",
    val endTime: String = "",
    val label: String = "",
    val endsNextDay: Boolean = false,
    val status: String = ""
)

/** The three buttons a task card can show. */
internal enum class MyTaskAction { ACCEPT, START, COMPLETE }

/** One row of the shifts card, already turned into text. */
internal data class MyShiftRow(val id: String, val dayLabel: String, val label: String, val timeRange: String)

/** Pure rules behind the My Tasks page. */
internal object MyTasksRules {

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

    private fun Timestamp.asInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

    /** Tasks still to do (not completed, not cancelled, not deleted), oldest first. No `createdAt` yet means brand new, so last. */
    fun activeTasks(tasks: List<StaffTask>): List<StaffTask> =
        tasks.filter { !it.isDeleted && TaskRules.isActive(it) }
            .sortedBy { it.createdAt?.asInstant() ?: Instant.MAX }

    /** The 20 most recent completed or cancelled tasks, newest of `completedAt ?: createdAt` first. */
    fun recentlyFinished(tasks: List<StaffTask>): List<StaffTask> =
        tasks.filter { !it.isDeleted && !TaskRules.isActive(it) }
            .sortedByDescending { (it.completedAt ?: it.createdAt)?.asInstant() ?: Instant.MIN }
            .take(MY_TASKS_RECENT_LIMIT)

    /** Accept when pending; Start when accepted; Mark Complete when accepted or in progress. */
    fun actionsFor(status: TaskStatus): List<MyTaskAction> = when (status) {
        TaskStatus.PENDING -> listOf(MyTaskAction.ACCEPT)
        TaskStatus.ACCEPTED -> listOf(MyTaskAction.START, MyTaskAction.COMPLETE)
        TaskStatus.IN_PROGRESS -> listOf(MyTaskAction.COMPLETE)
        TaskStatus.COMPLETED, TaskStatus.CANCELLED -> emptyList()
    }

    /** The status a task must have before [action] may run (used to ignore stale taps). */
    fun isAllowed(task: StaffTask, action: MyTaskAction): Boolean = action in actionsFor(task.taskStatus())

    /** "{n} active task(s) assigned to you", singular when n is 1. */
    fun subtitle(activeCount: Int): String =
        "$activeCount active ${if (activeCount == 1) "task" else "tasks"} assigned to you"

    /**
     * My shifts from [today] on: `staffId == uid`, `date >= today` ("yyyy-MM-dd" compares correctly as text),
     * not cancelled, sorted by date then start time, first [MY_SHIFTS_LIMIT].
     */
    fun upcomingShifts(shifts: List<MyShiftDoc>, uid: String, today: LocalDate): List<MyShiftDoc> {
        val todayText = today.toString()
        return shifts
            .filter { it.staffId == uid && it.date >= todayText && it.status != "cancelled" }
            .sortedWith(compareBy<MyShiftDoc> { it.date }.thenBy { it.startTime })
            .take(MY_SHIFTS_LIMIT)
    }

    /** "Today" for [today], otherwise "Mon, Mar 5". A date that cannot be read is shown as it was stored. */
    fun dayLabel(date: String, today: LocalDate): String {
        val parsed = try {
            LocalDate.parse(date)
        } catch (e: Exception) {
            return date
        }
        return if (parsed == today) "Today" else parsed.format(dayFormatter)
    }

    /** "08:00–16:00", plus " (+1 day)" when the shift ends the next day. */
    fun timeRange(shift: MyShiftDoc): String =
        "${shift.startTime}–${shift.endTime}" + if (shift.endsNextDay) " (+1 day)" else ""

    fun shiftRows(shifts: List<MyShiftDoc>, uid: String, today: LocalDate): List<MyShiftRow> =
        upcomingShifts(shifts, uid, today).map {
            MyShiftRow(it.id, dayLabel(it.date, today), it.label, timeRange(it))
        }
}
