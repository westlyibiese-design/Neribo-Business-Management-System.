package com.westly.nbms.features.finance

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PaymentsCsvTest {

    private fun doc(
        guest: String? = "Ada Obi",
        amount: Double = 5_000.0,
        method: String? = "cash",
        type: String? = "deposit",
        status: String? = "approved",
        recordedBy: String? = "Rita",
        approvedBy: String? = "Ola"
    ) = PaymentDoc(
        id = "p1",
        guestName = guest,
        amount = amount,
        paymentMethod = method,
        type = type,
        approvalStatus = status,
        recordedByName = recordedBy,
        approvedByName = approvedBy,
        createdAt = Timestamp(1_791_367_200L, 0) // 2026-10-07T10:00:00Z
    )

    @Test fun headerIsExactAndEveryCellIsQuoted() {
        assertEquals(
            "\"Date\",\"Guest\",\"Amount\",\"Payment Method\",\"Type\",\"Status\",\"Recorded By\",\"Approved By\"",
            PAYMENTS_CSV_HEADER
        )
        assertEquals(
            "Date,Guest,Amount,Payment Method,Type,Status,Recorded By,Approved By",
            PAYMENTS_CSV_HEADER.replace("\"", "")
        )
    }

    @Test fun aRowHasEightQuotedCellsInOrder() {
        val row = paymentsCsvRow(doc(), "2026-10-07")
        assertEquals("\"2026-10-07\",\"Ada Obi\",\"5000\",\"cash\",\"deposit\",\"approved\",\"Rita\",\"Ola\"", row)
    }

    @Test fun innerQuotesAreDoubledAndCommasStayInsideTheCell() {
        val row = paymentsCsvRow(doc(guest = "Ada \"AJ\" Obi, Jr"), "2026-10-07")
        assertTrue(row.contains("\"Ada \"\"AJ\"\" Obi, Jr\""))
        assertEquals("\"a\"\"b\"", paymentsCsvCell("a\"b"))
        assertEquals("\"\"", paymentsCsvCell(""))
    }

    @Test fun missingValuesBecomeEmptyQuotedCellsAndStatusIsNormalised() {
        val row = paymentsCsvRow(doc(guest = null, method = null, type = null, status = null, recordedBy = null, approvedBy = null), "")
        assertEquals("\"\",\"\",\"5000\",\"\",\"\",\"pending\",\"\",\"\"", row)
    }

    @Test fun amountsArePlainNumbers() {
        assertEquals("5000", paymentsCsvAmount(5_000.0))
        assertEquals("5000.5", paymentsCsvAmount(5_000.5))
        assertEquals("0", paymentsCsvAmount(0.0))
        assertEquals("1234567.25", paymentsCsvAmount(1_234_567.25))
    }

    @Test fun theFileHasTheHeaderThenOneLinePerRow() {
        val csv = buildPaymentsCsv(listOf(doc(), doc(guest = "Bola"))) { "2026-10-07" }
        val lines = csv.split("\n")
        assertEquals(3, lines.size)
        assertEquals(PAYMENTS_CSV_HEADER, lines[0])
        assertTrue(lines[2].startsWith("\"2026-10-07\",\"Bola\""))
        assertEquals(PAYMENTS_CSV_HEADER, buildPaymentsCsv(emptyList()) { "" })
    }

    @Test fun dateIsTheBusinessDay() {
        // 23:30 UTC is already the next day in Lagos (UTC+1).
        val late = doc().copy(createdAt = Timestamp(1_791_415_800L, 0)) // 2026-10-07T23:30:00Z
        assertEquals("2026-10-08", paymentsCsvDate(late, ZoneId.of("Africa/Lagos")))
        assertEquals("2026-10-07", paymentsCsvDate(late, ZoneId.of("UTC")))
        assertEquals("", paymentsCsvDate(doc().copy(createdAt = null), ZoneId.of("UTC")))
    }

    @Test fun fileNames() {
        assertEquals("payments-2026-10.csv", paymentsCsvFileName("2026-10"))
        assertEquals("payments-all.csv", paymentsCsvFileName(""))
        assertEquals("payments-all.csv", paymentsCsvFileName("  "))
    }
}
