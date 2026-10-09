package com.westly.nbms.features.checkin

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkInRepositoryTest {

    private val store = FakeWalkInStore()
    private val roomLogic = FakeRoomLogic()
    private val realtime = FakeRealtime()
    private val audit = FakeAudit()
    private val notifier = FakeNotifier()
    private val connectivity = FakeConnectivity()
    private val staff = WalkInStaff("u1", "Rita")
    private val lagos = TimeZone.of("Africa/Lagos")

    private fun repo() = WalkInRepository(store, roomLogic, realtime, audit, notifier, connectivity)

    private suspend fun submit(
        form: WalkInForm = testForm(),
        room: com.westly.nbms.features.rooms.Room = testRoom(),
        onLost: () -> Unit = {}
    ) = repo().submit(form, room, staff, lagos, "11:00", onLost)

    @Test fun payNowSavesEverythingAndRunsTheFollowUps() = runTest {
        val result = submit()
        val done = result as WalkInResult.Done
        assertEquals("Ada Obi", done.success.guestName)
        assertEquals("101", done.success.roomNumber)
        assertTrue(done.success.paidNow)
        assertEquals(50_000.0, done.success.total, 0.0)

        // one transaction, with the payment
        assertEquals(1, store.commits.size)
        assertNotNull(store.commits.single().payment)
        // the availability check used the same dates the transaction saved
        val check = roomLogic.conflictChecks.single()
        assertEquals("r1", check.first)
        assertEquals(done.success.checkOut, check.third)

        // live room status
        val (path, fields) = realtime.updates.single()
        assertEquals("roomStatus/r1", path)
        assertEquals("occupied", fields["status"])
        assertEquals("Ada Obi", fields["currentGuest"])
        assertEquals("clean", fields["cleanliness"])
        assertTrue(fields["updatedAt"] is Long)

        // audit
        val entry = audit.entries.single()
        assertEquals("walk_in_checkin", entry.action)
        assertEquals("bookings", entry.collection)
        assertEquals(store.commits.single().ids.bookingId, entry.id)
        assertNull(entry.previous)
        assertEquals(setOf("guestName", "roomNumber", "checkInAt", "checkOut"), entry.new!!.keys)

        // alerts: walk-in always, payment because the guest paid now
        assertEquals(listOf("New Walk-In", "Payment Received"), notifier.calls.map { it.title })

        // activity feed
        val (feedPath, feed) = realtime.sets.single()
        assertTrue(feedPath.startsWith("activity_feed/"))
        @Suppress("UNCHECKED_CAST")
        val feedMap = feed as Map<String, Any?>
        assertEquals("walk_in", feedMap["type"])
        assertEquals("Walk-in: Ada Obi → Room 101", feedMap["text"])
        assertEquals("Rita", feedMap["by"])
        assertTrue(feedMap["at"] is Long)
    }

    @Test fun payAtCheckOutHasNoPaymentAndNoPaymentAlert() = runTest {
        val done = submit(testForm(option = PaymentOption.PAY_AT_CHECKOUT)) as WalkInResult.Done
        assertEquals(false, done.success.paidNow)
        assertNull(store.commits.single().payment)
        assertEquals(listOf("New Walk-In"), notifier.calls.map { it.title })
    }

    @Test fun offlineStopsBeforeAnythingElse() = runTest {
        connectivity.online = false
        val r = submit() as WalkInResult.Rejected
        assertEquals("You're Offline", r.title)
        assertEquals("No internet connection detected. Please reconnect and try again.", r.message)
        assertTrue(roomLogic.conflictChecks.isEmpty())
        assertTrue(store.commits.isEmpty())
    }

    @Test fun aRoomThatIsNotAvailableIsRefused() = runTest {
        val r = submit(room = testRoom(status = "occupied")) as WalkInResult.Rejected
        assertEquals("Room Not Available", r.title)
        assertEquals("Room 101 is currently occupied. Please choose a different room.", r.message)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun checkOutBeforeCheckInIsRefused() = runTest {
        val form = testForm().copy(checkOutDate = testForm().checkInDate)
        val r = submit(form) as WalkInResult.Rejected
        assertEquals("Walk-In Failed", r.title)
        assertEquals("Check-out must be after check-in.", r.message)
        assertTrue(roomLogic.conflictChecks.isEmpty())
    }

    @Test fun missingCheckInIsRefused() = runTest {
        val r = submit(testForm().copy(checkInTime = null)) as WalkInResult.Rejected
        assertEquals("Walk-In Failed", r.title)
        assertEquals("Please enter a valid check-in date and time.", r.message)
    }

    @Test fun aBookedRoomIsRefusedWithoutSaving() = runTest {
        roomLogic.conflict = true
        val r = submit() as WalkInResult.Rejected
        assertEquals("Walk-In Failed", r.title)
        assertEquals("This room is already booked for the selected dates.", r.message)
        assertTrue(store.commits.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun theRoomTakenInsideTheTransactionIsReportedAndNothingFollows() = runTest {
        store.failWith = WalkInException("Room is no longer available.")
        val r = submit() as WalkInResult.Rejected
        assertEquals("Walk-In Failed", r.title)
        assertEquals("Room is no longer available.", r.message)
        assertTrue(realtime.updates.isEmpty())
        assertTrue(realtime.sets.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun anUnknownFailureUsesItsMessageOrTheGenericOne() = runTest {
        store.failWith = IllegalStateException("PERMISSION_DENIED")
        assertEquals("PERMISSION_DENIED", (submit() as WalkInResult.Rejected).message)
        store.failWith = IllegalStateException()
        assertEquals("Something went wrong. Please try again.", (submit() as WalkInResult.Rejected).message)
    }

    @Test fun aSaveThatNeverFinishesEndsWithAClearMessage() = runTest {
        store.neverFinish = true
        val r = submit() as WalkInResult.Rejected
        assertEquals("Walk-In Failed", r.title)
        assertEquals("Saving took too long — please check your connection and try again.", r.message)
    }

    @Test fun failedFollowUpsNeverHideTheSavedWalkIn() = runTest {
        realtime.fail = true
        audit.fail = true
        notifier.fail = true
        val result = submit()
        assertTrue(result is WalkInResult.Done)
        assertEquals(1, store.commits.size)
    }

    @Test fun connectionLossWhileSavingIsReportedOnce() = runTest {
        connectivity.lossEvents = flowOf(Unit, Unit)
        var told = 0
        val result = submit(onLost = { told++ })
        assertTrue(result is WalkInResult.Done)
        assertEquals(1, told)
    }
}
