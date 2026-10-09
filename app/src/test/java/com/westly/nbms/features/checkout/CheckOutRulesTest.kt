package com.westly.nbms.features.checkout

import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckOutRulesTest {

    private val scheduled = at("2026-10-12T10:00:00Z")

    // ---- timing ----

    @Test fun exactlyOnTime() {
        val t = checkoutTiming(scheduled, scheduled)
        assertEquals(TimingKind.ON_TIME, t.kind)
        assertEquals("This will be recorded as an on-time check-out.", timingLine(t))
        assertEquals("On Time", timingPillText(t))
    }

    @Test fun fifteenMinutesEitherSideIsStillOnTime() {
        assertEquals(TimingKind.ON_TIME, checkoutTiming(at("2026-10-12T10:15:00Z"), scheduled).kind)
        assertEquals(TimingKind.ON_TIME, checkoutTiming(at("2026-10-12T09:45:00Z"), scheduled).kind)
    }

    @Test fun sixteenMinutesLateIsLateByPointThreeHours() {
        val t = checkoutTiming(at("2026-10-12T10:16:00Z"), scheduled)
        assertEquals(TimingKind.LATE, t.kind)
        assertEquals(0.3, t.hours, 0.0)
        assertEquals("Late by 0.3h", timingPillText(t))
    }

    @Test fun earlyHoursAreRoundedToOneDecimal() {
        val t = checkoutTiming(at("2026-10-12T07:30:00Z"), scheduled)
        assertEquals(TimingKind.EARLY, t.kind)
        assertEquals(2.5, t.hours, 0.0)
        assertEquals("This will be recorded as an early check-out, 2.5h before the scheduled time.", timingLine(t))
        assertEquals("Early by 2.5h", timingPillText(t))
    }

    @Test fun wholeHoursHaveNoDecimalPoint() {
        val t = checkoutTiming(at("2026-10-12T13:00:00Z"), scheduled)
        assertEquals(3.0, t.hours, 0.0)
        assertEquals("This will be recorded as a late check-out, 3h after the scheduled time.", timingLine(t))
        assertEquals("3", hoursText(3.0))
        assertEquals("2.5", hoursText(2.5))
    }

    // ---- scheduled time ----

    @Test fun scheduledIsTheCheckOutDateAtTheOfficialTime() {
        val b = testBooking()
        assertEquals(at("2026-10-12T10:00:00Z"), scheduledCheckOutAt(b, "11:00", LAGOS))
        assertEquals(at("2026-10-12T11:00:00Z"), scheduledCheckOutAt(b, "12:00", LAGOS))
    }

    @Test fun scheduledFallsBackToElevenAndHandlesNoCheckOut() {
        assertEquals(at("2026-10-12T10:00:00Z"), scheduledCheckOutAt(testBooking(), "nonsense", LAGOS))
        assertNull(scheduledCheckOutAt(testBooking().copy(checkOut = null), "11:00", LAGOS))
        assertEquals("11:00", officialCheckOutTime(null))
        assertEquals("12:30", officialCheckOutTime(" 12:30 "))
        assertEquals("11:00", officialCheckOutTime("25:99"))
    }

    // ---- money ----

    @Test fun paidRoomOwesNothingWithoutExtras() {
        assertEquals(0.0, dueAtCheckout(50_000.0, "paid"), 0.0)
        assertEquals(0.0, totalDue(50_000.0, "paid", 0.0), 0.0)
    }

    @Test fun paidRoomOwesOnlyTheExtras() {
        assertEquals(500.0, totalDue(50_000.0, "paid", 500.0), 0.0)
    }

    @Test fun unpaidAndLegacyRoomsAreChargedInFull() {
        assertEquals(50_000.0, totalDue(50_000.0, "pending", 0.0), 0.0)
        assertEquals(50_500.0, totalDue(50_000.0, "pending", 500.0), 0.0)
        assertEquals(50_000.0, totalDue(50_000.0, null, 0.0), 0.0)
        assertEquals(50_500.0, finalAmount(50_000.0, 500.0), 0.0)
        assertTrue(isRoomPaid("paid"))
        assertFalse(isRoomPaid(null))
    }

    @Test fun extrasTextIsForgiving() {
        assertEquals(0.0, parseExtras(""), 0.0)
        assertEquals(0.0, parseExtras("abc"), 0.0)
        assertEquals(0.0, parseExtras("-5"), 0.0)
        assertEquals(1250.5, parseExtras("1250.5"), 0.0)
        assertEquals(5.0, parseExtras("5."), 0.0)
        assertEquals("12.59", sanitizeDecimal("1a2.5.9-"))
    }

    @Test fun methodLabels() {
        assertEquals("Credit Card", methodLabel("credit_card"))
        assertEquals("Cash", methodLabel("cash"))
        assertEquals("Bank Transfer", methodLabel("bank_transfer"))
    }

    // ---- the list and the filters ----

    private val today = LocalDate(2026, 10, 11)
    private fun filters(due: DueFilter, query: String = "") = CheckOutFilters(
        query = query,
        due = due,
        specificDate = LocalDate(2026, 10, 12),
        rangeStart = LocalDate(2026, 10, 10),
        rangeEnd = LocalDate(2026, 10, 12)
    )

    private fun match(b: Booking, f: CheckOutFilters) = matchesDue(b, f, "11:00", LAGOS, today)

    @Test fun onlyCheckedInGuestsThatAreNotDeleted() {
        val all = listOf(
            testBooking(),
            testBooking().copy(id = "b2", status = "checked_out"),
            testBooking().copy(id = "b3", isDeleted = true),
            testBooking().copy(id = "b4", status = "confirmed")
        )
        assertEquals(listOf("b1abcdef99"), checkedInGuests(all).map { it.id })
    }

    @Test fun searchMatchesNameRoomAndEmailIgnoringCase() {
        val b = testBooking()
        assertTrue(matchesSearch(b, ""))
        assertTrue(matchesSearch(b, "  ADA "))
        assertTrue(matchesSearch(b, "101"))
        assertTrue(matchesSearch(b, "ADA@X.COM"))
        assertFalse(matchesSearch(b, "zzz"))
        assertFalse(matchesSearch(b.copy(guestEmail = null), "x.com"))
    }

    @Test fun allMatchesEverything() {
        assertTrue(match(testBooking(), filters(DueFilter.ALL)))
    }

    @Test fun todayAndTomorrowUseTheScheduledDay() {
        val b = testBooking() // scheduled 12 Oct, today is 11 Oct
        assertFalse(match(b, filters(DueFilter.TODAY)))
        assertTrue(match(b, filters(DueFilter.TOMORROW)))
        assertFalse(match(b.copy(checkOut = null), filters(DueFilter.TOMORROW)))
    }

    @Test fun specificDateUsesTheCheckOutDate() {
        val b = testBooking()
        assertTrue(match(b, filters(DueFilter.SPECIFIC)))
        assertFalse(match(b, filters(DueFilter.SPECIFIC).copy(specificDate = LocalDate(2026, 10, 13))))
    }

    @Test fun rangeIncludesBothEnds() {
        val b = testBooking() // checks out 12 Oct
        assertTrue(match(b, filters(DueFilter.RANGE))) // 10..12
        assertTrue(match(b, filters(DueFilter.RANGE).copy(rangeStart = LocalDate(2026, 10, 12), rangeEnd = LocalDate(2026, 10, 12))))
        assertFalse(match(b, filters(DueFilter.RANGE).copy(rangeStart = LocalDate(2026, 10, 13), rangeEnd = LocalDate(2026, 10, 14))))
        assertFalse(match(b, filters(DueFilter.RANGE).copy(rangeStart = LocalDate(2026, 10, 9), rangeEnd = LocalDate(2026, 10, 11))))
    }

    @Test fun searchAndDueCombine() {
        val list = listOf(testBooking(), testBooking().copy(id = "b2", guestName = "Ben Eze", guestEmail = "ben@x.com", roomNumber = "202"))
        val f = filters(DueFilter.TOMORROW, query = "ben")
        assertEquals(listOf("b2"), filterGuests(list, f, "11:00", LAGOS, today).map { it.id })
        assertTrue(f.active)
        assertFalse(CheckOutFilters.initial(today).active)
    }

    @Test fun texts() {
        assertEquals("3 guest(s) currently checked in", guestCountText(3))
        assertEquals("A", guestInitial(" ada"))
        assertEquals("?", guestInitial(" "))
        assertEquals("Rita checked out Ada Obi (Room 101)", activityText("Rita", "Ada Obi", "101"))
        assertEquals("Ada has checked out. Payment sent to the Accountant for approval.", completeMessage("Ada", true))
        assertEquals("Ada has checked out. Room was already paid at check-in.", completeMessage("Ada", false))
        assertEquals("Booking not found.", bookingCheckError(false, null))
        assertEquals("Booking is not in checked-in state.", bookingCheckError(true, "checked_out"))
        assertNull(bookingCheckError(true, "checked_in"))
    }
}
