package com.westly.nbms.features.reports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class AnnualReportCsvTest {

    private fun month(m: Int, revenue: Double = 0.0, expenses: Double = 0.0, bookings: Int = 0) =
        AnnualMonth(YearMonth.of(2026, m), revenue, expenses, revenue - expenses, bookings)

    private fun report(vararg months: AnnualMonth) =
        AnnualReport(2026, months.toList(), 0.0, 0.0, 0.0, 0)

    @Test fun header_hasFiveQuotedColumns() {
        val lines = buildAnnualReportCsv(report()).lines()
        assertEquals(listOf("\"Month\",\"Revenue\",\"Expenses\",\"Profit\",\"Bookings\""), lines)
    }

    @Test fun oneRowPerMonth_afterTheHeader() {
        val csv = buildAnnualReportCsv(report(month(1, 1000.0, 400.0, 3), month(2, 0.0, 250.5, 0)))
        assertEquals(
            listOf(
                "\"Month\",\"Revenue\",\"Expenses\",\"Profit\",\"Bookings\"",
                "\"Jan\",\"1000\",\"400\",\"600\",\"3\"",
                "\"Feb\",\"0\",\"250.5\",\"-250.5\",\"0\""
            ),
            csv.lines()
        )
    }

    @Test fun amounts_arePlainNumbers() {
        assertEquals("25000", annualCsvAmount(25000.0))
        assertEquals("2500.5", annualCsvAmount(2500.5))
        assertEquals("0.3", annualCsvAmount(0.1 + 0.2))
        assertEquals("-12.35", annualCsvAmount(-12.345))
        assertEquals("0", annualCsvAmount(0.0))
        assertEquals("0", annualCsvAmount(Double.NaN))
    }

    @Test fun cells_doubleTheirQuotes() {
        assertEquals("\"say \"\"hi\"\", ok\"", annualCsvCell("say \"hi\", ok"))
        assertEquals("\"line1\nline2\"", annualCsvCell("line1\nline2"))
    }

    @Test fun fileName_usesTheYear() {
        assertEquals("annual-report-2026.csv", annualReportCsvFileName(2026))
    }
}
