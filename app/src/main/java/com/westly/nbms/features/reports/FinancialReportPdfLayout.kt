package com.westly.nbms.features.reports

import com.westly.nbms.core.util.Format
import com.westly.nbms.features.finance.RevenueCategory

// Pure layout of the Financial Report PDF: plain texts and lines at fixed positions on A4 portrait pages (points).
// No android.* classes here, so it is unit-tested on the JVM. FinancialReportPdf.kt only draws what this produces.

internal enum class PdfAlign { LEFT, RIGHT }

internal sealed interface PdfElement

/** [y] is the text baseline. [color] is ARGB. */
internal data class PdfText(
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float,
    val bold: Boolean,
    val color: Int,
    val align: PdfAlign = PdfAlign.LEFT
) : PdfElement

internal data class PdfLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float,
    val color: Int
) : PdfElement

internal data class PdfPage(val elements: List<PdfElement>)

internal data class PdfLayout(val width: Int, val height: Int, val pages: List<PdfPage>) {
    /** Every text of every page, in drawing order. */
    val texts: List<String> get() = pages.flatMap { page -> page.elements.filterIsInstance<PdfText>().map { it.text } }
}

internal const val FINANCIAL_REPORT_FOOTER = "Only approved transactions are counted as revenue."

internal object FinancialReportPdfLayout {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    /** Rows are never placed below this line (the footer lives under it). */
    const val DEFAULT_CONTENT_BOTTOM = 780f

    private const val MARGIN = 40f
    private const val RIGHT = PAGE_WIDTH - MARGIN
    private const val FOOTER_Y = 815f

    private val NAVY = 0xFF0B1F3A.toInt()
    private val GOLD = 0xFFC9A24B.toInt()
    private val INK = 0xFF1B212D.toInt()
    private val MUTED = 0xFF68748D.toInt()
    private val GREEN = 0xFF16A34A.toInt()
    private val RED = 0xFFDC2828.toInt()
    private val PURPLE = 0xFF673AB6.toInt()
    private val HAIRLINE = 0xFFDCDFE5.toInt()

    private const val ROW_HEIGHT = 20f

    fun build(
        report: FinancialReport,
        generatedAt: String,
        generatedBy: String,
        contentBottom: Float = DEFAULT_CONTENT_BOTTOM
    ): PdfLayout = Builder(report, contentBottom).run {
        drawHeader(generatedAt, generatedBy)
        drawSummary()
        drawIncomeStatement()
        drawRevenueBreakdown()
        drawExpenseBreakdown()
        finish()
    }

    /** Rough text width (points) used only to shorten very long names; the renderer draws what it is given. */
    private fun approxWidth(text: String, size: Float, bold: Boolean): Float =
        text.length * size * (if (bold) 0.58f else 0.52f)

    private fun fit(text: String, size: Float, bold: Boolean, maxWidth: Float): String {
        if (approxWidth(text, size, bold) <= maxWidth) return text
        val perChar = size * (if (bold) 0.58f else 0.52f)
        val keep = ((maxWidth / perChar).toInt() - 1).coerceAtLeast(1)
        return text.take(keep).trimEnd() + "…"
    }

    private class Builder(private val report: FinancialReport, private val contentBottom: Float) {
        private val symbol = report.currencySymbol
        private val monthLabel = financialMonthLabel(report.month)
        private val pages = mutableListOf<MutableList<PdfElement>>()
        private var current = mutableListOf<PdfElement>()
        private var y = 0f

        init {
            startPage()
        }

        private fun money(amount: Double) = Format.currency(amount, symbol)

        private fun startPage() {
            current = mutableListOf()
            pages += current
            y = 0f
        }

        private fun text(x: Float, y: Float, value: String, size: Float, bold: Boolean, color: Int, align: PdfAlign = PdfAlign.LEFT) {
            current += PdfText(x, y, value, size, bold, color, align)
        }

        private fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
            current += PdfLine(x1, y1, x2, y2, width, color)
        }

        /** Starts a new page when [height] more points would pass the content bottom. */
        private fun ensure(height: Float) {
            if (y + height <= contentBottom) return
            startPage()
            text(MARGIN, 52f, fit("Financial Report — $monthLabel (continued)", 10f, false, RIGHT - MARGIN), 10f, false, MUTED)
            line(MARGIN, 60f, RIGHT, 60f, 0.8f, HAIRLINE)
            y = 80f
        }

        fun drawHeader(generatedAt: String, generatedBy: String) {
            val name = report.businessName.trim().ifEmpty { "NBMS" }
            text(MARGIN, 62f, fit(name, 20f, true, RIGHT - MARGIN), 20f, true, NAVY)
            line(MARGIN, 72f, RIGHT, 72f, 2.5f, GOLD)
            text(MARGIN, 98f, "Financial Report — $monthLabel", 16f, true, INK)
            val by = generatedBy.trim().ifEmpty { "—" }
            text(MARGIN, 114f, fit("Generated $generatedAt by $by", 9f, false, RIGHT - MARGIN), 9f, false, MUTED)
            y = 140f
        }

        fun drawSummary() {
            ensure(100f)
            val netColor = if (report.netProfit < 0.0) RED else NAVY
            val boxes = listOf(
                Triple("Total Revenue", money(report.totalRevenue), GREEN),
                Triple("Total Expenses", money(report.totalExpenses), RED),
                Triple("Net Profit", money(report.netProfit), netColor),
                Triple("Profit Margin", "${report.margin}%", PURPLE)
            )
            val columnX = listOf(MARGIN, MARGIN + (RIGHT - MARGIN) / 2f)
            boxes.chunked(2).forEach { pair ->
                pair.forEachIndexed { i, (label, value, color) ->
                    text(columnX[i], y, label, 9f, false, MUTED)
                    text(columnX[i], y + 19f, fit(value, 15f, true, (RIGHT - MARGIN) / 2f - 10f), 15f, true, color)
                }
                y += 46f
            }
            y += 6f
        }

        private fun section(title: String, rowsToKeepWithTitle: Int) {
            ensure(34f + ROW_HEIGHT * rowsToKeepWithTitle)
            text(MARGIN, y + 12f, title, 12f, true, NAVY)
            line(MARGIN, y + 18f, RIGHT, y + 18f, 1f, NAVY)
            y += 28f
        }

        private fun row(label: String, value: String, bold: Boolean = false, color: Int = INK, size: Float = 10.5f, height: Float = ROW_HEIGHT) {
            ensure(height)
            val base = y + height - 6f
            text(MARGIN, base, label, size, bold, INK)
            text(RIGHT, base, value, size, bold, color, PdfAlign.RIGHT)
            line(MARGIN, y + height, RIGHT, y + height, 0.5f, HAIRLINE)
            y += height
        }

        fun drawIncomeStatement() {
            section("Income Statement Summary", 3)
            FINANCIAL_STATEMENT_ORDER.forEach { category ->
                val amount = report.revenueOf(category)
                if (category == RevenueCategory.OTHER && amount <= 0.0) return@forEach
                row(category.label, money(amount))
            }
            ensure(8f)
            line(MARGIN, y + 2f, RIGHT, y + 2f, 1.8f, NAVY)
            y += 6f
            row("Total Revenue", money(report.totalRevenue), bold = true, color = GREEN)
            row("Total Expenses", "(${money(report.totalExpenses)})", color = RED)
            row(
                "Net Profit",
                money(report.netProfit),
                bold = true,
                color = if (report.netProfit < 0.0) RED else NAVY,
                size = 13f,
                height = 28f
            )
            y += 10f
        }

        private val percentX = RIGHT
        private val amountX = RIGHT - 80f

        private fun tableHeader(first: String) {
            ensure(ROW_HEIGHT)
            val base = y + ROW_HEIGHT - 6f
            text(MARGIN, base, first, 9f, true, MUTED)
            text(amountX, base, "Amount", 9f, true, MUTED, PdfAlign.RIGHT)
            text(percentX, base, "% of total", 9f, true, MUTED, PdfAlign.RIGHT)
            line(MARGIN, y + ROW_HEIGHT, RIGHT, y + ROW_HEIGHT, 0.8f, HAIRLINE)
            y += ROW_HEIGHT
        }

        private fun tableRow(name: String, amount: Double, total: Double) {
            ensure(ROW_HEIGHT)
            val base = y + ROW_HEIGHT - 6f
            text(MARGIN, base, name, 10.5f, false, INK)
            text(amountX, base, money(amount), 10.5f, false, INK, PdfAlign.RIGHT)
            text(percentX, base, "${financialPercent(amount, total)}%", 10.5f, false, MUTED, PdfAlign.RIGHT)
            line(MARGIN, y + ROW_HEIGHT, RIGHT, y + ROW_HEIGHT, 0.5f, HAIRLINE)
            y += ROW_HEIGHT
        }

        private fun emptyRow(message: String) {
            ensure(ROW_HEIGHT)
            text(MARGIN, y + ROW_HEIGHT - 6f, message, 10f, false, MUTED)
            y += ROW_HEIGHT
        }

        fun drawRevenueBreakdown() {
            section("Revenue Breakdown", 2)
            tableHeader("Category")
            val rows = report.revenueBreakdown()
            if (rows.isEmpty()) {
                emptyRow("No approved revenue this month.")
            } else {
                rows.forEach { (_, name, amount) -> tableRow(name, amount, report.totalRevenue) }
            }
            y += 10f
        }

        fun drawExpenseBreakdown() {
            section("Expense Breakdown", 2)
            tableHeader("Category")
            if (report.expensesByCategory.isEmpty()) {
                emptyRow("No expenses recorded this month.")
            } else {
                report.expensesByCategory.forEach { (category, amount) -> tableRow(category.label, amount, report.totalExpenses) }
            }
        }

        fun finish(): PdfLayout {
            val count = pages.size
            pages.forEachIndexed { index, elements ->
                elements += PdfLine(MARGIN, FOOTER_Y - 14f, RIGHT, FOOTER_Y - 14f, 0.8f, GOLD)
                elements += PdfText(MARGIN, FOOTER_Y, FINANCIAL_REPORT_FOOTER, 9f, false, MUTED)
                elements += PdfText(RIGHT, FOOTER_Y, "Page ${index + 1} of $count", 9f, false, MUTED, PdfAlign.RIGHT)
            }
            return PdfLayout(PAGE_WIDTH, PAGE_HEIGHT, pages.map { PdfPage(it.toList()) })
        }
    }
}
