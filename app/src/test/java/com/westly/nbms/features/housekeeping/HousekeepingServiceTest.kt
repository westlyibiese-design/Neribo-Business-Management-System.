package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.notify.NotificationType
import com.westly.nbms.features.rooms.Cleanliness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HousekeepingServiceTest {

    private class Rig(zone: ZoneId = ZoneId.of("Africa/Lagos")) {
        val store = FakeHousekeepingStore()
        val rooms = FakeHousekeepingRoomLogic()
        val audit = FakeHousekeepingAudit()
        val notifier = FakeHousekeepingNotifier()
        val service = HousekeepingServiceImpl(store, rooms, audit, notifier) { zone }
    }

    private val r101 = HousekeepingRoomRef("r101", "101", "Deluxe")
    private val r102 = HousekeepingRoomRef("r102", "102", "Deluxe")
    private val r103 = HousekeepingRoomRef("r103", "103", "Standard")

    private fun day(s: String) = LocalDate.parse(s)
    private fun midnightUtc(s: String) = Instant.parse("${s}T00:00:00Z")

    // ---- createManualTask ----

    @Test fun createManualTaskWritesEveryField() = runTest {
        val r = Rig()
        // 23:30 UTC on 4 Mar is 00:30 on 5 Mar in Lagos (UTC+1)
        val scheduled = Instant.parse("2026-03-04T23:30:00Z")
        val id = r.service.createManualTask(
            "r101", "101", TaskType.CHECKOUT_CLEANING, TaskPriority.URGENT, "Deep clean",
            "hk1", "Hana", scheduled, TEST_ACTOR
        )
        val doc = r.store.docs.getValue("housekeeping_tasks").getValue(id)
        assertEquals("r101", doc["roomId"]); assertEquals("101", doc["roomNumber"])
        assertEquals("checkout_cleaning", doc["type"]); assertEquals("pending", doc["status"]); assertEquals("urgent", doc["priority"])
        assertEquals("Deep clean", doc["instructions"])
        assertEquals("hk1", doc["assignedTo"]); assertEquals("Hana", doc["assignedToName"])
        assertEquals("sup1", doc["assignedBy"]); assertEquals("Sam Super", doc["assignedByName"])
        assertEquals(scheduled, doc["scheduledFor"])
        assertEquals("manual", doc["source"]); assertNull(doc["bookingId"])
        assertEquals("2026-03-05", doc["dayKey"])
        assertEquals(3.0, doc["weight"]) // checkout 2 + urgent 1
        assertNull(doc["homeOwnerId"]); assertNull(doc["homeOwnerName"]); assertEquals(false, doc["rebalanced"])
        assertTrue(doc["createdAt"] === HousekeepingServerTime); assertTrue(doc["updatedAt"] === HousekeepingServerTime)
        listOf("startedAt", "completedAt", "completedBy", "completedByName").forEach {
            assertTrue("$it present", doc.containsKey(it)); assertNull(doc[it])
        }
        assertEquals(false, doc["isDeleted"])
        // a payload the tolerant parser reads back
        val task = parseHousekeepingTask(id, doc.mapValues { if (it.value === HousekeepingServerTime) null else it.value })
        assertEquals(TaskType.CHECKOUT_CLEANING, task.type); assertEquals(3.0, task.weight, 0.0)
    }

    @Test fun createManualTaskSetsCleanlinessAuditsAndNotifies() = runTest {
        val r = Rig()
        val id = r.service.createManualTask("r101", "101", TaskType.CHECKOUT_CLEANING, TaskPriority.LOW, null, "hk1", "Hana", actor = TEST_ACTOR)
        assertEquals(listOf("clean:r101:checkout_cleaning_due"), r.rooms.calls)
        assertEquals("housekeeping_task_created", r.audit.entries.single().action)
        assertEquals(id, r.audit.entries.single().documentId)
        val note = r.notifier.calls.single()
        assertEquals(NotificationType.HOUSEKEEPING_TASK_SCHEDULED, note.type); assertEquals(listOf("hk1"), note.forUserIds)

        val r2 = Rig()
        r2.service.createManualTask("r102", "102", TaskType.MANUAL, TaskPriority.MEDIUM, null, null, null, actor = TEST_ACTOR)
        assertEquals(listOf("clean:r102:daily_cleaning_due"), r2.rooms.calls)
        assertTrue("nobody to tell when unassigned", r2.notifier.calls.isEmpty())
    }

    @Test fun createManualTaskSurvivesAuditNotifyAndCleanlinessFailures() = runTest {
        val r = Rig()
        r.audit.fail = true; r.notifier.fail = true; r.rooms.failCleanliness = IllegalStateException("rtdb down")
        val id = r.service.createManualTask("r101", "101", TaskType.MANUAL, TaskPriority.MEDIUM, null, "hk1", "Hana", actor = TEST_ACTOR)
        assertTrue(r.store.docs.getValue("housekeeping_tasks").containsKey(id))
    }

    // ---- reassignTask / startTask / skipTask ----

    @Test fun reassignTaskUpdatesOwnerAuditsAndNotifiesNewOwner() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "roomNumber" to "101", "priority" to "high", "instructions" to "Be quick", "assignedTo" to "hk1")
        r.service.reassignTask("t1", "hk2", "Hugo", TEST_ACTOR)
        val doc = r.store.docs.getValue("housekeeping_tasks").getValue("t1")
        assertEquals("hk2", doc["assignedTo"]); assertEquals("Hugo", doc["assignedToName"])
        assertEquals("sup1", doc["assignedBy"]); assertEquals("Sam Super", doc["assignedByName"])
        assertTrue(doc["updatedAt"] === HousekeepingServerTime)
        assertEquals("housekeeping_task_reassigned", r.audit.entries.single().action)
        assertEquals(listOf("hk2"), r.notifier.calls.single().forUserIds)
        assertTrue(r.notifier.calls.single().message.contains("101"))
    }

    @Test fun startTaskSetsStatusStartedAtAuditAndCleanliness() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "roomId" to "r101", "status" to "pending")
        r.service.startTask("t1", TEST_ACTOR)
        val doc = r.store.docs.getValue("housekeeping_tasks").getValue("t1")
        assertEquals("in_progress", doc["status"])
        assertTrue(doc["startedAt"] === HousekeepingServerTime); assertTrue(doc["updatedAt"] === HousekeepingServerTime)
        assertEquals("housekeeping_task_started", r.audit.entries.single().action)
        assertEquals(listOf("clean:r101:cleaning_in_progress"), r.rooms.calls)
    }

    @Test fun startTaskIgnoresAFailingCleanlinessUpdate() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "roomId" to "r101", "status" to "pending")
        r.rooms.failCleanliness = IllegalStateException("rtdb down")
        r.service.startTask("t1", TEST_ACTOR)
        assertEquals("in_progress", r.store.docs.getValue("housekeeping_tasks").getValue("t1")["status"])
    }

    @Test fun skipTaskStoresReasonOrNull() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "pending")
        r.service.skipTask("t1", "  Guest asked for privacy ", TEST_ACTOR)
        val doc = r.store.docs.getValue("housekeeping_tasks").getValue("t1")
        assertEquals("skipped", doc["status"]); assertEquals("Guest asked for privacy", doc["skipReason"])
        r.service.skipTask("t1", "   ", TEST_ACTOR)
        assertNull(doc["skipReason"])
        assertEquals("housekeeping_task_skipped", r.audit.entries.first().action)
    }

    // ---- completeTask ----

    @Test fun completeTaskOnOccupiedRoomOnlySetsCleanliness() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.store.put("rooms", "r101", "currentBookingId" to "b1")
        r.service.completeTask("t1", r101, TEST_ACTOR)
        assertEquals(listOf("clean:r101:clean"), r.rooms.calls) // never a status call
        val doc = r.store.docs.getValue("housekeeping_tasks").getValue("t1")
        assertEquals("completed", doc["status"]); assertEquals("sup1", doc["completedBy"]); assertEquals("Sam Super", doc["completedByName"])
        assertTrue(doc["completedAt"] === HousekeepingServerTime)
        val audit = r.audit.entries.single()
        assertEquals("housekeeping_task_completed", audit.action)
        assertEquals(mapOf("status" to "completed", "roomRemainedOccupied" to true), audit.newValue)
        assertEquals(NotificationType.HOUSEKEEPING_TASK_DONE, r.notifier.calls.single().type)
    }

    @Test fun completeTaskOnACleaningRoomThatStillHasAGuestPutsItBackToOccupied() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.store.put("rooms", "r101", "currentBookingId" to "b1", "status" to "cleaning")
        r.service.completeTask("t1", r101, TEST_ACTOR)
        // never Available while a guest is in; Occupied replaces the stuck "Cleaning"
        assertEquals(listOf("status:r101:occupied", "clean:r101:clean"), r.rooms.calls)
    }

    @Test fun completeTaskTreatsABlankBookingIdAsVacated() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.store.put("rooms", "r101", "currentBookingId" to "", "status" to "cleaning")
        r.service.completeTask("t1", r101, TEST_ACTOR)
        assertEquals(listOf("status:r101:available", "clean:r101:clean"), r.rooms.calls)
    }

    @Test fun completeTaskOnVacatedRoomMakesItAvailableThenClean() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.store.put("rooms", "r101", "currentBookingId" to null)
        r.service.completeTask("t1", r101, TEST_ACTOR)
        assertEquals(listOf("status:r101:available", "clean:r101:clean"), r.rooms.calls)
        assertEquals(mapOf("cleaningReminderLastSentAt" to null), r.rooms.cleanlinessExtras.single())
        assertEquals(false, r.audit.entries.single().newValue?.get("roomRemainedOccupied"))
    }

    @Test fun completeTaskTreatsAMissingRoomAsVacated() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.service.completeTask("t1", r101, TEST_ACTOR)
        assertEquals(listOf("status:r101:available", "clean:r101:clean"), r.rooms.calls)
    }

    @Test fun completeTaskWhenStatusUpdateFailsStillMarksCleanAndRethrows() = runTest {
        val r = Rig()
        r.store.put("housekeeping_tasks", "t1", "status" to "in_progress")
        r.store.put("rooms", "r101")
        r.rooms.failStatus = IllegalStateException("cannot free this room")
        try {
            r.service.completeTask("t1", r101, TEST_ACTOR)
            fail("should rethrow")
        } catch (e: IllegalStateException) {
            assertEquals("cannot free this room", e.message)
        }
        assertEquals(listOf("status:r101:available", "clean:r101:clean"), r.rooms.calls)
        assertTrue("no audit when it failed", r.audit.entries.isEmpty())
    }

    // ---- assignRooms ----

    @Test fun assignRoomsWithNoRoomsFails() = runTest {
        val r = Rig()
        try {
            r.service.assignRooms("hk1", "Hana", emptyList(), day("2026-03-05"), null, null, TEST_ACTOR)
            fail("should fail")
        } catch (e: HousekeepingException) {
            assertEquals("Select at least one room to assign.", e.message)
        }
        assertEquals(0, r.store.commits)
    }

    @Test fun assignRoomsCreatesGroupAndOneMirrorPerRoom() = runTest {
        val r = Rig()
        val groupId = r.service.assignRooms("hk1", "Hana", listOf(r101, r102), day("2026-03-05"), day("2026-04-05"), "  VIP wing ", TEST_ACTOR)
        assertEquals(1, r.store.commits)
        val group = r.store.docs.getValue("room_assignment_groups").getValue(groupId)
        assertEquals("hk1", group["housekeeperId"]); assertEquals("Hana", group["housekeeperName"])
        assertEquals(listOf("r101", "r102"), group["roomIds"]); assertEquals(listOf("101", "102"), group["roomNumbers"])
        assertEquals(midnightUtc("2026-03-05"), group["startDate"]); assertEquals(midnightUtc("2026-04-05"), group["endDate"])
        assertEquals("VIP wing", group["notes"]); assertEquals("active", group["status"])
        assertEquals("sup1", group["createdBy"]); assertEquals("Sam Super", group["createdByName"])
        assertEquals(false, group["isDeleted"])
        for (room in listOf(r101, r102)) {
            val mirror = r.store.docs.getValue("room_assignments").getValue(room.id)
            assertEquals(room.id, mirror["roomId"]); assertEquals(room.number, mirror["roomNumber"])
            assertEquals("hk1", mirror["housekeeperId"]); assertEquals(groupId, mirror["groupId"]); assertEquals("active", mirror["status"])
            assertEquals(midnightUtc("2026-03-05"), mirror["startDate"])
        }
        assertEquals("rooms_assigned", r.audit.entries.single().action)
        assertEquals(mapOf("housekeeperId" to "hk1", "roomIds" to listOf("r101", "r102")), r.audit.entries.single().newValue)
        val note = r.notifier.calls.single()
        assertEquals(NotificationType.ROOM_ASSIGNED, note.type); assertEquals(listOf("hk1"), note.forUserIds)
        assertTrue(note.message.contains("2026-03-05") && note.message.contains("2026-04-05"))
        assertEquals(listOf("r101", "r102"), r.service.assignedRoomIds("hk1").sorted())
    }

    @Test fun ongoingAssignmentHasNullEndDate() = runTest {
        val r = Rig()
        val id = r.service.assignRooms("hk1", "Hana", listOf(r101), day("2026-03-05"), null, null, TEST_ACTOR)
        assertNull(r.store.docs.getValue("room_assignment_groups").getValue(id)["endDate"])
        assertNull(r.store.docs.getValue("room_assignment_groups").getValue(id)["notes"])
        assertNull(r.store.docs.getValue("room_assignments").getValue("r101")["endDate"])
    }

    @Test fun movingARoomShrinksTheOldGroupByIndexAndNotifiesThePreviousOwner() = runTest {
        val r = Rig()
        val groupA = r.service.assignRooms("hkA", "Ann", listOf(r101, r102, r103), day("2026-03-05"), null, null, TEST_ACTOR)
        r.notifier.calls.clear()
        val groupB = r.service.assignRooms("hkB", "Bob", listOf(r102), day("2026-03-06"), null, null, TEST_ACTOR)

        val a = r.store.docs.getValue("room_assignment_groups").getValue(groupA)
        assertEquals(listOf("r101", "r103"), a["roomIds"]); assertEquals(listOf("101", "103"), a["roomNumbers"])
        assertEquals("active", a["status"])
        assertTrue(a["updatedAt"] === HousekeepingServerTime); assertEquals("sup1", a["updatedBy"])
        assertEquals(groupB, r.store.docs.getValue("room_assignments").getValue("r102")["groupId"])
        assertEquals("hkB", r.store.docs.getValue("room_assignments").getValue("r102")["housekeeperId"])
        assertEquals(groupA, r.store.docs.getValue("room_assignments").getValue("r101")["groupId"])

        assertEquals(2, r.notifier.calls.size)
        val reassigned = r.notifier.calls.single { it.type == NotificationType.ROOM_REASSIGNED }
        assertEquals(listOf("hkA"), reassigned.forUserIds); assertTrue(reassigned.message.contains("102")); assertTrue(reassigned.message.contains("Bob"))
        assertEquals(listOf("r101", "r103"), r.service.assignedRoomIds("hkA").sorted())
        assertEquals(listOf("r102"), r.service.assignedRoomIds("hkB"))
    }

    @Test fun movingEveryRoomEndsTheOldGroup() = runTest {
        val r = Rig()
        val groupA = r.service.assignRooms("hkA", "Ann", listOf(r101, r102), day("2026-03-05"), null, null, TEST_ACTOR)
        r.service.assignRooms("hkB", "Bob", listOf(r101, r102, r103), day("2026-03-06"), null, null, TEST_ACTOR)
        val a = r.store.docs.getValue("room_assignment_groups").getValue(groupA)
        assertEquals("ended", a["status"]); assertEquals(emptyList<String>(), a["roomIds"]); assertEquals(emptyList<String>(), a["roomNumbers"])
        assertTrue(a["endedAt"] === HousekeepingServerTime); assertEquals("sup1", a["endedBy"])
        assertTrue(r.service.assignedRoomIds("hkA").isEmpty())
    }

    @Test fun sameOwnerRoomsAreRepointedWithoutAReassignedNotification() = runTest {
        val r = Rig()
        val first = r.service.assignRooms("hkA", "Ann", listOf(r101, r102), day("2026-03-05"), null, null, TEST_ACTOR)
        r.notifier.calls.clear()
        val second = r.service.assignRooms("hkA", "Ann", listOf(r101, r102), day("2026-03-10"), null, null, TEST_ACTOR)
        assertEquals("ended", r.store.docs.getValue("room_assignment_groups").getValue(first)["status"])
        assertEquals(second, r.store.docs.getValue("room_assignments").getValue("r101")["groupId"])
        assertEquals(second, r.store.docs.getValue("room_assignments").getValue("r102")["groupId"])
        assertTrue(r.notifier.calls.none { it.type == NotificationType.ROOM_REASSIGNED })
        assertEquals(1, r.notifier.calls.count { it.type == NotificationType.ROOM_ASSIGNED })
    }

    @Test fun assignRoomsStillSucceedsWhenAuditAndNotificationsFail() = runTest {
        val r = Rig()
        r.audit.fail = true; r.notifier.fail = true
        val id = r.service.assignRooms("hk1", "Hana", listOf(r101), day("2026-03-05"), null, null, TEST_ACTOR)
        assertTrue(r.store.docs.getValue("room_assignment_groups").containsKey(id))
    }

    // ---- endAssignmentGroup ----

    @Test fun endingAMissingGroupFails() = runTest {
        val r = Rig()
        try {
            r.service.endAssignmentGroup("nope", TEST_ACTOR)
            fail("should fail")
        } catch (e: HousekeepingException) {
            assertEquals("Assignment not found.", e.message)
        }
    }

    @Test fun endingAGroupRemovesItsMirrorsAndTellsTheHousekeeper() = runTest {
        val r = Rig()
        val groupA = r.service.assignRooms("hkA", "Ann", listOf(r101, r102), day("2026-03-05"), null, null, TEST_ACTOR)
        val other = r.service.assignRooms("hkB", "Bob", listOf(r103), day("2026-03-05"), null, null, TEST_ACTOR)
        r.notifier.calls.clear(); r.audit.entries.clear()
        r.service.endAssignmentGroup(groupA, TEST_ACTOR)
        val a = r.store.docs.getValue("room_assignment_groups").getValue(groupA)
        assertEquals("ended", a["status"]); assertTrue(a["endedAt"] === HousekeepingServerTime); assertEquals("sup1", a["endedBy"])
        assertFalse(r.store.docs.getValue("room_assignments").containsKey("r101"))
        assertFalse(r.store.docs.getValue("room_assignments").containsKey("r102"))
        assertTrue("other group untouched", r.store.docs.getValue("room_assignments").containsKey("r103"))
        assertEquals("active", r.store.docs.getValue("room_assignment_groups").getValue(other)["status"])
        assertEquals("room_assignment_ended", r.audit.entries.single().action)
        val note = r.notifier.calls.single()
        assertEquals(NotificationType.ROOM_ASSIGNMENT_ENDED, note.type); assertEquals(listOf("hkA"), note.forUserIds)
    }

    // ---- updateAssignmentGroup ----

    @Test fun updatingAGroupGivesTheGroupAndEveryMirrorTheSameDates() = runTest {
        val r = Rig()
        val id = r.service.assignRooms("hk1", "Hana", listOf(r101, r102), day("2026-03-05"), day("2026-04-05"), null, TEST_ACTOR)
        r.service.updateAssignmentGroup(id, AssignmentGroupUpdate(startDate = day("2026-03-07"), changeEndDate = true, endDate = day("2026-05-01"), notes = " New note "), TEST_ACTOR)
        val group = r.store.docs.getValue("room_assignment_groups").getValue(id)
        assertEquals(midnightUtc("2026-03-07"), group["startDate"]); assertEquals(midnightUtc("2026-05-01"), group["endDate"])
        assertEquals("New note", group["notes"]); assertEquals("sup1", group["updatedBy"]); assertTrue(group["updatedAt"] === HousekeepingServerTime)
        for (roomId in listOf("r101", "r102")) {
            val mirror = r.store.docs.getValue("room_assignments").getValue(roomId)
            assertEquals(group["startDate"], mirror["startDate"]); assertEquals(group["endDate"], mirror["endDate"])
        }
        assertEquals("room_assignment_updated", r.audit.entries.last().action)
    }

    @Test fun makingAGroupOngoingClearsTheEndDateEverywhereAndKeepsWhatWasNotProvided() = runTest {
        val r = Rig()
        val id = r.service.assignRooms("hk1", "Hana", listOf(r101), day("2026-03-05"), day("2026-04-05"), "keep me", TEST_ACTOR)
        r.service.updateAssignmentGroup(id, AssignmentGroupUpdate(changeEndDate = true, endDate = null), TEST_ACTOR)
        val group = r.store.docs.getValue("room_assignment_groups").getValue(id)
        assertNull(group["endDate"]); assertEquals(midnightUtc("2026-03-05"), group["startDate"]); assertEquals("keep me", group["notes"])
        val mirror = r.store.docs.getValue("room_assignments").getValue("r101")
        assertNull(mirror["endDate"]); assertEquals(midnightUtc("2026-03-05"), mirror["startDate"])
    }

    @Test fun updatingAMissingGroupFails() = runTest {
        val r = Rig()
        try {
            r.service.updateAssignmentGroup("nope", AssignmentGroupUpdate(notes = "x"), TEST_ACTOR)
            fail("should fail")
        } catch (e: HousekeepingException) {
            assertEquals("Assignment not found.", e.message)
        }
    }

    // ---- calendar days ----

    @Test fun calendarDaysAreStoredAsUtcMidnightWhateverTheDeviceZone() {
        assertEquals(Instant.parse("2026-03-05T00:00:00Z"), calendarDayInstant(day("2026-03-05")))
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), calendarDayInstant(day("2026-12-31")))
    }
}
