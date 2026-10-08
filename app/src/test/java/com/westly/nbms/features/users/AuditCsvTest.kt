package com.westly.nbms.features.users

import com.westly.nbms.features.users.models.AuditLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditCsvTest {

    private fun entry(
        user: String = "Ada",
        role: String = "super_admin",
        action: String = "user_created",
        collection: String = "users",
        doc: String = "abc123"
    ) = AuditLogEntry(userName = user, userRole = role, action = action, collection = collection, documentId = doc)

    @Test
    fun cellIsWrappedInQuotes() {
        assertEquals("\"hello\"", csvCell("hello"))
    }

    @Test
    fun innerQuotesAreDoubled() {
        assertEquals("\"say \"\"hi\"\"\"", csvCell("say \"hi\""))
    }

    @Test
    fun commasAndNewlinesStayInsideTheQuotes() {
        assertEquals("\"a,b\nc\"", csvCell("a,b\nc"))
    }

    @Test
    fun headerIsExact() {
        assertEquals("\"Timestamp\",\"User\",\"Role\",\"Action\",\"Collection\",\"Document ID\"", CSV_HEADER)
    }

    @Test
    fun rowHasSixQuotedCellsInOrder() {
        val row = csvRow(entry(), "12 Mar 2025, 14:30")
        assertEquals(
            "\"12 Mar 2025, 14:30\",\"Ada\",\"super_admin\",\"user_created\",\"users\",\"abc123\"",
            row
        )
    }

    @Test
    fun fileHasHeaderThenOneLinePerEntry() {
        val csv = buildAuditCsv(listOf(entry(user = "A"), entry(user = "B"))) { "T" }
        val lines = csv.split("\n")
        assertEquals(3, lines.size)
        assertEquals(CSV_HEADER, lines[0])
        assertEquals("\"T\",\"A\",\"super_admin\",\"user_created\",\"users\",\"abc123\"", lines[1])
        assertEquals("\"T\",\"B\",\"super_admin\",\"user_created\",\"users\",\"abc123\"", lines[2])
    }

    @Test
    fun emptyListGivesOnlyTheHeader() {
        assertEquals(CSV_HEADER, buildAuditCsv(emptyList()) { "T" })
    }

    @Test
    fun fileNameUsesTheDate() {
        assertEquals("audit-log-2026-10-08.csv", auditCsvFileName("2026-10-08"))
    }
}
