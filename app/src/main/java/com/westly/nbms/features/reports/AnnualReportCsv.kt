package com.westly.nbms.features.reports

import java.math.BigDecimal
import java.math.RoundingMode

/** The first line of the annual export. Every cell, the header too, is double-quoted. */
internal const val ANNUAL_CSV_HEADER = "\"Month\",\"Revenue\",\"Expenses\",\"Profit\",\"Bookings\""

/** Wraps a value in double quotes and doubles every quote inside it, so commas, quotes and line breaks stay in one cell. */
internal fun annualCsvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** "25000" for 25000.0 and "2500.5" for 2500.5: plain digits (at most 2 decimals), no symbol, no thousands separators. */
internal fun annualCsvAmount(amount: Double): String =
    if (amount.isNaN() || amount.isInfinite()) "0"
    else BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** One line: Month, Revenue, Expenses, Profit, Bookings. */
internal fun annualCsvRow(month: AnnualMonth): String =
    listOf(
        month.label,
        annualCsvAmount(month.revenue),
        annualCsvAmount(month.expenses),
        annualCsvAmount(month.profit),
        month.bookings.toString()
    ).joinToString(",") { annualCsvCell(it) }

/** The whole file: the header, then one line per month (January up to the current month). */
internal fun buildAnnualReportCsv(report: AnnualReport): String =
    (listOf(ANNUAL_CSV_HEADER) + report.months.map { annualCsvRow(it) }).joinToString("\n")

/** "annual-report-2026.csv". */
internal fun annualReportCsvFileName(year: Int): String = "annual-report-$year.csv"
