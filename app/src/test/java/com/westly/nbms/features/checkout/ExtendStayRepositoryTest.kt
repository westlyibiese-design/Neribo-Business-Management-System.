package com.westly.nbms.features.checkout

import com.google.firebase.Timestamp
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendStayRepositoryTest {

    private val store = FakeExtendStayStore()
    private val roomLogic = FakeRoomLogic()
    private val realtime = FakeRealtime()
    private val audit = FakeAudit()
    private val notifier = FakeNotifier()
    private val network = FakeNetwork()

    private fun repo() = ExtendStayRepository(store, roomLogic, realtime, audit, notifier, network).apply {
        clock = { at("2026-10-11T08:00:00Z") }
    }

    private fun request(nights: Int = 2, role: Role? = Role.RECEPTIONIST, method: ExtendPaymentMethod = ExtendPaymentMethod.CARD) =
        ExtendRequest(nights, method, role, STAFF, LAGOS)

    private suspend fun extend(request: ExtendRequest = request()) = repo().extend(testBooking(), request)

    @Test fun twoNightsSavesTheBookingTheLockAndThePayment() = runTest {
        val done = extend() as ExtendResult.Done
        assertEquals("101", done.roomNumber)
        assertEquals("Oct 14, 2026", done.newCheckOutText)

        val w = store.commits.single()
        val newOut = Timestamp(at("2026-10-14T10:00:00Z").epochSeconds, 0)

        // booking: check-out moves, nights and total grow, check-in is never touched
        assertEquals(newOut, w.booking["checkOut"])
        assertFalse(w.booking.containsKey("checkIn"))
        assertEquals(4, w.booking["nights"])
        assertEquals(100_000.0, w.booking["totalAmount"])
        assertTrue(w.booking.containsKey("lastOverdueNotifiedAt"))
        assertNull(w.booking["lastOverdueNotifiedAt"])
        assertTrue(w.booking["updatedAt"] === CheckOutServerTime)
        assertEquals("u1", w.booking["updatedBy"])
        assertEquals("Rita", w.booking["updatedByName"])

        val union = w.booking["extensionHistory"] as ExtendArrayUnion
        assertEquals(setOf("extendedAt", "extendedBy", "extendedByName", "nightsAdded", "previousCheckOut", "newCheckOut", "amount"), union.entry.keys)
        assertEquals(2, union.entry["nightsAdded"])
        assertEquals(50_000.0, union.entry["amount"])
        assertEquals(newOut, union.entry["newCheckOut"])
        assertEquals(Timestamp(at("2026-10-12T10:00:00Z").epochSeconds, 0), union.entry["previousCheckOut"])

        // the lock changes together with the booking and stays checked in
        assertEquals("checked_in", w.bookingDate["status"])
        assertEquals(newOut, w.bookingDate["checkOut"])
        assertEquals("r1", w.bookingDate["roomId"])
        assertEquals(Timestamp(at("2026-10-10T13:00:00Z").epochSeconds, 0), w.bookingDate["checkIn"])

        // payment
        val p = w.payment!!
        assertEquals("stay_extension", p["type"])
        assertEquals(50_000.0, p["amount"])
        assertEquals("card", p["paymentMethod"])
        assertEquals("pending", p["approvalStatus"])
        assertEquals(2, p["extensionNights"])
        assertEquals(newOut, p["newCheckOut"])
        assertEquals(false, p["isDeleted"])
        assertNull(p["approvedBy"])
        assertTrue(p.containsKey("rejectedReason"))
    }

    @Test fun theConflictCheckExcludesThisBookingAndUsesTheSameDates() = runTest {
        extend()
        val c = roomLogic.checks.single()
        assertEquals("r1", c.roomId)
        assertEquals(at("2026-10-12T10:00:00Z"), c.checkIn)
        assertEquals(at("2026-10-14T10:00:00Z"), c.checkOut)
        assertEquals("b1abcdef99", c.exclude)
    }

    @Test fun followUps() = runTest {
        extend()
        val e = audit.entries.single()
        assertEquals("stay_extended", e.action)
        assertEquals("bookings", e.collection)
        assertEquals(setOf("checkOut"), e.previous!!.keys)
        assertEquals(setOf("checkOut", "nightsAdded", "amount"), e.new!!.keys)

        assertEquals(listOf("Guest Stay Extended", "Payment Received"), notifier.calls.map { it.title })
        assertEquals("Ada Obi's stay in Room 101 was extended to Oct 14, 2026 by Rita.", notifier.calls.first().message)

        val (path, feed) = realtime.sets.single()
        assertTrue(path.startsWith("activity_feed/"))
        @Suppress("UNCHECKED_CAST")
        val map = feed as Map<String, Any?>
        assertEquals("booking_modified", map["type"])
        assertEquals("Rita extended Ada Obi's stay in Room 101 by 2 night(s)", map["text"])
    }

    @Test fun aFreeExtensionMakesNoPaymentAndNoPaymentAlert() = runTest {
        store.live = testStay(pricePerNight = 0.0, total = 0.0, nights = null, roomPrice = null)
        extend()
        assertNull(store.commits.single().payment)
        assertEquals(listOf("Guest Stay Extended"), notifier.calls.map { it.title })
    }

    @Test fun anOverdueRoomIsMarkedToBeCleared() = runTest {
        store.live = testStay(overdue = true)
        extend()
        assertTrue(store.commits.single().clearRoomOverdue)
    }

    @Test fun theRateComesFromTheLiveDocuments() = runTest {
        // The list says 25,000 a night, but the live booking has been repriced.
        store.live = testStay(pricePerNight = 40_000.0, total = 80_000.0)
        extend(request(nights = 1))
        assertEquals(40_000.0, store.commits.single().summary.amount, 0.0)
        assertEquals(120_000.0, store.commits.single().booking["totalAmount"])
    }

    @Test fun missingNightsAreWorkedOutFromTheDates() = runTest {
        store.live = testStay(nights = null)
        extend(request(nights = 1))
        assertEquals(3, store.commits.single().booking["nights"]) // 2 nights between the dates + 1
    }

    @Test fun otherRolesAreNotAuthorised() = runTest {
        val r = extend(request(role = Role.MANAGER)) as ExtendResult.Rejected
        assertEquals("Not Authorized", r.title)
        assertEquals("You don't have permission to extend a guest's stay.", r.message)
        assertTrue(store.commits.isEmpty())
        assertTrue(roomLogic.checks.isEmpty())
    }

    @Test fun offlineIsRefused() = runTest {
        network.online = false
        val r = extend() as ExtendResult.Rejected
        assertEquals("You're Offline", r.title)
        assertEquals("No internet connection detected. Please reconnect and try again.", r.message)
    }

    @Test fun lessThanOneNightIsRefused() = runTest {
        val r = extend(request(nights = 0)) as ExtendResult.Rejected
        assertEquals("Invalid Extension", r.title)
        assertEquals("Enter at least 1 additional night.", r.message)
    }

    @Test fun aBookingWithoutACheckOutIsRefused() = runTest {
        val r = repo().extend(testBooking().copy(checkOut = null), request()) as ExtendResult.Rejected
        assertEquals("Extension Failed", r.title)
        assertEquals("This booking has no valid checkout date to extend from.", r.message)
    }

    @Test fun aConflictIsRefusedWithoutSaving() = runTest {
        roomLogic.conflict = true
        val r = extend() as ExtendResult.Rejected
        assertEquals("Extension Failed", r.title)
        assertEquals(
            "This room is already reserved by another guest during part of the requested extension. " +
                "Try fewer nights or move the guest to a different room.",
            r.message
        )
        assertTrue(store.commits.isEmpty())
    }

    @Test fun transactionProblemsShowTheirMessages() = runTest {
        store.live = null
        assertEquals("Booking not found.", (extend() as ExtendResult.Rejected).message)

        store.live = testStay().copy(status = "checked_out")
        assertEquals("This guest is no longer checked in — the stay can't be extended.", (extend() as ExtendResult.Rejected).message)

        store.live = testStay()
        store.roomExists = false
        assertEquals("Room not found.", (extend() as ExtendResult.Rejected).message)

        store.roomExists = true
        store.roomBookingId = "someone-else"
        assertEquals(
            "This room is no longer assigned to this guest's booking — please refresh and try again.",
            (extend() as ExtendResult.Rejected).message
        )
        assertTrue(store.commits.isEmpty())
    }

    @Test fun failingFollowUpsNeverUndoTheExtension() = runTest {
        realtime.fail = true
        audit.fail = true
        notifier.fail = true
        assertNotNull((extend() as ExtendResult.Done).roomNumber)
        assertEquals(1, store.commits.size)
    }
}
