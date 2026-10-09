package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Pure expense maths. No Android, no Firestore calls. */
object ExpenseRules {

    /** Expenses whose `date` falls inside [month] in the business time zone [zone]. A null date is left out. */
    fun inMonth(expenses: List<Expense>, month: YearMonth, zone: ZoneId): List<Expense> =
        expenses.filter { expense ->
            val instant = expense.date?.toInstantOrNull() ?: return@filter false
            YearMonth.from(instant.atZone(zone)) == month
        }

    fun total(expenses: List<Expense>): Double = expenses.sumOf { it.amount }

    /** Totals per [ExpenseCategory] (unknown keys count as OTHER), biggest first, zero totals left out. */
    fun byCategory(expenses: List<Expense>): List<Pair<ExpenseCategory, Double>> =
        expenses
            .groupBy { ExpenseCategory.fromKey(it.category) }
            .map { (category, items) -> category to items.sumOf { it.amount } }
            .filter { it.second != 0.0 }
            .sortedWith(compareByDescending<Pair<ExpenseCategory, Double>> { it.second }.thenBy { it.first.ordinal })
}

/** An expense of this amount or more is flagged as large. */
internal const val LARGE_EXPENSE_THRESHOLD: Double = 1000.0

internal fun ExpenseRules.isLarge(amount: Double): Boolean = amount >= LARGE_EXPENSE_THRESHOLD

/**
 * The list the Expenses page shows: deleted rows removed, optional month (null = every month, judged in [zone]),
 * optional search (trimmed, case-insensitive, matches the title or the raw category key),
 * newest `date` first with null dates last.
 */
internal fun ExpenseRules.filtered(
    expenses: List<Expense>,
    month: YearMonth?,
    query: String,
    zone: ZoneId
): List<Expense> {
    val live = expenses.filter { !it.isDeleted }
    val inRange = if (month == null) live else inMonth(live, month, zone)
    val needle = query.trim()
    val matched = if (needle.isEmpty()) inRange else inRange.filter {
        it.title.contains(needle, ignoreCase = true) || it.category.contains(needle, ignoreCase = true)
    }
    return matched.sortedWith(compareBy(nullsLast(reverseOrder<Timestamp>())) { expense: Expense -> expense.date })
}

private fun Timestamp.toInstantOrNull(): Instant? =
    runCatching { Instant.ofEpochSecond(seconds, nanoseconds.toLong()) }.getOrNull()
