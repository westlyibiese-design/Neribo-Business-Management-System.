package com.westly.nbms.features.rooms

import com.westly.nbms.core.design.BadgeTone
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A fake database for the room rules. */
private class FakeStore : RoomLogicStore {
    val rooms = LinkedHashMap<String, Room>()
    val locks = mutableListOf<BookingLock>()
    val roomUpdates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val liveSets = mutableListOf<Pair<String, Map<String, Any?>>>()
    val liveUpdates = mutableListOf<Pair<String, Map<String, Any?>>>()
    var liveMode = LiveMode.OK
    var failRoomUpdate = false
    var lastLockQueryStatuses: List<String> = emptyList()

    enum class LiveMode { OK, HANG, FAIL }

    override suspend fun activeBookingLocks(roomId: String, statuses: List<String>): List<BookingLock> {
        lastLockQueryStatuses = statuses
        return locks.filter { it.roomId == roomId && it.status in statuses }
    }

    override suspend fun allRooms(): List<Room> = rooms.values.toList()
    override suspend fun getRoom(roomId: String): Room? = rooms[roomId]

    override suspend fun updateRoom(roomId: String, fields: Map<String, Any?>) {
        if (failRoomUpdate) throw IllegalStateException("Firestore is down")
        roomUpdates += roomId to fields
    }

    override suspend fun setLiveStatus(roomId: String, fields: Map<String, Any?>) {
        when (liveMode) {
            LiveMode.OK -> liveSets += roomId to fields
            LiveMode.HANG -> awaitCancellation()
            LiveMode.FAIL -> throw RuntimeException("Realtime Database is down")
        }
    }

    override suspend fun updateLiveStatus(roomId: String, fields: Map<String, Any?>) {
        when (liveMode) {
            LiveMode.OK -> liveUpdates += roomId to fields
            LiveMode.HANG -> awaitCancellation()
            LiveMode.FAIL -> throw RuntimeException("Realtime Database is down")
        }
    }
}

class RoomLogicTest {

    private fun t(text: String) = Instant.parse("2026-03-${text}T12:00:00Z")

    private val now = Instant.parse("2026-10-08T10:00:00Z")

    private fun logic(store: FakeStore): RoomLogicImpl =
        RoomLogicImpl(store).also { it.clock = { now } }

    private fun lock(id: String, room: String, inDay: String, outDay: String, status: String = "confirmed") =
        BookingLock(id, room, t(inDay), t(outDay), status)

    // ---- datesOverlap ----

    @Test
    fun overlappingStaysOverlap() {
        val l = logic(FakeStore())
        assertTrue(l.datesOverlap(t("10"), t("15"), t("12"), t("18")))
        assertTrue(l.datesOverlap(t("12"), t("18"), t("10"), t("15")))
        assertTrue(l.datesOverlap(t("10"), t("20"), t("12"), t("14"))) // one inside the other
        assertTrue(l.datesOverlap(t("10"), t("15"), t("10"), t("15"))) // identical
    }

    @Test
    fun backToBackStaysDoNotOverlap() {
        val l = logic(FakeStore())
        // Check-out day of one guest is the check-in day of the next guest.
        assertFalse(l.datesOverlap(t("10"), t("15"), t("15"), t("18")))
        assertFalse(l.datesOverlap(t("15"), t("18"), t("10"), t("15")))
        assertFalse(l.datesOverlap(t("01"), t("05"), t("10"), t("15")))
    }

    // ---- detectConflict ----

    @Test
    fun conflictWhenAnotherBookingOverlaps() = runTest {
        val s = FakeStore().apply { locks += lock("b1", "r1", "10", "15") }
        assertTrue(logic(s).detectConflict("r1", t("12"), t("14")))
        assertEquals(listOf("confirmed", "checked_in", "pending"), s.lastLockQueryStatuses)
    }

    @Test
    fun noConflictWhenDatesTouchOnly() = runTest {
        val s = FakeStore().apply { locks += lock("b1", "r1", "10", "15") }
        assertFalse(logic(s).detectConflict("r1", t("15"), t("18")))
        assertFalse(logic(s).detectConflict("r1", t("05"), t("10")))
    }

    @Test
    fun excludedBookingIsIgnored() = runTest {
        val s = FakeStore().apply { locks += lock("b1", "r1", "10", "15") }
        assertFalse(logic(s).detectConflict("r1", t("12"), t("14"), excludeBookingId = "b1"))
        assertTrue(logic(s).detectConflict("r1", t("12"), t("14"), excludeBookingId = "other"))
    }

    @Test
    fun cancelledAndCheckedOutLocksDoNotBlock() = runTest {
        val s = FakeStore().apply {
            locks += lock("b1", "r1", "10", "15", "cancelled")
            locks += lock("b2", "r1", "10", "15", "checked_out")
            locks += lock("b3", "r1", "10", "15", "rejected")
        }
        assertFalse(logic(s).detectConflict("r1", t("12"), t("14")))
    }

    @Test
    fun everyBlockingStatusConflicts() = runTest {
        for (status in listOf("confirmed", "checked_in", "pending")) {
            val s = FakeStore().apply { locks += lock("b1", "r1", "10", "15", status) }
            assertTrue(status, logic(s).detectConflict("r1", t("12"), t("14")))
        }
    }

    @Test
    fun otherRoomsDoNotConflict() = runTest {
        val s = FakeStore().apply { locks += lock("b1", "r2", "10", "15") }
        assertFalse(logic(s).detectConflict("r1", t("12"), t("14")))
    }

    @Test
    fun lockWithoutDatesIsIgnored() = runTest {
        val s = FakeStore().apply { locks += BookingLock("b1", "r1", null, null, "confirmed") }
        assertFalse(logic(s).detectConflict("r1", t("12"), t("14")))
    }

    // ---- findAvailableRooms ----

    @Test
    fun findsOnlyFreeRoomsOfTheRightTypeThatAreNotDeleted() = runTest {
        val s = FakeStore().apply {
            rooms["r1"] = Room(id = "r1", number = "101", type = "Deluxe Room")
            rooms["r2"] = Room(id = "r2", number = "102", type = "Deluxe Room")
            rooms["r3"] = Room(id = "r3", number = "103", type = "Deluxe Room", isDeleted = true)
            rooms["r4"] = Room(id = "r4", number = "104", type = "Standard Room")
            rooms["r5"] = Room(id = "r5", number = "105", type = "Deluxe Room")
            locks += lock("b1", "r2", "10", "15")
        }
        val found = logic(s).findAvailableRooms("Deluxe Room", t("12"), t("14"))
        assertEquals(listOf("r1", "r5"), found.map { it.id })
    }

    // ---- updateRoomStatus ----

    @Test
    fun cannotMarkAvailableWhileAGuestIsCheckedIn() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "occupied", currentBookingId = "b1") }
        try {
            logic(s).updateRoomStatus("r1", RoomStatus.AVAILABLE)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(MSG_ROOM_STILL_OCCUPIED, e.message)
            assertEquals(
                "This room still has a guest checked in and cannot be marked Available. The guest must be checked out first — see Check-Out.",
                e.message
            )
        }
        assertTrue(s.roomUpdates.isEmpty())
        assertTrue(s.liveSets.isEmpty())
    }

    @Test
    fun overrideAllowsAvailableWhileOccupied() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "occupied", currentBookingId = "b1") }
        logic(s).updateRoomStatus("r1", RoomStatus.AVAILABLE, allowOccupiedOverride = true)
        assertEquals("available", s.roomUpdates.single().second["status"])
    }

    @Test
    fun availableIsFineWhenNoGuestIsInTheRoom() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "cleaning") }
        logic(s).updateRoomStatus("r1", RoomStatus.AVAILABLE)
        assertEquals("available", s.roomUpdates.single().second["status"])
    }

    @Test
    fun otherStatusesAreNotGuarded() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "occupied", currentBookingId = "b1") }
        logic(s).updateRoomStatus("r1", RoomStatus.MAINTENANCE)
        assertEquals("maintenance", s.roomUpdates.single().second["status"])
    }

    @Test
    fun statusWritesFirestoreAndLiveCopy() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1") }
        logic(s).updateRoomStatus("r1", RoomStatus.OCCUPIED, mapOf("currentBookingId" to "b9"))

        val (id, fields) = s.roomUpdates.single()
        assertEquals("r1", id)
        assertEquals("occupied", fields["status"])
        assertEquals(now, fields["statusUpdatedAt"])
        assertEquals("b9", fields["currentBookingId"])

        val (liveId, live) = s.liveSets.single()
        assertEquals("r1", liveId)
        assertEquals("occupied", live["status"])
        assertEquals(now.toEpochMilliseconds(), live["updatedAt"])
        assertEquals("b9", live["currentBookingId"])
    }

    @Test
    fun liveCopyDropsValuesTheRealtimeDatabaseCannotHold() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1") }
        logic(s).updateRoomStatus("r1", RoomStatus.OCCUPIED, mapOf("note" to "hi", "marker" to Any(), "at" to now))
        val live = s.liveSets.single().second
        assertEquals("hi", live["note"])
        assertEquals(now.toEpochMilliseconds(), live["at"])
        assertFalse(live.containsKey("marker"))
        // The Firestore copy keeps everything.
        assertTrue(s.roomUpdates.single().second.containsKey("marker"))
    }

    @Test
    fun slowLiveWriteIsGivenUpAfterTheTimeoutAndIgnored() = runTest {
        val s = FakeStore().apply {
            rooms["r1"] = Room(id = "r1")
            liveMode = FakeStore.LiveMode.HANG
        }
        logic(s).updateRoomStatus("r1", RoomStatus.CLEANING) // returns instead of waiting forever
        assertEquals("cleaning", s.roomUpdates.single().second["status"])
    }

    @Test
    fun failedLiveWriteIsIgnored() = runTest {
        val s = FakeStore().apply {
            rooms["r1"] = Room(id = "r1")
            liveMode = FakeStore.LiveMode.FAIL
        }
        logic(s).updateRoomStatus("r1", RoomStatus.CLEANING)
        assertEquals(1, s.roomUpdates.size)
    }

    @Test
    fun failedFirestoreWriteIsNotIgnored() = runTest {
        val s = FakeStore().apply {
            rooms["r1"] = Room(id = "r1")
            failRoomUpdate = true
        }
        try {
            logic(s).updateRoomStatus("r1", RoomStatus.CLEANING)
            fail("Expected an exception")
        } catch (e: IllegalStateException) {
            assertEquals("Firestore is down", e.message)
        }
        assertTrue(s.liveSets.isEmpty())
    }

    // ---- updateRoomCleanliness ----

    @Test
    fun cleanlinessWritesFirestoreAndMergesLiveCopy() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1") }
        logic(s).updateRoomCleanliness("r1", Cleanliness.CLEANING_IN_PROGRESS, mapOf("cleanedBy" to "u1"))

        val fields = s.roomUpdates.single().second
        assertEquals("cleaning_in_progress", fields["cleanliness"])
        assertEquals(now, fields["cleanlinessUpdatedAt"])
        assertEquals("u1", fields["cleanedBy"])

        val live = s.liveUpdates.single().second
        assertEquals("cleaning_in_progress", live["cleanliness"])
        assertEquals(now.toEpochMilliseconds(), live["cleanlinessUpdatedAt"])
        assertEquals("u1", live["cleanedBy"])
        assertTrue(s.liveSets.isEmpty())
    }

    @Test
    fun slowCleanlinessLiveWriteIsIgnored() = runTest {
        val s = FakeStore().apply { liveMode = FakeStore.LiveMode.HANG }
        logic(s).updateRoomCleanliness("r1", Cleanliness.CLEAN)
        assertEquals(1, s.roomUpdates.size)
    }

    // ---- triggerRoomStatus ----

    @Test
    fun eventsMoveTheRoomToTheRightStatus() = runTest {
        val expected = mapOf(
            RoomEvent.CHECK_IN to "occupied",
            RoomEvent.CHECK_OUT to "cleaning", // check-out always goes to Cleaning first
            RoomEvent.START_CLEANING to "cleaning",
            RoomEvent.FINISH_CLEANING to "available",
            RoomEvent.MAINTENANCE to "maintenance",
            RoomEvent.BACK_TO_SERVICE to "available"
        )
        assertEquals(RoomEvent.entries.toSet(), expected.keys)
        for ((event, status) in expected) {
            val s = FakeStore().apply { rooms["r1"] = Room(id = "r1") }
            logic(s).triggerRoomStatus("r1", event)
            assertEquals(event.name, status, s.roomUpdates.single().second["status"])
        }
    }

    @Test
    fun aBlankBookingIdDoesNotBlockMakingTheRoomAvailable() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "cleaning", currentBookingId = "") }
        logic(s).updateRoomStatus("r1", RoomStatus.AVAILABLE)
        assertEquals("available", s.roomUpdates.single().second["status"])
    }

    @Test
    fun finishingCleaningStillRespectsTheOccupiedGuard() = runTest {
        val s = FakeStore().apply { rooms["r1"] = Room(id = "r1", status = "cleaning", currentBookingId = "b1") }
        try {
            logic(s).triggerRoomStatus("r1", RoomEvent.FINISH_CLEANING)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(MSG_ROOM_STILL_OCCUPIED, e.message)
        }
    }

    // ---- getRoomDisplayStatus ----

    private fun display(
        status: String = "available",
        cleanliness: String? = null,
        overdue: Boolean = false
    ) = logic(FakeStore()).getRoomDisplayStatus(Room(status = status, cleanliness = cleanliness, checkoutOverdue = overdue))

    @Test
    fun displayStatusTable() {
        assertEquals(RoomLabels.MAINTENANCE, display("maintenance").label)
        assertEquals("Maintenance", display("maintenance").label)
        assertEquals("Out of Service", display("out_of_service").label)
        assertEquals("Reserved", display("reserved").label)

        assertEquals("Occupied · Checkout Overdue", display("occupied", overdue = true).label)
        assertEquals("Occupied · Checkout Overdue", display("occupied", "cleaning_in_progress", overdue = true).label)
        assertEquals("Occupied · Cleaning in Progress", display("occupied", "cleaning_in_progress").label)
        assertEquals("Occupied · Cleaning Due", display("occupied", "daily_cleaning_due").label)
        assertEquals("Occupied · Cleaning Due", display("occupied", "checkout_cleaning_due").label)
        assertEquals("Occupied · Clean", display("occupied").label)
        assertEquals("Occupied · Clean", display("occupied", "clean").label)

        assertEquals("Cleaning in Progress", display("cleaning", "cleaning_in_progress").label)
        assertEquals("Dirty · Needs Cleaning", display("cleaning").label)
        assertEquals("Dirty · Needs Cleaning", display("cleaning", "dirty").label)

        assertEquals("Clean · Available", display("available").label)
        assertEquals("Clean · Available", display("available", "dirty").label)
    }

    @Test
    fun maintenanceAndOutOfServiceWinOverOverdueAndCleanliness() {
        assertEquals("Maintenance", display("maintenance", "cleaning_in_progress", overdue = true).label)
        assertEquals("Out of Service", display("out_of_service", "dirty", overdue = true).label)
        assertEquals("Reserved", display("reserved", overdue = true).label)
    }

    @Test
    fun overdueOnlyMattersForOccupiedRooms() {
        assertEquals("Clean · Available", display("available", overdue = true).label)
        assertEquals("Dirty · Needs Cleaning", display("cleaning", overdue = true).label)
    }

    @Test
    fun missingOrUnknownValuesUseWestlyDefaults() {
        val blank = logic(FakeStore()).getRoomDisplayStatus(Room())
        assertEquals("Clean · Available", blank.label)
        assertEquals(RoomStatus.AVAILABLE, blank.occupancyStatus)
        assertEquals(Cleanliness.CLEAN, blank.cleanliness)

        val odd = display("something_else", "weird")
        assertEquals("Clean · Available", odd.label)
        assertEquals(RoomStatus.AVAILABLE, odd.occupancyStatus)
    }

    @Test
    fun defaultCleanlinessIsDirtyOnlyForCleaningRooms() {
        assertEquals(Cleanliness.DIRTY, display("cleaning").cleanliness)
        assertEquals(Cleanliness.CLEAN, display("available").cleanliness)
        assertEquals(Cleanliness.CLEAN, display("occupied").cleanliness)
        assertEquals(Cleanliness.CLEANING_IN_PROGRESS, display("cleaning", "cleaning_in_progress").cleanliness)
    }

    @Test
    fun displayStatusCarriesOccupancyAndOverdue() {
        val d = display("occupied", "daily_cleaning_due", overdue = true)
        assertEquals(RoomStatus.OCCUPIED, d.occupancyStatus)
        assertEquals(Cleanliness.DAILY_CLEANING_DUE, d.cleanliness)
        assertTrue(d.checkoutOverdue)
        assertFalse(display("occupied").checkoutOverdue)
    }

    @Test
    fun tonesFollowThePillColours() {
        assertEquals(BadgeTone.Warning, display("maintenance").tone)            // orange
        assertEquals(BadgeTone.Outline, display("out_of_service").tone)         // gray
        assertEquals(BadgeTone.Info, display("reserved").tone)                  // blue
        assertEquals(BadgeTone.Destructive, display("occupied", overdue = true).tone) // red
        assertEquals(BadgeTone.Gold, display("occupied", "cleaning_in_progress").tone) // purple
        assertEquals(BadgeTone.Warning, display("occupied", "daily_cleaning_due").tone) // amber
        assertEquals(BadgeTone.Info, display("occupied").tone)                  // blue
        assertEquals(BadgeTone.Gold, display("cleaning", "cleaning_in_progress").tone) // purple
        assertEquals(BadgeTone.Warning, display("cleaning").tone)               // yellow
        assertEquals(BadgeTone.Success, display("available").tone)              // green
    }

    // ---- helpers ----

    @Test
    fun statusKeysRoundTrip() {
        RoomStatus.entries.forEach { assertEquals(it, RoomStatus.fromKey(it.key)) }
        Cleanliness.entries.forEach { assertEquals(it, Cleanliness.fromKey(it.key)) }
        assertNull(RoomStatus.fromKey("nope"))
        assertNull(RoomStatus.fromKey(null))
        assertNull(Cleanliness.fromKey(null))
    }

    @Test
    fun liveFieldsKeepOnlyPlainValues() {
        val out = liveFields(mapOf("status" to "occupied"), mapOf("a" to 1, "b" to true, "c" to null, "d" to Any()))
        assertEquals(mapOf<String, Any?>("status" to "occupied", "a" to 1, "b" to true, "c" to null), out)
    }
}
