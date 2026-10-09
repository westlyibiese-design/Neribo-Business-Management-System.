package com.westly.nbms.features.laundry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LaundryHistoryCsvTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    @Test fun theHeaderHasTheTenColumnsInOrderEachInQuotes() {
        assertEquals(
            "\"Date\",\"Guest\",\"Room\",\"Items\",\"Item Count\",\"Status\",\"Charge\",\"Payment Status\",\"Logged By\",\"Delivered At\"",
            laundryHistoryCsvHeader()
        )
        assertEquals(10, LAUNDRY_HISTORY_CSV_COLUMNS.size)
    }

    @Test fun aRowMapsEveryColumnAndQuotesEveryCell() {
        val request = laundryHistoryRequestOf(
            "a", guest = "Mr Okoro", room = "201", items = "2 shirts", count = 2, charge = 4000.0,
            payment = PaymentStatus.PAID, status = LaundryStatus.DELIVERED, valet = "Wale",
            at = Instant.parse("2026-10-09T10:00:00Z"), deliveredAt = Instant.parse("2026-10-09T12:15:00Z")
        )
        assertEquals(
            "\"2026-10-09 11:00\",\"Mr Okoro\",\"201\",\"2 shirts\",\"2\",\"Delivered\",\"4000\",\"Paid\",\"Wale\",\"2026-10-09 13:15\"",
            laundryHistoryCsvRow(request, lagos)
        )
    }

    @Test fun statusAndPaymentUseTheirLabels() {
        val row = laundryHistoryCsvRow(
            laundryHistoryRequestOf("a", status = LaundryStatus.READY, payment = PaymentStatus.UNPAID), lagos
        )
        assertTrue(row.contains("\"Ready for Collection\""))
        assertTrue(row.contains("\"Unpaid\""))
    }

    @Test fun innerQuotesAreDoubledAndCommasAndLineBreaksStayInOneCell() {
        val request = laundryHistoryRequestOf("a", guest = "Mr \"Big\" Okoro", items = "shirts, trousers\nsuit")
        val row = laundryHistoryCsvRow(request, lagos)
        assertTrue(row.contains("\"Mr \"\"Big\"\" Okoro\""))
        assertTrue(row.contains("\"shirts, trousers\nsuit\""))
        assertEquals("\"a\"\"b\"", laundryHistoryCsvCell("a\"b"))
        assertEquals("\"\"", laundryHistoryCsvCell(""))
    }

    @Test fun missingValuesAreEmptyQuotedCells() {
        val request = laundryHistoryRequestOf("a", guest = null, room = null, items = null, count = 1, at = null, deliveredAt = null)
        assertEquals(
            "\"\",\"\",\"\",\"\",\"1\",\"Received\",\"4000\",\"Unpaid\",\"Wale\",\"\"",
            laundryHistoryCsvRow(request, lagos)
        )
    }

    @Test fun theChargeIsPlainDigitsWithoutSymbolOrSeparators() {
        assertEquals("4000", laundryHistoryCsvAmount(4000.0))
        assertEquals("2500.5", laundryHistoryCsvAmount(2500.5))
        assertEquals("0", laundryHistoryCsvAmount(0.0))
        assertEquals("12345678", laundryHistoryCsvAmount(12345678.0))
        assertEquals("10000000", laundryHistoryCsvAmount(1.0E7))
        assertEquals("0", laundryHistoryCsvAmount(Double.NaN))
        assertEquals("0", laundryHistoryCsvAmount(Double.POSITIVE_INFINITY))
    }

    @Test fun timesAreWrittenInTheBusinessTimeZone() {
        assertEquals("2026-10-09 11:00", laundryHistoryCsvDateTime(Instant.parse("2026-10-09T10:00:00Z"), lagos))
        assertEquals("2026-10-10 00:30", laundryHistoryCsvDateTime(Instant.parse("2026-10-09T23:30:00Z"), lagos))
        assertEquals("", laundryHistoryCsvDateTime(null, lagos))
    }

    @Test fun theWholeFileIsTheHeaderThenOneLinePerRowWithNoTrailingBlankLine() {
        val rows = listOf(laundryHistoryRequestOf("a"), laundryHistoryRequestOf("b", guest = "Other"))
        val lines = buildLaundryHistoryCsv(rows, lagos).split("\n")
        assertEquals(3, lines.size)
        assertEquals(laundryHistoryCsvHeader(), lines[0])
        assertEquals(laundryHistoryCsvRow(rows[0], lagos), lines[1])
        assertEquals(laundryHistoryCsvRow(rows[1], lagos), lines[2])
    }

    @Test fun anEmptyListStillExportsTheHeader() {
        assertEquals(laundryHistoryCsvHeader(), buildLaundryHistoryCsv(emptyList(), lagos))
    }

    @Test fun theFileNameFollowsTheMonthOrSaysAll() {
        assertEquals("laundry-history-2026-10.csv", laundryHistoryCsvFileName("2026-10"))
        assertEquals("laundry-history-2026-10.csv", laundryHistoryCsvFileName(" 2026-10 "))
        assertEquals("laundry-history-all.csv", laundryHistoryCsvFileName(""))
        assertEquals("laundry-history-all.csv", laundryHistoryCsvFileName("   "))
    }

    @Test fun theExportCarriesTheFilteredRowsAndTheMonthsFileName() {
        val rows = listOf(
            laundryHistoryRequestOf("hit", guest = "Mr Okoro"),
            laundryHistoryRequestOf("miss", guest = "Someone Else")
        )
        val filters = LaundryHistoryFilters(month = "2026-10", search = "okoro")
        val export = laundryHistoryExportOf(filterLaundryHistory(rows, filters, lagos), filters, lagos)
        assertEquals("laundry-history-2026-10.csv", export.fileName)
        assertEquals(2, export.content.split("\n").size) // header + the one match
        assertTrue(export.content.contains("Mr Okoro"))
        assertTrue(!export.content.contains("Someone Else"))

        val all = laundryHistoryExportOf(rows, LaundryHistoryFilters(month = ""), lagos)
        assertEquals("laundry-history-all.csv", all.fileName)
    }
}
