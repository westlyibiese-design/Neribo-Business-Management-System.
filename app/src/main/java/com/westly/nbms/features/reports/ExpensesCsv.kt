package com.westly.nbms.features.reports

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** The first line of every expenses export. Every cell, the header too, is double-quoted. */
internal const val EXPENSES_CSV_HEADER =
    "\"Date\",\"Title\",\"Category\",\"Amount\",\"Payment Method\",\"Recorded By\""

/** Wraps a value in double quotes and doubles every quote inside it, so commas, quotes and line breaks stay in one cell. */
internal fun expensesCsvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** "25000" for 25000.0 and "2500.5" for 2500.5: plain digits, no symbol, no thousands separators, so a spreadsheet can add them up. */
internal fun expensesCsvAmount(amount: Double): String =
    if (amount.isNaN() || amount.isInfinite()) "0" else BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

/** The moment of the expense `date`, or null when it has none. */
internal fun expensesInstant(expense: Expense): Instant? =
    expense.date?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

/** "2026-10-09" in the business time zone, or an empty cell when the expense has no date. */
internal fun expensesCsvDate(expense: Expense, zone: ZoneId): String =
    expensesInstant(expense)?.atZone(zone)?.toLocalDate()?.toString().orEmpty()

/** One line: Date, Title, Category, Amount, Payment Method, Recorded By. Category and method are the stored (raw) values. */
internal fun expensesCsvRow(expense: Expense, dateText: String): String =
    listOf(
        dateText,
        expense.title,
        expense.category,
        expensesCsvAmount(expense.amount),
        expense.paymentMethod,
        expense.recordedByName
    ).joinToString(",") { expensesCsvCell(it) }

/** The whole file: the header, then one line per row. Callers pass the FILTERED list. */
internal fun buildExpensesCsv(rows: List<Expense>, zone: ZoneId): String =
    (listOf(EXPENSES_CSV_HEADER) + rows.map { expensesCsvRow(it, expensesCsvDate(it, zone)) }).joinToString("\n")

/** "expenses-2026-10.csv" for a month written as yyyy-MM, or "expenses-all.csv" when no month is chosen. */
internal fun expensesCsvFileName(month: String): String {
    val clean = month.trim()
    return if (clean.isEmpty()) "expenses-all.csv" else "expenses-$clean.csv"
}
