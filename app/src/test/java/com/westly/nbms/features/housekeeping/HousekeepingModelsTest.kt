package com.westly.nbms.features.housekeeping

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HousekeepingModelsTest {

    @Test fun enumKeysAndLabelsAreExact() {
        assertEquals(
            listOf("checkout_cleaning" to "Check-out Cleaning", "occupied_service" to "Occupied Room Service", "manual" to "Manual / Ad-hoc",
                "maintenance_followup" to "Maintenance Follow-up", "cleaning" to "Cleaning"),
            TaskType.entries.map { it.key to it.label }
        )
        assertEquals(listOf("pending", "in_progress", "completed", "skipped"), TaskStatus.entries.map { it.key })
        assertEquals(
            listOf("low" to "Low", "medium" to "Medium", "high" to "High", "urgent" to "Urgent"),
            TaskPriority.entries.map { it.key to it.label }
        )
    }

    @Test fun emptyTaskDocumentParsesWithDefaults() {
        val t = parseHousekeepingTask("t1", emptyMap())
        assertEquals("t1", t.id); assertEquals("", t.roomId); assertEquals("", t.roomNumber)
        assertEquals(TaskType.CLEANING, t.type); assertEquals(TaskStatus.PENDING, t.status); assertEquals(TaskPriority.MEDIUM, t.priority)
        assertNull(t.instructions); assertNull(t.assignedTo); assertNull(t.assignedToName); assertNull(t.assignedBy); assertNull(t.assignedByName)
        assertNull(t.scheduledFor); assertEquals("", t.source); assertNull(t.bookingId); assertEquals("", t.dayKey); assertEquals(0.0, t.weight, 0.0)
        assertNull(t.homeOwnerId); assertNull(t.homeOwnerName); assertFalse(t.rebalanced)
        assertNull(t.createdAt); assertNull(t.updatedAt); assertNull(t.startedAt); assertNull(t.completedAt)
        assertNull(t.completedBy); assertNull(t.completedByName); assertNull(t.skipReason); assertFalse(t.isDeleted)
    }

    @Test fun unknownEnumValuesFallBack() {
        val t = parseHousekeepingTask("t", mapOf("type" to "wizardry", "status" to "limbo", "priority" to "whenever"))
        assertEquals(TaskType.CLEANING, t.type); assertEquals(TaskStatus.PENDING, t.status); assertEquals(TaskPriority.MEDIUM, t.priority)
    }

    @Test fun fullTaskDocumentParses() {
        val ts = Timestamp(1_772_000_000L, 0)
        val t = parseHousekeepingTask(
            "t9",
            mapOf(
                "roomId" to "r1", "roomNumber" to "101", "type" to "checkout_cleaning", "status" to "completed", "priority" to "urgent",
                "instructions" to "Hurry", "assignedTo" to "u1", "assignedToName" to "Hana", "assignedBy" to "u2", "assignedByName" to "Sam",
                "scheduledFor" to ts, "source" to "auto_checkout", "bookingId" to "b1", "dayKey" to "2026-03-05", "weight" to 3L,
                "homeOwnerId" to "u3", "homeOwnerName" to "Ola", "rebalanced" to true,
                "createdAt" to ts, "updatedAt" to ts, "startedAt" to ts, "completedAt" to ts,
                "completedBy" to "u1", "completedByName" to "Hana", "skipReason" to "n/a", "isDeleted" to true
            )
        )
        assertEquals(TaskType.CHECKOUT_CLEANING, t.type); assertEquals(TaskStatus.COMPLETED, t.status); assertEquals(TaskPriority.URGENT, t.priority)
        assertEquals(Instant.ofEpochSecond(1_772_000_000L), t.scheduledFor); assertEquals(3.0, t.weight, 0.0)
        assertTrue(t.rebalanced); assertTrue(t.isDeleted); assertEquals("b1", t.bookingId); assertEquals("Ola", t.homeOwnerName)
        assertEquals(Instant.ofEpochSecond(1_772_000_000L), t.completedAt)
    }

    @Test fun wrongTypedValuesNeverThrow() {
        val t = parseHousekeepingTask("t", mapOf("roomNumber" to 101, "weight" to "heavy", "rebalanced" to "yes", "scheduledFor" to "tomorrow", "instructions" to 5))
        assertEquals("", t.roomNumber); assertEquals(0.0, t.weight, 0.0); assertFalse(t.rebalanced); assertNull(t.scheduledFor); assertNull(t.instructions)
    }

    @Test fun emptyGroupDocumentParsesWithDefaults() {
        val g = parseAssignmentGroup("g1", emptyMap())
        assertEquals("g1", g.id); assertEquals("", g.housekeeperId); assertEquals("", g.housekeeperName)
        assertTrue(g.roomIds.isEmpty()); assertTrue(g.roomNumbers.isEmpty())
        assertNull(g.startDate); assertNull(g.endDate); assertNull(g.notes); assertEquals("", g.status)
        assertNull(g.createdBy); assertNull(g.createdByName); assertNull(g.createdAt); assertNull(g.updatedAt); assertFalse(g.isDeleted)
    }

    @Test fun fullGroupDocumentParses() {
        val ts = Timestamp(1_772_000_000L, 0)
        val g = parseAssignmentGroup(
            "g2",
            mapOf(
                "housekeeperId" to "hk1", "housekeeperName" to "Hana", "roomIds" to listOf("r1", "r2"), "roomNumbers" to listOf("101", "102"),
                "startDate" to ts, "endDate" to null, "notes" to "VIP", "status" to "active", "createdBy" to "u1", "createdByName" to "Sam",
                "createdAt" to ts, "updatedAt" to ts, "isDeleted" to false
            )
        )
        assertEquals(listOf("r1", "r2"), g.roomIds); assertEquals(listOf("101", "102"), g.roomNumbers)
        assertEquals(Instant.ofEpochSecond(1_772_000_000L), g.startDate); assertNull(g.endDate); assertEquals("VIP", g.notes); assertEquals("active", g.status)
    }

    @Test fun listsWithJunkAreCleaned() {
        val g = parseAssignmentGroup("g", mapOf("roomIds" to listOf("r1", null, 7), "roomNumbers" to "not a list"))
        assertEquals(listOf("r1", "7"), g.roomIds); assertTrue(g.roomNumbers.isEmpty())
    }

    @Test fun roomAssignmentParses() {
        val ts = Timestamp(1_772_000_000L, 0)
        val a = parseRoomAssignment("r1", mapOf("roomNumber" to "101", "housekeeperId" to "hk1", "housekeeperName" to "Hana", "groupId" to "g1", "startDate" to ts, "status" to "active"))
        assertEquals("r1", a.roomId); assertEquals("101", a.roomNumber); assertEquals("hk1", a.housekeeperId); assertEquals("g1", a.groupId)
        assertEquals(Instant.ofEpochSecond(1_772_000_000L), a.startDate); assertNull(a.endDate); assertEquals("active", a.status)
        val empty = parseRoomAssignment("r2", emptyMap())
        assertEquals("r2", empty.roomId); assertEquals("", empty.groupId); assertNull(empty.startDate)
    }
}
