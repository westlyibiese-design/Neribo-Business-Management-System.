package com.westly.nbms.features.bookings

import com.google.firebase.Timestamp
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ts(sec: Long) = Timestamp(sec, 0)

private fun booking(
    id: String = "b1",
    status: String = "pending",
    guest: String = "Ada Obi",
    email: String? = "ada@mail.com",
    room: String = "101",
    code: String? = "WI-AB12CD",
    created: Long? = 100,
    deleted: Boolean = false
) = Booking(
    id = id, bookingId = code, guestName = guest, guestEmail = email, roomNumber = room, status = status,
    createdAt = created?.let { ts(it) }, isDeleted = deleted
)

class BookingRulesTest {

    // ── transitions ──

    @Test fun pendingCanBeConfirmedRejectedOrCancelled() {
        assertEquals(
            setOf(BookingStatus.CONFIRMED, BookingStatus.REJECTED, BookingStatus.CANCELLED),
            allowedTransitions(BookingStatus.PENDING)
        )
    }

    @Test fun confirmedCanBeCancelledOrNoShow() {
        assertEquals(setOf(BookingStatus.CANCELLED, BookingStatus.NO_SHOW), allowedTransitions(BookingStatus.CONFIRMED))
    }

    @Test fun otherStatusesHaveNoManualTransitions() {
        listOf(
            BookingStatus.CHECKED_IN, BookingStatus.CHECKED_OUT,
            BookingStatus.CANCELLED, BookingStatus.REJECTED, BookingStatus.NO_SHOW
        ).forEach { assertTrue("$it", allowedTransitions(it).isEmpty()) }
    }

    @Test fun canTransitionMatchesTheTable() {
        assertTrue(canTransition(BookingStatus.PENDING, BookingStatus.CONFIRMED))
        assertFalse(canTransition(BookingStatus.PENDING, BookingStatus.NO_SHOW))
        assertFalse(canTransition(BookingStatus.CONFIRMED, BookingStatus.CONFIRMED))
        assertFalse(canTransition(BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN))
        assertFalse(canTransition(BookingStatus.CANCELLED, BookingStatus.CONFIRMED))
    }

    // ── roles ──

    @Test fun onlyFrontOfficeRolesChangeStatus() {
        assertTrue(canChangeStatus(Role.SUPER_ADMIN))
        assertTrue(canChangeStatus(Role.MANAGER))
        assertTrue(canChangeStatus(Role.RECEPTIONIST))
        assertFalse(canChangeStatus(Role.OPERATIONS_MANAGER))
        assertFalse(canChangeStatus(Role.ACCOUNTANT))
    }

    @Test fun onlySuperAdminAndReceptionistExtendStays() {
        assertTrue(canExtendStay(Role.SUPER_ADMIN))
        assertTrue(canExtendStay(Role.RECEPTIONIST))
        assertFalse(canExtendStay(Role.MANAGER))
        assertFalse(canExtendStay(Role.OPERATIONS_MANAGER))
    }

    // ── labels ──

    @Test fun labels() {
        assertEquals("checked in", statusWords("checked_in"))
        assertEquals("No Show", statusTitle("no_show"))
        assertEquals("Pending", statusTitle("pending"))
        assertEquals("Booking confirmed.", statusChangedMessage("confirmed"))
        assertEquals("Booking no show.", statusChangedMessage("no_show"))
        assertEquals("1 booking", bookingCountText(1))
        assertEquals("0 bookings", bookingCountText(0))
        assertEquals("12 bookings", bookingCountText(12))
        assertEquals("1 stay", stayCountText(1))
        assertEquals("3 stays", stayCountText(3))
        assertEquals("1 adults, 0 children", guestsText(Booking()))
        assertEquals("—", null.orDash())
        assertEquals("—", "  ".orDash())
        assertEquals("x", "x".orDash())
    }

    @Test fun bookingCodeUsesBookingIdOrFirstEightOfTheDocumentId() {
        assertEquals("WI-AB12CD", bookingCode(booking()))
        assertEquals("ABCDEFGH", bookingCode(booking(id = "abcdefghijkl", code = null)))
        assertEquals("ABCDEFGH", bookingCode(booking(id = "abcdefghijkl", code = " ")))
    }

    // ── chips and counts ──

    @Test fun countsAndChipLabels() {
        val list = listOf(booking("1", "pending"), booking("2", "pending"), booking("3", "checked_in"), booking("4", "no_show"))
        val counts = statusCounts(list)
        assertEquals(4, counts["all"])
        assertEquals(2, counts["pending"])
        assertEquals(1, counts["checked_in"])
        assertEquals(0, counts["confirmed"])
        assertEquals("All (4)", chipLabel("all", counts))
        assertEquals("pending (2)", chipLabel("pending", counts))
        assertEquals("checked in (1)", chipLabel("checked_in", counts))
        assertEquals("confirmed (0)", chipLabel("confirmed", counts))
        assertEquals(listOf("all", "pending", "confirmed", "checked_in", "checked_out"), STATUS_CHIP_KEYS)
        assertEquals(8, STATUS_FILTER_OPTIONS.size)
    }

    // ── sort and filter ──

    @Test fun deletedBookingsAreDroppedAndNewestComesFirst() {
        val list = listOf(
            booking("old", created = 10),
            booking("new", created = 30),
            booking("gone", created = 99, deleted = true),
            booking("none", created = null),
            booking("mid", created = 20)
        )
        assertEquals(listOf("new", "mid", "old", "none"), visibleBookings(list).map { it.id })
    }

    @Test fun searchMatchesGuestEmailRoomAndBookingIdIgnoringCase() {
        val list = listOf(
            booking("1", guest = "Ada Obi", email = "ada@mail.com", room = "101", code = "WI-AAA111"),
            booking("2", guest = "Bola Tunde", email = "bola@x.org", room = "202", code = "WI-BBB222")
        )
        assertEquals(listOf("1"), filterBookings(list, "ADA", "all").map { it.id })
        assertEquals(listOf("2"), filterBookings(list, "x.org", "all").map { it.id })
        assertEquals(listOf("2"), filterBookings(list, "202", "all").map { it.id })
        assertEquals(listOf("1"), filterBookings(list, "wi-aaa", "all").map { it.id })
        assertEquals(2, filterBookings(list, "  ", "all").size)
        assertTrue(filterBookings(list, "zzz", "all").isEmpty())
    }

    @Test fun statusFilterCombinesWithSearch() {
        val list = listOf(
            booking("1", status = "pending", guest = "Ada"),
            booking("2", status = "confirmed", guest = "Ada"),
            booking("3", status = "confirmed", guest = "Bola")
        )
        assertEquals(listOf("2", "3"), filterBookings(list, "", "confirmed").map { it.id })
        assertEquals(listOf("2"), filterBookings(list, "ada", "confirmed").map { it.id })
        assertEquals(3, filterBookings(list, "", "all").size)
    }

    @Test fun bookingWithoutOptionalFieldsStillFilters() {
        val b = Booking(id = "x", guestName = "Solo", roomNumber = "9")
        assertEquals(1, filterBookings(listOf(b), "solo", "all").size)
        assertTrue(filterBookings(listOf(b), "mail", "all").isEmpty())
    }

    // ── what a status change writes ──

    @Test fun statusFieldsHoldStatusAndWhoDidIt() {
        val f = bookingStatusFields(BookingStatus.CONFIRMED, ServerTime, "u1", "Rita")
        assertEquals(setOf("status", "updatedAt", "updatedBy", "updatedByName"), f.keys)
        assertEquals("confirmed", f["status"])
        assertEquals("u1", f["updatedBy"])
        assertEquals("Rita", f["updatedByName"])
        assertTrue(f["updatedAt"] === ServerTime)
    }

    @Test fun lockFieldsKeepRoomAndDatesAndTakeTheNewStatus() {
        val b = Booking(id = "b", roomId = "r1", checkIn = ts(1), checkOut = ts(2), status = "confirmed")
        val f = bookingLockFields(b, BookingStatus.CANCELLED)
        assertEquals(setOf("roomId", "checkIn", "checkOut", "status"), f.keys)
        assertEquals("r1", f["roomId"])
        assertEquals(ts(1), f["checkIn"])
        assertEquals(ts(2), f["checkOut"])
        assertEquals("cancelled", f["status"])
    }

    @Test fun lockFieldsSkipMissingDatesSoAMergeNeverBlanksThem() {
        val f = bookingLockFields(Booking(id = "b", roomId = "r1"), BookingStatus.NO_SHOW)
        assertEquals(setOf("roomId", "status"), f.keys)
    }

    @Test fun modifiedAlertIsSkippedForCheckInAndCheckOut() {
        assertFalse(sendsModifiedAlert(BookingStatus.CHECKED_IN))
        assertFalse(sendsModifiedAlert(BookingStatus.CHECKED_OUT))
        assertTrue(sendsModifiedAlert(BookingStatus.CONFIRMED))
        assertTrue(sendsModifiedAlert(BookingStatus.NO_SHOW))
    }

    // ── guests ──

    @Test fun guestSearchMatchesNameEmailAndPhone() {
        val guests = listOf(
            Guest(id = "g1", name = "Ada Obi", email = "ada@mail.com", phone = "08031234567"),
            Guest(id = "g2", name = "Bola Tunde", email = null, phone = null),
            Guest(id = "g3", name = "Gone", isDeleted = true)
        )
        val live = visibleGuests(guests)
        assertEquals(listOf("g1", "g2"), live.map { it.id })
        assertEquals(listOf("g1"), filterGuests(live, "MAIL.COM").map { it.id })
        assertEquals(listOf("g1"), filterGuests(live, "0803").map { it.id })
        assertEquals(listOf("g2"), filterGuests(live, "bola").map { it.id })
        assertEquals(2, filterGuests(live, "").size)
        assertTrue(filterGuests(live, "nobody").isEmpty())
    }

    @Test fun stayHistoryMatchesByGuestIdOrNameNewestFirst() {
        val guest = Guest(id = "g1", name = "Ada Obi")
        val bookings = listOf(
            Booking(id = "a", guestId = "g1", guestName = "Someone Else", checkIn = ts(10)),
            Booking(id = "b", guestId = null, guestName = "Ada Obi", checkIn = ts(30)),
            Booking(id = "c", guestId = "g9", guestName = "Other", checkIn = ts(50)),
            Booking(id = "d", guestId = "g1", guestName = "Ada Obi", checkIn = ts(5), checkInAt = ts(40)),
            Booking(id = "e", guestId = "g1", guestName = "Ada Obi", checkIn = ts(99), isDeleted = true)
        )
        assertEquals(listOf("d", "b", "a"), guestStays(guest, bookings).map { it.id })
    }
}
