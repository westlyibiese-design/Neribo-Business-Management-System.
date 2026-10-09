package com.westly.nbms.features.checkout

import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendStayRulesTest {

    @Test fun rateIsThePriceOnTheBookingWhenItHasOne() {
        assertEquals(25_000.0, nightlyRate(25_000.0, 99.0, 3, 10.0), 0.0)
    }

    @Test fun rateFallsBackToTotalOverNights() {
        assertEquals(20_000.0, nightlyRate(null, 60_000.0, 3, 10.0), 0.0)
        assertEquals(20_000.0, nightlyRate(0.0, 60_000.0, 3, 10.0), 0.0)
    }

    @Test fun rateFallsBackToTheRoomPriceThenZero() {
        assertEquals(30_000.0, nightlyRate(null, 60_000.0, null, 30_000.0), 0.0)
        assertEquals(30_000.0, nightlyRate(null, 60_000.0, 0, 30_000.0), 0.0)
        assertEquals(0.0, nightlyRate(null, 60_000.0, null, null), 0.0)
    }

    @Test fun additionalAmountIsRateTimesNights() {
        assertEquals(75_000.0, additionalAmount(25_000.0, 3), 0.0)
        assertEquals(0.0, additionalAmount(0.0, 2), 0.0)
    }

    @Test fun nightFieldNonNumericOrZeroCountsAsOne() {
        assertEquals(1, parseExtraNights(""))
        assertEquals(1, parseExtraNights("abc"))
        assertEquals(1, parseExtraNights("0"))
        assertEquals(4, parseExtraNights(" 4 "))
        assertEquals(-2, parseExtraNights("-2"))
    }

    @Test fun addingDaysKeepsTheTimeOfDay() {
        val out = at("2026-10-12T10:00:00Z") // 11:00 in Lagos
        assertEquals(at("2026-10-14T10:00:00Z"), addDays(out, 2, LAGOS))
        assertEquals(at("2026-11-01T10:00:00Z"), addDays(at("2026-10-30T10:00:00Z"), 2, LAGOS))
    }

    @Test fun dateTexts() {
        assertEquals("Oct 12, 2026", mmmDYyyy(LocalDate(2026, 10, 12)))
        assertEquals("Jan 5, 2027", mmmDYyyy(LocalDate(2027, 1, 5)))
        assertEquals("Oct 12, 2026", mmmDYyyy(at("2026-10-12T10:00:00Z"), LAGOS))
        assertEquals(2, nightsBetween(at("2026-10-10T13:00:00Z"), at("2026-10-12T10:00:00Z"), LAGOS))
        assertNull(nightsBetween(null, at("2026-10-12T10:00:00Z"), LAGOS))
    }

    @Test fun textsMatchWestly() {
        assertEquals("2 night(s)", extensionText(2))
        assertEquals("Room 101 now checks out Oct 14, 2026.", extendedMessage("101", "Oct 14, 2026"))
        assertEquals("Rita extended Ada Obi's stay in Room 101 by 2 night(s)", extendActivityText("Rita", "Ada Obi", "101", 2))
        assertEquals("1 night", chipText(1))
        assertEquals("3 nights", chipText(3))
    }

    @Test fun onlySuperAdminAndReceptionistMayExtend() {
        assertTrue(mayExtend(Role.SUPER_ADMIN))
        assertTrue(mayExtend(Role.RECEPTIONIST))
        assertFalse(mayExtend(Role.MANAGER))
        assertFalse(mayExtend(Role.OPERATIONS_MANAGER))
        assertFalse(mayExtend(null))
    }

    @Test fun transactionChecks() {
        assertEquals("Booking not found.", stayCheckError(false, null))
        assertEquals("This guest is no longer checked in — the stay can't be extended.", stayCheckError(true, "checked_out"))
        assertNull(stayCheckError(true, "checked_in"))
        assertEquals("Room not found.", roomAssignmentError(false, null, "b1"))
        assertEquals(
            "This room is no longer assigned to this guest's booking — please refresh and try again.",
            roomAssignmentError(true, "other", "b1")
        )
        assertNull(roomAssignmentError(true, "b1", "b1"))
        assertNull(roomAssignmentError(true, null, "b1"))
    }
}
