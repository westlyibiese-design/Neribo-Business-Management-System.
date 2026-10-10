package com.westly.nbms.features.opslog

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MaintenanceRulesTest {

    private fun at(minutes: Long): Instant = MT_NOW.plusSeconds(minutes * 60)

    // ── open / closed split ──

    @Test fun anythingNotClosedCountsAsOpen() {
        val all = listOf(
            mtRequest("a", status = "open"),
            mtRequest("b", status = "in_progress"),
            mtRequest("c", status = ""),
            mtRequest("d", status = "closed", closedAt = at(1))
        )
        assertEquals(setOf("a", "b", "c"), openMaintenance(all).map { it.id }.toSet())
        assertEquals(listOf("d"), closedMaintenance(all).map { it.id })
    }

    @Test fun parserTreatsMissingStatusAsOpenAndUnknownPriorityAsMedium() {
        val r = parseMaintenanceRequest("x", mapOf("title" to "Leak", "priority" to "urgent!!"))
        assertTrue(r.isOpen)
        assertEquals(MaintenancePriority.MEDIUM, r.priority)
        assertEquals("—", r.roomNumber)
        assertEquals("—", r.reportedByName)
        assertNull(r.description)
    }

    @Test fun parserReadsAClosedRequest() {
        val r = parseMaintenanceRequest(
            "x",
            mapOf(
                "roomId" to "r2", "roomNumber" to "202", "title" to "AC", "description" to "Warm air", "priority" to "high",
                "status" to "closed", "reportedByName" to "Ada", "closedByName" to "Tunde", "isDeleted" to false
            )
        )
        assertTrue(r.isClosed)
        assertEquals(MaintenancePriority.HIGH, r.priority)
        assertEquals("Warm air", r.description)
        assertEquals("Tunde", r.closedByName)
    }

    // ── ordering ──

    @Test fun openRequestsGoCriticalHighMediumLowThenOldestFirst() {
        val all = listOf(
            mtRequest("low-old", MaintenancePriority.LOW, createdAt = at(0)),
            mtRequest("med-new", MaintenancePriority.MEDIUM, createdAt = at(30)),
            mtRequest("crit-new", MaintenancePriority.CRITICAL, createdAt = at(40)),
            mtRequest("high-old", MaintenancePriority.HIGH, createdAt = at(5)),
            mtRequest("crit-old", MaintenancePriority.CRITICAL, createdAt = at(10)),
            mtRequest("med-old", MaintenancePriority.MEDIUM, createdAt = at(20)),
            mtRequest("high-new", MaintenancePriority.HIGH, createdAt = at(25))
        )
        assertEquals(
            listOf("crit-old", "crit-new", "high-old", "high-new", "med-old", "med-new", "low-old"),
            openMaintenance(all).map { it.id }
        )
    }

    @Test fun aRequestWithNoTimeYetGoesLastInItsPriority() {
        val all = listOf(
            mtRequest("pending", MaintenancePriority.HIGH, createdAt = null),
            mtRequest("dated", MaintenancePriority.HIGH, createdAt = at(5))
        )
        assertEquals(listOf("dated", "pending"), openMaintenance(all).map { it.id })
    }

    @Test fun priorityRanksAreCriticalFirst() {
        assertEquals(
            listOf(MaintenancePriority.CRITICAL, MaintenancePriority.HIGH, MaintenancePriority.MEDIUM, MaintenancePriority.LOW),
            MaintenancePriority.entries.sortedBy { it.rank }
        )
    }

    @Test fun onlyHighAndCriticalAreUrgent() {
        assertTrue(MaintenancePriority.HIGH.isUrgent)
        assertTrue(MaintenancePriority.CRITICAL.isUrgent)
        assertFalse(MaintenancePriority.MEDIUM.isUrgent)
        assertFalse(MaintenancePriority.LOW.isUrgent)
    }

    @Test fun closedRequestsAreNewestClosedFirstAndOnlyFiveShow() {
        val all = (1..7).map { mtRequest("c$it", status = "closed", closedAt = at(it.toLong())) } +
            mtRequest("noTime", status = "closed", closedAt = null) +
            mtRequest("o1")
        assertEquals(listOf("c7", "c6", "c5", "c4", "c3"), recentlyClosedMaintenance(all).map { it.id })
        // The one with no close time sorts after every dated one.
        assertEquals("noTime", closedMaintenance(all).last().id)
    }

    @Test fun nothingClosedMeansNothingToShow() {
        assertTrue(recentlyClosedMaintenance(listOf(mtRequest("a"))).isEmpty())
    }

    // ── labels ──

    @Test fun subtitleAndLabels() {
        assertEquals("0 open request(s)", maintenanceSubtitle(0))
        assertEquals("3 open request(s)", maintenanceSubtitle(3))
        assertEquals("Room 202", maintenanceRoomLabel("202"))
        assertEquals("Room 202 — Deluxe Room", MaintenanceRoomOption("r2", "202", "Deluxe Room").label)
        assertEquals("—", maintenanceDate(null))
    }

    @Test fun priorityDropdownIsLowMediumHighCritical() {
        assertEquals(listOf("Low", "Medium", "High", "Critical"), MAINTENANCE_PRIORITY_OPTIONS.map { it.label })
        assertEquals(MaintenancePriority.MEDIUM, MaintenanceForm().priority)
    }

    // ── close and log messages ──

    @Test fun closeMessageWhenTheRoomIsFreed() {
        val t = closeResultToast("202", roomFreed = true)
        assertEquals("Maintenance Closed", t.title)
        assertEquals("Room 202 is now available.", t.message)
    }

    @Test fun closeMessageWhenTheRoomStillHoldsAGuest() {
        val t = closeResultToast("202", roomFreed = false)
        assertEquals("Maintenance Closed", t.title)
        assertEquals("Room 202 stays Occupied.", t.message)
    }

    @Test fun logMessageWhenEverythingWorked() {
        val input = MaintenanceInput("r2", "202", "AC not cooling", "", MaintenancePriority.HIGH)
        val t = logResultToast(input, roomError = null)
        assertEquals("Maintenance Request Logged", t.title)
    }

    @Test fun logMessageWhenTheRoomCouldNotBeChanged() {
        val input = MaintenanceInput("r2", "202", "AC not cooling", "", MaintenancePriority.HIGH)
        val t = logResultToast(input, roomError = "Room not found")
        assertEquals("Request logged", t.title)
        assertEquals("The request was saved, but the room status couldn't be changed: Room not found", t.message)
    }

    // ── validation ──

    @Test fun roomIsRequired() {
        val check = validateMaintenance(MaintenanceForm(roomId = null, title = "Leak"), MT_ROOMS)
        assertEquals(MaintenanceCheck.Toast(TITLE_MAINTENANCE_MISSING, MSG_MAINTENANCE_ROOM_REQUIRED), check)
    }

    @Test fun aRoomThatIsNotInTheListIsRejected() {
        val check = validateMaintenance(MaintenanceForm(roomId = "gone", title = "Leak"), MT_ROOMS)
        assertTrue(check is MaintenanceCheck.Toast)
    }

    @Test fun titleIsRequiredAndBlankDoesNotCount() {
        val check = validateMaintenance(MaintenanceForm(roomId = "r2", title = "   "), MT_ROOMS)
        assertEquals(MaintenanceCheck.Toast(TITLE_MAINTENANCE_MISSING, MSG_MAINTENANCE_TITLE_REQUIRED), check)
    }

    @Test fun descriptionIsOptionalAndTextIsTrimmed() {
        val check = validateMaintenance(
            MaintenanceForm(roomId = "r2", title = "  AC not cooling ", description = "  ", priority = MaintenancePriority.HIGH),
            MT_ROOMS
        ) as MaintenanceCheck.Ready
        assertEquals(MaintenanceInput("r2", "202", "AC not cooling", "", MaintenancePriority.HIGH), check.input)
    }

    // ── permissions: who sees Log Request and Close ──

    @Test fun logAndCloseAreShownToEveryoneWhoCanOpenThePage() {
        for (role in listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER)) {
            assertTrue("open $role", maintenanceCanOpen(role))
            assertTrue("log $role", maintenanceCanLog(role))
            assertTrue("close $role", maintenanceCanClose(role))
        }
    }

    @Test fun otherRolesDoNotGetTheControls() {
        for (role in Role.entries - setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER)) {
            assertFalse("open $role", maintenanceCanOpen(role))
            assertFalse("log $role", maintenanceCanLog(role))
            assertFalse("close $role", maintenanceCanClose(role))
        }
    }

    // ── payloads ──

    @Test fun createPayloadHasExactlyTheDocumentedFields() {
        val input = MaintenanceInput("r2", "202", "AC not cooling", "Warm air", MaintenancePriority.HIGH)
        val p = buildMaintenanceCreatePayload(input, "u1", "Ada")
        assertEquals(
            mapOf(
                "roomId" to "r2", "roomNumber" to "202", "title" to "AC not cooling", "description" to "Warm air",
                "priority" to "high", "status" to "open", "reportedBy" to "u1", "reportedByName" to "Ada",
                "createdAt" to MaintenanceServerTime, "isDeleted" to false
            ),
            p
        )
    }

    @Test fun closePayloadChangesOnlyTheClosingFields() {
        val p = buildMaintenanceClosePayload("u9", "Tunde")
        assertEquals(
            mapOf("status" to "closed", "closedAt" to MaintenanceServerTime, "closedBy" to "u9", "closedByName" to "Tunde"),
            p
        )
    }
}
