package com.westly.nbms.features.reports

import com.westly.nbms.features.finance.RevenueCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class FinancialReportPdfLayoutTest {

    private fun sampleReport(
        revenue: Map<RevenueCategory, Double> = mapOf(
            RevenueCategory.ROOM to 550000.0,
            RevenueCategory.SALES to 150000.0,
            RevenueCategory.RESTAURANT to 100000.0,
            RevenueCategory.BAR to 50000.0,
            RevenueCategory.LAUNDRY to 40000.0,
            RevenueCategory.OTHER to 110000.0
        ),
        expenses: List<Pair<ExpenseCategory, Double>> = listOf(
            ExpenseCategory.PAYROLL to 300000.0,
            ExpenseCategory.UTILITIES to 100000.0
        ),
        businessName: String = "Westly Hotel"
    ): FinancialReport {
        val all = RevenueCategory.entries.associateWith { revenue[it] ?: 0.0 }
        val totalRevenue = all.values.sum()
        val totalExpenses = expenses.sumOf { it.second }
        val net = totalRevenue - totalExpenses
        return FinancialReport(
            month = YearMonth.of(2026, 10), businessName = businessName, currencySymbol = "₦",
            revenueByCategory = all, expensesByCategory = expenses,
            totalRevenue = totalRevenue, totalExpenses = totalExpenses, netProfit = net,
            margin = if (totalRevenue == 0.0) 0 else Math.round(net / totalRevenue * 100.0).toInt(),
            approvedCount = 12
        )
    }

    private fun layout(report: FinancialReport = sampleReport(), bottom: Float = FinancialReportPdfLayout.DEFAULT_CONTENT_BOTTOM) =
        FinancialReportPdfLayout.build(report, "9 Oct 2026, 14:30", "Ada Obi", bottom)

    private fun PdfLayout.has(text: String) = texts.contains(text)

    @Test fun isA4Portrait_withContent() {
        val l = layout()
        assertEquals(595, l.width)
        assertEquals(842, l.height)
        assertTrue(l.pages.isNotEmpty())
        assertTrue(l.pages.all { it.elements.isNotEmpty() })
    }

    @Test fun firstPage_hasBusinessName_goldRule_titleAndGeneratedLine() {
        val l = layout()
        val name = l.pages[0].elements.filterIsInstance<PdfText>().first { it.text == "Westly Hotel" }
        assertTrue(name.bold)
        assertEquals(0xFF0B1F3A.toInt(), name.color)
        assertTrue(l.pages[0].elements.filterIsInstance<PdfLine>().any { it.color == 0xFFC9A24B.toInt() })
        assertTrue(l.has("Financial Report — October 2026"))
        assertTrue(l.has("Generated 9 Oct 2026, 14:30 by Ada Obi"))
    }

    @Test fun hasTheFourSummaryFigures() {
        val l = layout()
        // revenue 1,000,000 ; expenses 400,000 ; net 600,000 ; margin 60%
        listOf("Total Revenue", "Total Expenses", "Net Profit", "Profit Margin").forEach { assertTrue(it, l.has(it)) }
        assertTrue(l.has("₦1,000,000"))
        assertTrue(l.has("₦400,000"))
        assertTrue(l.has("₦600,000"))
        assertTrue(l.has("60%"))
    }

    @Test fun incomeStatement_hasEveryRow_andExpensesInBrackets() {
        val l = layout()
        assertTrue(l.has("Income Statement Summary"))
        listOf("Room Revenue", "Sales Revenue", "Restaurant Revenue", "Bar Revenue", "Laundry Revenue", "Other Income")
            .forEach { assertTrue(it, l.has(it)) }
        assertTrue(l.has("(₦400,000)"))
        assertTrue(l.has("₦600,000"))
    }

    @Test fun incomeStatement_leavesOutOtherIncomeWhenZero() {
        val l = layout(sampleReport(revenue = mapOf(RevenueCategory.ROOM to 1000.0)))
        assertFalse(l.has("Other Income"))
    }

    @Test fun revenueBreakdownTable_hasNameAmountAndPercent() {
        val l = layout()
        assertTrue(l.has("Revenue Breakdown"))
        assertTrue(l.has("Amount"))
        assertTrue(l.has("% of total"))
        listOf("Room Revenue", "Sales", "Restaurant", "Bar", "Laundry", "Other Income").forEach { assertTrue(it, l.has(it)) }
        assertTrue(l.has("₦550,000"))
        // 550k, 150k, 100k, 50k, 40k, 110k of 1,000k
        listOf("55%", "15%", "10%", "5%", "4%", "11%").forEach { assertTrue(it, l.has(it)) }
    }

    @Test fun expenseBreakdownTable_hasCategoriesAndPercent() {
        val l = layout()
        assertTrue(l.has("Expense Breakdown"))
        assertTrue(l.has("Payroll"))
        assertTrue(l.has("Utilities"))
        assertTrue(l.has("₦300,000"))
        assertTrue(l.has("75%"))
        assertTrue(l.has("25%"))
    }

    @Test fun emptyBreakdowns_showAShortMessage() {
        val l = layout(sampleReport(revenue = emptyMap(), expenses = emptyList()))
        assertTrue(l.has("No approved revenue this month."))
        assertTrue(l.has("No expenses recorded this month."))
        assertTrue(l.has("0%"))
    }

    @Test fun negativeNetProfit_isRed() {
        val l = layout(sampleReport(revenue = mapOf(RevenueCategory.ROOM to 1000.0), expenses = listOf(ExpenseCategory.PAYROLL to 3000.0)))
        val net = l.pages.flatMap { it.elements }.filterIsInstance<PdfText>().filter { it.text == "-₦2,000" }
        assertTrue(net.isNotEmpty())
        assertTrue(net.all { it.color == 0xFFDC2828.toInt() })
    }

    @Test fun footerSentence_isOnEveryPage_withPageNumbers() {
        val l = layout(bottom = 300f)
        assertTrue(l.pages.size >= 2)
        l.pages.forEachIndexed { index, page ->
            val texts = page.elements.filterIsInstance<PdfText>().map { it.text }
            assertTrue(texts.contains("Only approved transactions are counted as revenue."))
            assertTrue(texts.contains("Page ${index + 1} of ${l.pages.size}"))
            assertTrue(texts.contains("\u00A9 Neribo Group"))
        }
    }

    @Test fun normalReport_fitsOnOnePage() {
        assertEquals(1, layout().pages.size)
    }

    @Test fun rowsThatOverflow_moveToASecondPage_andNothingIsLost() {
        val single = layout()
        val split = layout(bottom = 400f)
        assertTrue(split.pages.size >= 2)
        assertTrue(split.pages[1].elements.filterIsInstance<PdfText>().any { it.text.endsWith("(continued)") })
        // Every text of the one-page version is still somewhere in the split version.
        val splitTexts = split.texts
        single.texts.filter { !it.startsWith("Page ") }.forEach { assertTrue(it, splitTexts.contains(it)) }
    }

    @Test fun nothingIsDrawnBelowTheContentBottom_exceptTheFooter() {
        val bottom = 400f
        val l = layout(bottom = bottom)
        l.pages.forEach { page ->
            page.elements.filterIsInstance<PdfText>()
                .filter { it.text != "Only approved transactions are counted as revenue." && !it.text.startsWith("Page ") && it.text != "\u00A9 Neribo Group" }
                .forEach { assertTrue("${it.text} at ${it.y}", it.y <= bottom) }
        }
    }

    @Test fun veryLongBusinessName_isShortenedToFit() {
        val l = layout(sampleReport(businessName = "A".repeat(200)))
        val name = l.pages[0].elements.filterIsInstance<PdfText>().first { it.size == 20f }
        assertTrue(name.text.length < 200)
        assertTrue(name.text.endsWith("…"))
    }

    @Test fun blankBusinessName_fallsBackToAppName() {
        assertTrue(layout(sampleReport(businessName = "  ")).has("NeriboBMS"))
    }
}
