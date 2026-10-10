package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MyHousekeepingLogicTest {

    // ---- queue ------------------------------------------------------------------------------

    @Test fun queueIsSortedUrgentHighMediumLow() {
        val tasks = listOf(
            myTask("low", priority = "low"), myTask("urgent", priority = "urgent"),
            myTask("medium", priority = "medium"), myTask("high", priority = "high")
        )
        assertEquals(listOf("urgent", "high", "medium", "low"), buildMyQueue(tasks, "me").map { it.id })
    }

    @Test fun queueKeepsOnlyMyPendingAndInProgressTasks() {
        val tasks = listOf(
            myTask("mine-pending", status = "pending"),
            myTask("mine-progress", status = "in_progress"),
            myTask("mine-done", status = "completed"),
            myTask("mine-skipped", status = "skipped"),
            myTask("theirs", assignedTo = "other"),
            myTask("nobody", assignedTo = null),
            myTask("deleted", isDeleted = true)
        )
        assertEquals(setOf("mine-pending", "mine-progress"), buildMyQueue(tasks, "me").map { it.id }.toSet())
    }

    @Test fun samePriorityGoesEarlierScheduleFirst() {
        val tasks = listOf(
            myTask("late", priority = "high", scheduledFor = Instant.parse("2026-10-10T12:00:00Z")),
            myTask("early", priority = "high", scheduledFor = Instant.parse("2026-10-10T08:00:00Z"))
        )
        assertEquals(listOf("early", "late"), buildMyQueue(tasks, "me").map { it.id })
    }

    // ---- rooms and chunking -----------------------------------------------------------------

    @Test fun activeRoomIdsAreMineActiveAndDistinct() {
        val list = listOf(
            myAssignment("r1"), myAssignment("r1"), myAssignment("r2", housekeeperId = "other"),
            myAssignment("r3", status = "ended"), myAssignment("r4")
        )
        assertEquals(listOf("r1", "r4"), myActiveRoomIds(list, "me"))
    }

    @Test fun roomIdsAreChunkedInGroupsOfTen() {
        val ids = (1..25).map { "r$it" }
        assertEquals(listOf(10, 10, 5), chunkRoomIds(ids).map { it.size })
        assertEquals(ids, chunkRoomIds(ids).flatten())
        assertTrue(chunkRoomIds(emptyList()).isEmpty())
        assertEquals(listOf(10), chunkRoomIds((1..10).map { "r$it" }).map { it.size })
    }

    @Test fun eachChunkGetsItsOwnListenerAndResultsAreMerged() = runTest {
        val asked = mutableListOf<List<String>>()
        val result = observeRoomsInChunks((1..23).map { "r$it" }) { chunk ->
            asked += chunk
            flowOf(Resource.Success(chunk.map { myRoom(it) }))
        }.first()
        assertEquals(listOf(10, 10, 3), asked.map { it.size })
        assertTrue(asked.all { it.size <= 10 })
        assertEquals(23, (result as Resource.Success<List<com.westly.nbms.features.rooms.Room>>).data.size)
    }

    @Test fun noAssignedRoomsMeansNoRoomListenerAtAll() = runTest {
        var listeners = 0
        val result = observeRoomsInChunks(emptyList()) { listeners++; flowOf(Resource.Loading) }.first()
        assertEquals(0, listeners)
        assertEquals(emptyList<Any>(), (result as Resource.Success<*>).data)
    }

    @Test fun oneChunkErrorFailsTheWholeRoomList() = runTest {
        val result = observeRoomsInChunks((1..12).map { "r$it" }) { chunk ->
            if ("r11" in chunk) flowOf(Resource.Error("boom")) else flowOf(Resource.Success(emptyList()))
        }.first()
        assertTrue(result is Resource.Error)
    }

    // ---- texts ------------------------------------------------------------------------------

    @Test fun subtitleCountsTasksAndRooms() {
        assertEquals("2 tasks in your queue · 1 room assigned to you", myHeaderSubtitle(2, 1))
        assertEquals("1 task in your queue · 3 rooms assigned to you", myHeaderSubtitle(1, 3))
        assertEquals("0 tasks in your queue · 0 rooms assigned to you", myHeaderSubtitle(0, 0))
    }

    @Test fun tabLabelsCarryTheCounts() {
        assertEquals("My Queue (4)", myQueueTabLabel(4))
        assertEquals("My Rooms (2)", myRoomsTabLabel(2))
    }

    @Test fun historyRowShowsTypeTimeAndWho() {
        val task = myTask(
            "h1", type = "checkout_cleaning", status = "completed",
            completedAt = Instant.parse("2026-03-05T09:30:00Z"), completedByName = "Hana"
        )
        // Africa/Lagos is UTC+1, so 09:30Z is 10:30 AM.
        assertEquals("Check-out Cleaning — Mar 5, 10:30 AM · Hana", historyRowText(task, "Africa/Lagos"))
        assertEquals(
            "Check-out Cleaning — Mar 5, 10:30 AM",
            historyRowText(task.copy(completedByName = null), "Africa/Lagos")
        )
    }

    @Test fun markCleanToastDependsOnAGuestStillCheckedIn() {
        assertEquals(
            "Room Marked Clean" to "Room 101 is clean. Guest is still checked in, so it stays Occupied.",
            markCleanToast(myRoom("r1", "101", currentBookingId = "b1"))
        )
        assertEquals("Room Marked Clean" to "Room 101 is now available.", markCleanToast(myRoom("r1", "101")))
        assertEquals("Room Marked Clean" to "Room 101 is now available.", markCleanToast(myRoom("r1", "101", currentBookingId = "")))
    }

    // ---- screen state -----------------------------------------------------------------------

    @Test fun stateShowsSortedRoomsAndCounts() {
        val state = buildMyState(
            "me",
            Resource.Success(listOf(myTask("a", priority = "low"), myTask("b", priority = "urgent"))),
            Resource.Success(listOf(myAssignment("r10"), myAssignment("r2"))),
            Resource.Success(listOf(myRoom("r10", "10"), myRoom("r2", "2")))
        )
        assertEquals(listOf("b", "a"), state.queue.map { it.id })
        assertEquals(listOf("2", "10"), state.rooms.map { it.number })
        assertEquals(2, state.assignedRoomCount)
        assertEquals("2 tasks in your queue · 2 rooms assigned to you", state.subtitle)
        assertEquals("b", state.pendingTaskFor("r-b")?.id)
    }

    @Test fun errorStateCarriesTheDetail() {
        val state = buildMyState(
            "me", Resource.Error("x", IllegalStateException("permission-denied")),
            Resource.Success(emptyList()), Resource.Success(emptyList())
        )
        assertTrue(state.failed)
        assertEquals("permission-denied", state.errorDetail)
    }
}
