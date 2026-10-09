package com.westly.nbms.features.reports

import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Pure maths of the Financial Report. No Android, no Firestore. Revenue counts APPROVED transactions only. */
internal object FinancialReportCalculator {

    /** First and last millisecond of [month] in the business time zone (the same bounds the Revenue page uses). */
    fun monthBounds(month: YearMonth, zone: ZoneId): Pair<Instant, Instant> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusMillis(1)
        return start to end
    }

    fun build(
        transactions: List<RevenueTransaction>,
        expenses: List<Expense>,
        month: YearMonth,
        zone: ZoneId,
        businessName: String,
        currencySymbol: String
    ): FinancialReport {
        val (start, end) = monthBounds(month, zone)
        val approved = RevenueMath.inRange(RevenueMath.approvedOnly(transactions), start, end)
        val revenueByCategory = RevenueMath.sumByCategory(approved)
        val totalRevenue = RevenueMath.sum(approved)

        val monthExpenses = ExpenseRules.inMonth(expenses.filter { !it.isDeleted }, month, zone)
        val totalExpenses = ExpenseRules.total(monthExpenses)
        val expensesByCategory = ExpenseRules.byCategory(monthExpenses)

        val netProfit = totalRevenue - totalExpenses
        val margin = if (totalRevenue == 0.0) 0 else Math.round(netProfit / totalRevenue * 100.0).toInt()

        return FinancialReport(
            month = month,
            businessName = businessName,
            currencySymbol = currencySymbol,
            revenueByCategory = revenueByCategory,
            expensesByCategory = expensesByCategory,
            totalRevenue = totalRevenue,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            margin = margin,
            approvedCount = approved.size
        )
    }
}
