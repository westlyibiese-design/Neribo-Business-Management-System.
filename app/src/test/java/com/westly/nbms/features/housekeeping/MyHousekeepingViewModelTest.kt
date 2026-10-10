package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MyHousekeepingViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig(role: Role = Role.HOUSEKEEPING, usesPin: Boolean = true) {
        val source = MyHousekeepingFakeSource()
        val service = MyHousekeepingFakeService()
        val roomLogic = FakeHousekeepingRoomLogic()
        val audit = OverviewFakeAudit()
        val notifier = FakeHousekeepingNotifier()
        val toast = ToastController()
        val session = MyHousekeepingFakeSession(role, usesPin)
        val events = mutableListOf<ToastEvent>()
        val vm = MyHousekeepingViewModel(source, service, roomLogic, audit, notifier, toast, session)
    }

    private fun TestScope.listen(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    // ---- live data --------------------------------------------------------------------------

    @Test fun stateReadsMyQueueAndAsksOnlyForMyRoomsById() = runTest {
        val rig = Rig()
        rig.source.queue.value = Resource.Success(listOf(myTask("a", priority = "low"), myTask("b", priority = "urgent"), myTask("x", assignedTo = "other")))
        rig.source.assignments.value = Resource.Success(listOf(myAssignment("r1"), myAssignment("r2")))
        rig.source.rooms.value = Resource.Success(listOf(myRoom("r1", "101"), myRoom("r2", "102")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.state.collect {} }

        val state = rig.vm.state.value
        assertEquals(listOf("me"), rig.source.queueUids.distinct())
        assertEquals(listOf("b", "a"), state.queue.map { it.id })
        assertEquals(listOf(listOf("r1", "r2")), rig.source.roomIdRequests)
        assertEquals(2, state.assignedRoomCount)
        assertFalse(state.queueLoading)
        assertFalse(state.roomsLoading)
    }

    // ---- Start and Complete -----------------------------------------------------------------

    @Test fun startCallsTheServiceAndNothingElse() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.start(myTask("t1", roomNumber = "101"))
        assertEquals("t1", rig.service.started.single().first)
        assertEquals("me", rig.service.started.single().second.id)
        assertEquals(0, rig.session.signOutCalls)
        assertTrue(rig.events.isEmpty())
        assertTrue(rig.vm.busyTaskIds.value.isEmpty())
    }

    @Test fun completeCallsTheServiceAndToasts() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.vm.complete(myTask("t1", roomId = "r1", roomNumber = "101"))
        val done = rig.service.completed.single()
        assertEquals("t1", done.taskId)
        assertEquals("r1", done.room.id)
        assertEquals("101", done.room.number)
        assertEquals("Task Complete", rig.events.single().title)
        assertEquals("Room 101 marked done.", rig.events.single().message)
        assertEquals(ToastType.Success, rig.events.single().type)
    }

    @Test fun failedStartOrCompleteToastsErrorAndClearsBusy() = runTest {
        val rig = Rig()
        listen(rig)
        rig.service.failStart = IllegalStateException("Task already started.")
        rig.vm.start(myTask("t1"))
        assertEquals("Error", rig.events.last().title)
        assertEquals("Task already started.", rig.events.last().message)
        assertEquals(ToastType.Error, rig.events.last().type)
        assertTrue(rig.vm.busyTaskIds.value.isEmpty())

        rig.service.failComplete = IllegalStateException("nope")
        rig.vm.complete(myTask("t2"))
        assertEquals("nope", rig.events.last().message)
        assertEquals(0, rig.session.signOutCalls)
        assertFalse(rig.vm.pinEnding.value)
        assertTrue(rig.vm.busyTaskIds.value.isEmpty())
    }

    // ---- Mark Clean -------------------------------------------------------------------------

    @Test fun markCleanCreatesACleaningMediumTaskForMeThenCompletesIt() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.vm.markClean(myRoom("r1", "101"))
        assertEquals(listOf("create", "complete"), rig.service.order)
        val created = rig.service.created.single()
        assertEquals(TaskType.CLEANING, created.type)
        assertEquals(TaskPriority.MEDIUM, created.priority)
        assertEquals("me", created.assignedTo)
        assertEquals("r1", created.roomId)
        assertEquals("task-1", rig.service.completed.single().taskId)
    }

    @Test fun markCleanToastsAvailableForAVacantRoom() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.vm.markClean(myRoom("r1", "101"))
        assertEquals("Room Marked Clean", rig.events.single().title)
        assertEquals("Room 101 is now available.", rig.events.single().message)
    }

    @Test fun markCleanToastsOccupiedWhenAGuestIsStillCheckedIn() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.vm.markClean(myRoom("r1", "101", currentBookingId = "b1"))
        assertEquals("Room Marked Clean", rig.events.single().title)
        assertEquals("Room 101 is clean. Guest is still checked in, so it stays Occupied.", rig.events.single().message)
    }

    @Test fun failedMarkCleanToastsErrorAndKeepsTheSession() = runTest {
        val rig = Rig()
        listen(rig)
        rig.service.failComplete = IllegalStateException("Could not finish.")
        rig.vm.markClean(myRoom("r1", "101"))
        assertEquals("Error", rig.events.single().title)
        assertEquals("Could not finish.", rig.events.single().message)
        assertFalse(rig.vm.pinEnding.value)
        assertTrue(rig.vm.busyRoomIds.value.isEmpty())
    }

    // ---- Maintenance flag -------------------------------------------------------------------

    @Test fun maintenanceFlagSetsStatusAuditsNotifiesAndToasts() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.vm.flagMaintenance(myRoom("r1", "101", status = "cleaning"))

        assertEquals(listOf("status:r1:maintenance"), rig.roomLogic.calls)
        val entry = rig.audit.entries.single()
        assertEquals("room_maintenance", entry.action)
        assertEquals("rooms", entry.collection)
        assertEquals("r1", entry.documentId)
        assertEquals(mapOf("status" to "maintenance"), entry.new)
        val note = rig.notifier.calls.single()
        assertTrue(note.message.contains("Flagged by housekeeping"))
        assertTrue(note.message.contains("Room 101"))
        assertTrue(note.message.contains("Hana Housekeeper"))
        assertEquals("Room Sent to Maintenance", rig.events.single().title)
        assertEquals("Room 101 flagged for maintenance.", rig.events.single().message)
    }

    @Test fun failedMaintenanceFlagToastsErrorWithoutAuditOrNotice() = runTest {
        val rig = Rig()
        listen(rig)
        rig.roomLogic.failStatus = IllegalStateException("Cannot change status.")
        rig.vm.flagMaintenance(myRoom("r1", "101"))
        assertEquals("Error", rig.events.single().title)
        assertEquals("Cannot change status.", rig.events.single().message)
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
        assertFalse(rig.vm.pinEnding.value)
    }

    @Test fun aFailedNotificationDoesNotUndoTheFlag() = runTest {
        val rig = Rig(usesPin = false)
        listen(rig)
        rig.notifier.fail = true
        rig.vm.flagMaintenance(myRoom("r1", "101"))
        assertEquals("Room Sent to Maintenance", rig.events.single().title)
    }

    // ---- PIN session end --------------------------------------------------------------------

    @Test fun pinHousekeeperIsSignedOutAfterTwoAndAHalfSecondsFollowingEachWorkAction() = runTest {
        // Complete
        val a = Rig(usesPin = true)
        a.vm.complete(myTask("t1"))
        assertTrue(a.vm.pinEnding.value)
        testScheduler.advanceTimeBy(2_499); testScheduler.runCurrent()
        assertEquals(0, a.session.signOutCalls)
        testScheduler.advanceTimeBy(2); testScheduler.runCurrent()
        assertEquals(1, a.session.signOutCalls)
        assertFalse(a.vm.pinEnding.value)

        // Mark Clean
        val b = Rig(usesPin = true)
        b.vm.markClean(myRoom("r1", "101"))
        testScheduler.advanceTimeBy(2_500); testScheduler.runCurrent()
        assertEquals(1, b.session.signOutCalls)

        // Maintenance flag
        val c = Rig(usesPin = true)
        c.vm.flagMaintenance(myRoom("r1", "101"))
        testScheduler.advanceTimeBy(2_500); testScheduler.runCurrent()
        assertEquals(1, c.session.signOutCalls)
    }

    @Test fun startNeverEndsASession() = runTest {
        val rig = Rig(usesPin = true)
        rig.vm.start(myTask("t1"))
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(0, rig.session.signOutCalls)
        assertFalse(rig.vm.pinEnding.value)
    }

    @Test fun aSignedInHousekeeperWithoutPinKeepsTheSession() = runTest {
        val rig = Rig(usesPin = false)
        rig.vm.complete(myTask("t1"))
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(0, rig.session.signOutCalls)
        assertFalse(rig.vm.pinEnding.value)
    }

    @Test fun otherRolesNeverGetTheSessionEnd() = runTest {
        listOf(Role.MANAGER, Role.OPERATIONS_MANAGER, Role.SUPER_ADMIN).forEach { role ->
            val rig = Rig(role = role, usesPin = true)
            rig.vm.markClean(myRoom("r1", "101"))
            testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
            assertEquals("$role", 0, rig.session.signOutCalls)
            assertFalse(rig.vm.pinEnding.value)
        }
    }

    // ---- Cleaning history -------------------------------------------------------------------

    @Test fun historyIsNotQueriedUntilARoomIsExpanded() = runTest {
        val rig = Rig()
        rig.source.queue.value = Resource.Success(emptyList())
        rig.source.assignments.value = Resource.Success(listOf(myAssignment("r1")))
        rig.source.rooms.value = Resource.Success(listOf(myRoom("r1", "101")))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.state.collect {} }
        assertTrue(rig.source.historyRequests.isEmpty())
        assertTrue(rig.vm.history.value.isEmpty())

        rig.vm.toggleHistory("r1")
        assertEquals(listOf("r1" to 5), rig.source.historyRequests)
    }

    @Test fun expandingLoadsLastFiveAndShowsRowsOrEmpty() = runTest {
        val rig = Rig()
        rig.source.history["r1"] = listOf(
            myTask("h1", status = "completed", completedAt = Instant.parse("2026-03-05T09:30:00Z"), completedByName = "Hana")
        )
        rig.vm.toggleHistory("r1")
        assertTrue("r1" in rig.vm.expandedRoomIds.value)
        val loaded = rig.vm.history.value.getValue("r1") as MyHistoryState.Loaded
        assertEquals(listOf("h1"), loaded.rows.map { it.id })

        rig.vm.toggleHistory("r2")
        assertEquals(emptyList<HousekeepingTask>(), (rig.vm.history.value.getValue("r2") as MyHistoryState.Loaded).rows)
    }

    @Test fun collapsingDoesNotQueryAgainAndReopeningFetchesFresh() = runTest {
        val rig = Rig()
        rig.vm.toggleHistory("r1")
        rig.vm.toggleHistory("r1")
        assertFalse("r1" in rig.vm.expandedRoomIds.value)
        assertEquals(1, rig.source.historyRequests.size)
        rig.vm.toggleHistory("r1")
        assertEquals(2, rig.source.historyRequests.size)
    }

    @Test fun aFailedHistoryQueryShowsTheMessage() = runTest {
        val rig = Rig()
        rig.source.historyError = IllegalStateException("index missing")
        rig.vm.toggleHistory("r1")
        assertEquals("index missing", (rig.vm.history.value.getValue("r1") as MyHistoryState.Failed).message)
    }
}
