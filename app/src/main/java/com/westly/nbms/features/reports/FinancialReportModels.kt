package com.westly.nbms.features.reports

import com.westly.nbms.features.finance.RevenueCategory
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** One month of the business's money, worked out by [FinancialReportCalculator]. Money is in naira (major unit). */
internal data class FinancialReport(
    val month: YearMonth,
    val businessName: String,
    val currencySymbol: String,
    /** Approved revenue of the month per category; every category is present (0.0 when empty). */
    val revenueByCategory: Map<RevenueCategory, Double>,
    /** Expenses of the month per category, biggest first, zero totals left out. */
    val expensesByCategory: List<Pair<ExpenseCategory, Double>>,
    val totalRevenue: Double,
    val totalExpenses: Double,
    val netProfit: Double,
    /** Whole percent; 0 when there is no revenue. */
    val margin: Int,
    /** How many approved transactions fall in the month. */
    val approvedCount: Int
) {
    /** The amount of one revenue category (0.0 when none). */
    fun revenueOf(category: RevenueCategory): Double = revenueByCategory[category] ?: 0.0
}

/** The order of the Income Statement rows (the "Other Income" row is shown only when it is above zero). */
internal val FINANCIAL_STATEMENT_ORDER: List<RevenueCategory> = listOf(
    RevenueCategory.ROOM,
    RevenueCategory.SALES,
    RevenueCategory.RESTAURANT,
    RevenueCategory.BAR,
    RevenueCategory.LAUNDRY,
    RevenueCategory.OTHER
)

/** The order and the short names of the revenue breakdown (donut, legend and PDF table). */
internal val FINANCIAL_BREAKDOWN_ORDER: List<Pair<RevenueCategory, String>> = listOf(
    RevenueCategory.ROOM to "Room Revenue",
    RevenueCategory.SALES to "Sales",
    RevenueCategory.RESTAURANT to "Restaurant",
    RevenueCategory.BAR to "Bar",
    RevenueCategory.LAUNDRY to "Laundry",
    RevenueCategory.OTHER to "Other Income"
)

/** The breakdown rows above zero, in breakdown order: short name and amount. */
internal fun FinancialReport.revenueBreakdown(): List<Triple<RevenueCategory, String, Double>> =
    FINANCIAL_BREAKDOWN_ORDER
        .map { (category, name) -> Triple(category, name, revenueOf(category)) }
        .filter { it.third > 0.0 }

/** "October 2026". */
internal fun financialMonthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"

/** "financial-report-2026-10.pdf". */
internal fun financialReportFileName(month: YearMonth): String = "financial-report-$month.pdf"

/** Share of [part] in [total] as a whole percent; 0 when [total] is not above zero. */
internal fun financialPercent(part: Double, total: Double): Int =
    if (total <= 0.0) 0 else Math.round(part / total * 100.0).toInt()

/** "October 2026 · 3 approved transactions" (singular for exactly one). */
internal fun financialSubtitle(month: YearMonth, approvedCount: Int): String {
    val noun = if (approvedCount == 1) "transaction" else "transactions"
    return "${financialMonthLabel(month)} · $approvedCount approved $noun"
}
