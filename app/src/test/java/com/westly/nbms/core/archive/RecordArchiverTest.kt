package com.westly.nbms.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordArchiverTest {

    private val now = "SERVER_TIME"

    @Test
    fun softDeleteFieldsMarkTheOriginalRecord() {
        val f = softDeleteFields("u1", "Ada", "Duplicate entry", now)
        assertEquals(true, f["isDeleted"])
        assertEquals(now, f["deletedAt"])
        assertEquals("u1", f["deletedBy"])
        assertEquals("Ada", f["deletedByName"])
        assertEquals("Duplicate entry", f["deleteReason"])
    }

    @Test
    fun missingReasonIsStoredAsNull() {
        val f = softDeleteFields("u1", "Ada", null, now)
        assertTrue(f.containsKey("deleteReason"))
        assertNull(f["deleteReason"])
    }

    @Test
    fun archiveEntryHasExactlyTheAgreedFields() {
        val e = archiveEntry("rooms", "r1", "Room 101", "u1", "Ada", "receptionist", null, now)
        assertEquals(
            setOf(
                "originalCollection", "originalDocumentId", "label", "deletedBy",
                "deletedByName", "deletedByRole", "deletedAt", "reason"
            ),
            e.keys
        )
        assertEquals("rooms", e["originalCollection"])
        assertEquals("r1", e["originalDocumentId"])
        assertEquals("Room 101", e["label"])
        assertEquals("receptionist", e["deletedByRole"])
        assertEquals(now, e["deletedAt"])
        assertNull(e["reason"])
    }

    @Test
    fun restoreFieldsBringTheRecordBack() {
        val f = restoreFields("u9", "Boss", now)
        assertEquals(false, f["isDeleted"])
        assertEquals(now, f["restoredAt"])
        assertEquals("u9", f["restoredBy"])
        assertEquals("Boss", f["restoredByName"])
        assertEquals(4, f.size)
    }
}
