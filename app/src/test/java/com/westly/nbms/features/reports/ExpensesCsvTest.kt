package com.westly.nbms.features.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ExpensesCsvTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    private fun expense(
        title: String = "Electricity",
        amount: Double = 25_000.0,
        category: String = "utilities",
        date: String? = "2026-10-09T10:00:00Z",
        method: String = "bank_transfer",
        by: String = "Ngozi"
    ) = expenseDoc("a", title, amount, category, date, method, by).toExpense()

    @Test fun headerHasTheSixColumnsAllDoubleQuoted() {
        assertEquals(
            "\"Date\",\"Title\",\"Category\",\"Amount\",\"Payment Method\",\"Recorded By\"",
            EXPENSES_CSV_HEADER
        )
    }

    @Test fun everyCellIsDoubleQuoted() {
        assertEquals(
            "\"2026-10-09\",\"Electricity\",\"utilities\",\"25000\",\"bank_transfer\",\"Ngozi\"",
            expensesCsvRow(expense(), "2026-10-09")
        )
    }

    @Test fun innerQuotesAreDoubledAndCommasStayInOneCell() {
        assertEquals("\"say \"\"hi\"\", ok\"", expensesCsvCell("say \"hi\", ok"))
        val row = expensesCsvRow(expense(title = "5\" pipe, 2 pcs"), "2026-10-09")
        assertEquals("\"2026-10-09\",\"5\"\" pipe, 2 pcs\",\"utilities\",\"25000\",\"bank_transfer\",\"Ngozi\"", row)
    }

    @Test fun lineBreaksStayInsideTheirCell() {
        assertEquals("\"a\nb\"", expensesCsvCell("a\nb"))
    }

    @Test fun amountsArePlainNumbersWithoutSymbolOrSeparators() {
        assertEquals("25000", expensesCsvAmount(25_000.0))
        assertEquals("2500.5", expensesCsvAmount(2_500.5))
        assertEquals("0", expensesCsvAmount(0.0))
        assertEquals("0", expensesCsvAmount(Double.NaN))
    }

    @Test fun dateIsTheBusinessDayAndEmptyWhenMissing() {
        // 23:30 UTC on 9 Oct is 00:30 on 10 Oct in Lagos
        assertEquals("2026-10-10", expensesCsvDate(expense(date = "2026-10-09T23:30:00Z"), lagos))
        assertEquals("2026-10-09", expensesCsvDate(expense(date = "2026-10-09T10:00:00Z"), lagos))
        assertEquals("", expensesCsvDate(expense(date = null), lagos))
    }

    @Test fun theWholeFileIsTheHeaderThenOneLinePerRow() {
        val rows = listOf(expense(title = "A"), expense(title = "B", date = null))
        val lines = buildExpensesCsv(rows, lagos).split("\n")
        assertEquals(3, lines.size)
        assertEquals(EXPENSES_CSV_HEADER, lines[0])
        assertTrue(lines[1].startsWith("\"2026-10-09\",\"A\""))
        assertTrue(lines[2].startsWith("\"\",\"B\""))
    }

    @Test fun anEmptyListStillHasTheHeader() {
        assertEquals(EXPENSES_CSV_HEADER, buildExpensesCsv(emptyList(), lagos))
    }

    @Test fun fileNamesUseTheMonthOrAll() {
        assertEquals("expenses-2026-10.csv", expensesCsvFileName("2026-10"))
        assertEquals("expenses-2026-10.csv", expensesCsvFileName(" 2026-10 "))
        assertEquals("expenses-all.csv", expensesCsvFileName(""))
        assertEquals("expenses-all.csv", expensesCsvFileName("   "))
    }
}
