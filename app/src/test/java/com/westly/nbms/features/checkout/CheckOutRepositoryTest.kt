package com.westly.nbms.features.checkout

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckOutRepositoryTest {

    private val store = FakeCheckOutStore()
    private val realtime = FakeRealtime()
    private val audit = FakeAudit()
    private val notifier = FakeNotifier()
    private val network = FakeNetwork()

    private fun repo() = CheckOutRepository(store, realtime, audit, notifier, network).apply {
        clock = { at("2026-10-12T09:30:00Z") }
    }

    private suspend fun checkOut(input: CheckOutInput = testInput(actual = at("2026-10-12T09:00:00Z"))) =
        repo().checkOut(testBooking(), input)

    @Test fun paidRoomNoExtrasSavesWithoutPaymentOrPaymentAlert() = runTest {
        val done = checkOut() as CheckOutResult.Done
        assertEquals("Ada Obi", done.success.guestName)
        assertEquals("101", done.success.roomNumber)
        assertEquals(0.0, done.success.summary.amountToCharge, 0.0)
        assertEquals(1, store.commits.size)
        assertNull(store.commits.single().writes.payment)
        assertEquals(listOf("Guest Checked Out"), notifier.calls.map { it.title })
    }

    @Test fun followUpsRunAfterTheCommit() = runTest {
        checkOut(testInput(actual = at("2026-10-12T09:00:00Z"), extras = 500.0))

        val (path, fields) = realtime.updates.single()
        assertEquals("roomStatus/r1", path)
        assertEquals("cleaning", fields["status"])
        assertEquals("dirty", fields["cleanliness"])
        assertTrue(fields.containsKey("currentGuest"))
        assertNull(fields["currentGuest"])
        assertTrue(fields["updatedAt"] is Long)

        val entry = audit.entries.single()
        assertEquals("check_out", entry.action)
        assertEquals("bookings", entry.collection)
        assertEquals("b1abcdef99", entry.id)
        assertEquals(mapOf<String, Any?>("status" to "checked_in"), entry.previous)
        assertEquals(setOf("status", "checkOutAt", "finalAmount"), entry.new!!.keys)
        assertEquals(50_500.0, entry.new!!["finalAmount"])

        assertEquals(listOf("Guest Checked Out", "Payment Received"), notifier.calls.map { it.title })
        assertEquals("₦500 (cash) received from Ada Obi, recorded by Rita.", notifier.calls.last().message)

        val (feedPath, feed) = realtime.sets.single()
        assertTrue(feedPath.startsWith("activity_feed/"))
        @Suppress("UNCHECKED_CAST")
        val map = feed as Map<String, Any?>
        assertEquals("check_out", map["type"])
        assertEquals("Rita checked out Ada Obi (Room 101)", map["text"])
        assertEquals("Rita", map["by"])
        assertTrue(map["at"] is Long)
    }

    @Test fun successCarriesTheReceipt() = runTest {
        val done = checkOut(testInput(actual = at("2026-10-12T09:00:00Z"), extras = 500.0)) as CheckOutResult.Done
        val receipt = done.success.receipt
        assertEquals("WI-AB12CD", receipt.receiptNumber)
        assertEquals("Westly Hotel", receipt.businessName)
        assertEquals(50_500.0, receipt.total, 0.0)
        assertEquals(at("2026-10-12T09:30:00Z"), receipt.producedAt)
    }

    @Test fun offlineStopsFirst() = runTest {
        network.online = false
        val r = checkOut() as CheckOutResult.Rejected
        assertEquals("You're Offline", r.title)
        assertEquals("No internet connection detected. Please reconnect and try again.", r.message)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun missingDateIsRefused() = runTest {
        val r = checkOut(testInput(actual = null)) as CheckOutResult.Rejected
        assertEquals("Check-Out Failed", r.title)
        assertEquals("Please enter a valid check-out date and time.", r.message)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun checkOutBeforeCheckInIsRefused() = runTest {
        val r = checkOut(testInput(actual = at("2026-10-10T12:59:00Z"))) as CheckOutResult.Rejected
        assertEquals("Check-Out Failed", r.title)
        assertEquals("Check-out time cannot be before the guest's check-in time.", r.message)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun aMissingBookingGivesItsMessage() = runTest {
        store.booking = null
        val r = checkOut() as CheckOutResult.Rejected
        assertEquals("Check-Out Failed", r.title)
        assertEquals("Booking not found.", r.message)
    }

    @Test fun aBookingThatIsNotCheckedInGivesItsMessage() = runTest {
        store.booking = testBooking().copy(status = "checked_out")
        val r = checkOut() as CheckOutResult.Rejected
        assertEquals("Booking is not in checked-in state.", r.message)
        assertTrue(realtime.updates.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aSlowTransactionGivesUpAfterTwentySeconds() = runTest {
        store.neverFinish = true
        val r = checkOut() as CheckOutResult.Rejected
        assertEquals("Check-Out Failed", r.title)
        assertEquals("Saving took too long — please check your connection and try again.", r.message)
    }

    @Test fun failingFollowUpsNeverUndoTheCheckOut() = runTest {
        realtime.fail = true
        audit.fail = true
        notifier.fail = true
        val done = checkOut(testInput(actual = at("2026-10-12T09:00:00Z"), extras = 500.0))
        assertNotNull((done as CheckOutResult.Done).success)
        assertEquals(1, store.commits.size)
    }

    @Test fun noRoomIdSkipsTheLiveRoomStatus() = runTest {
        store.booking = testBooking(roomId = "")
        checkOut()
        assertTrue(realtime.updates.isEmpty())
        assertEquals(1, realtime.sets.size) // the activity feed
    }
}
