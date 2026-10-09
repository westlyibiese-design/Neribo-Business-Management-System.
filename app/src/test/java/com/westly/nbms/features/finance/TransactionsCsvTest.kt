package com.westly.nbms.features.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TransactionsCsvTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    @Test fun headerIsExactlyTheEleven() {
        assertEquals(
            "Date & Time,Guest,Type,Category,Amount,Method,Status,Recorded By,Approved By,Approval Date,Rejection Reason",
            TRANSACTIONS_CSV_HEADER
        )
    }

    @Test fun cellsAreDoubleQuotedAndInnerQuotesDoubled() {
        assertEquals("\"plain\"", transactionsCsvCell("plain"))
        assertEquals("\"Ada \"\"AJ\"\" Obi, Jr.\"", transactionsCsvCell("Ada \"AJ\" Obi, Jr."))
        assertEquals("\"\"", transactionsCsvCell(""))
        assertEquals("\"line1\nline2\"", transactionsCsvCell("line1\nline2"))
    }

    @Test fun approvedRowCarriesApprovedByAndApprovalDate() {
        val txn = approvalsTxn(
            id = "a",
            status = ApprovalStatus.APPROVED,
            amount = 10_000.0,
            date = "2026-10-09T09:00:00Z",
            approvedByName = "Ngozi",
            approvedAt = "2026-10-09T11:00:00Z"
        )
        assertEquals(
            "\"2026-10-09 10:00\",\"Ada Obi\",\"Room Payment\",\"Room Revenue\",\"10000\",\"cash\",\"Approved\",\"Rita\",\"Ngozi\",\"2026-10-09\",\"\"",
            transactionsCsvRow(txn, lagos)
        )
    }

    @Test fun rejectedRowCarriesTheReasonAndQuotesInsideIt() {
        val txn = approvalsTxn(
            id = "r",
            status = ApprovalStatus.REJECTED,
            amount = 500.0,
            reason = "Duplicate, \"same\" payment"
        )
        val row = transactionsCsvRow(txn, lagos)
        assertTrue(row.endsWith(",\"Duplicate, \"\"same\"\" payment\""))
        assertTrue(row.contains(",\"Rejected\","))
    }

    @Test fun pendingRowHasEmptyApprovalCells() {
        val row = transactionsCsvRow(approvalsTxn("p"), lagos)
        assertTrue(row.endsWith(",\"Pending\",\"Rita\",\"\",\"\",\"\""))
    }

    @Test fun missingDateIsAnEmptyCell() {
        val row = transactionsCsvRow(approvalsTxn("n", date = null), lagos)
        assertTrue(row.startsWith("\"\",\"Ada Obi\""))
    }

    @Test fun amountsArePlainDigits() {
        assertEquals("5000", transactionsCsvAmount(5000.0))
        assertEquals("5000.5", transactionsCsvAmount(5000.5))
        assertEquals("1234567.25", transactionsCsvAmount(1_234_567.25))
        assertEquals("0", transactionsCsvAmount(Double.NaN))
    }

    @Test fun everyDataCellIsQuoted() {
        val row = transactionsCsvRow(approvalsTxn("q", guest = "A,B"), lagos)
        assertTrue(row.startsWith("\"") && row.endsWith("\""))
        // 11 cells -> 22 quote marks when no cell has an inner quote.
        assertEquals(22, row.count { it == '"' })
    }

    @Test fun fileIsTheHeaderThenOneLinePerRow() {
        val rows = listOf(approvalsTxn("1"), approvalsTxn("2"))
        val lines = buildTransactionsCsv(rows, lagos).split("\n")
        assertEquals(3, lines.size)
        assertEquals(TRANSACTIONS_CSV_HEADER, lines[0])
    }

    @Test fun emptyListStillHasTheHeader() {
        assertEquals(TRANSACTIONS_CSV_HEADER, buildTransactionsCsv(emptyList(), lagos))
    }

    @Test fun fileNameUsesTheRange() {
        assertEquals(
            "transactions-2026-10-01_to_2026-10-31.csv",
            transactionsCsvFileName(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        )
    }
}
