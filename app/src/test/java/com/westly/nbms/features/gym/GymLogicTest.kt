package com.westly.nbms.features.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GymLogicTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val day = 86_400L

    private fun status(stored: String, endOffsetSeconds: Long?) =
        GymLogic.effectiveStatus(stored, endOffsetSeconds?.let { now.plusSeconds(it) }, now)

    @Test fun suspendedAndCancelledAlwaysWin() {
        assertEquals(MembershipStatus.SUSPENDED, status("suspended", -day))
        assertEquals(MembershipStatus.SUSPENDED, status("suspended", day))
        assertEquals(MembershipStatus.CANCELLED, status("cancelled", -day))
        assertEquals(MembershipStatus.CANCELLED, status("cancelled", null))
    }

    @Test fun anEndDateInThePastIsExpiredEvenIfStoredActive() {
        assertEquals(MembershipStatus.EXPIRED, status("active", -1))
        assertEquals(MembershipStatus.EXPIRED, status("expired", -day))
    }

    @Test fun aRenewedMembershipStoredExpiredShowsActive() {
        assertEquals(MembershipStatus.ACTIVE, status("expired", day))
        assertEquals(MembershipStatus.ACTIVE, status("expired", null))
    }

    @Test fun activeInTheFutureStaysActiveAndUnknownCountsAsActive() {
        assertEquals(MembershipStatus.ACTIVE, status("active", day))
        assertEquals(MembershipStatus.ACTIVE, status("weird", day))
        assertEquals(MembershipStatus.ACTIVE, status("active", null))
    }

    @Test fun daysUntilExpiryRoundsUp() {
        assertEquals(1, GymLogic.daysUntilExpiry(now.plusSeconds(1), now))
        assertEquals(1, GymLogic.daysUntilExpiry(now.plusSeconds(day), now))
        assertEquals(2, GymLogic.daysUntilExpiry(now.plusSeconds(day + 1), now))
        assertEquals(7, GymLogic.daysUntilExpiry(now.plusSeconds(6 * day + 3600), now))
        assertEquals(0, GymLogic.daysUntilExpiry(now, now))
    }

    @Test fun daysUntilExpiryIsNegativeWhenPastAndNullWithoutADate() {
        assertEquals(-2, GymLogic.daysUntilExpiry(now.minusSeconds(2 * day), now))
        assertNull(GymLogic.daysUntilExpiry(null, now))
    }

    @Test fun durationLabelsMapToDays() {
        assertEquals(365, GymLogic.durationToDays("Annual"))
        assertEquals(365, GymLogic.durationToDays("1 YEAR pass"))
        assertEquals(90, GymLogic.durationToDays("Quarterly"))
        assertEquals(90, GymLogic.durationToDays("quarter"))
        assertEquals(7, GymLogic.durationToDays("Weekly"))
        assertEquals(1, GymLogic.durationToDays("Day pass"))
        assertEquals(30, GymLogic.durationToDays("Monthly"))
        assertEquals(30, GymLogic.durationToDays(""))
        assertEquals(30, GymLogic.durationToDays("whatever"))
        assertEquals(30, GymLogic.durationToDays(null))
    }

    @Test fun earlyRenewalKeepsTheRemainingDays() {
        val currentEnd = now.plusSeconds(5 * day)
        assertEquals(now.plusSeconds(35 * day), GymLogic.renewalEnd(currentEnd, now, 30))
    }

    @Test fun lapsedOrMissingEndStartsFromNow() {
        assertEquals(now.plusSeconds(7 * day), GymLogic.renewalEnd(now.minusSeconds(10 * day), now, 7))
        assertEquals(now.plusSeconds(7 * day), GymLogic.renewalEnd(null, now, 7))
        assertEquals(now.plusSeconds(7 * day), GymLogic.renewalEnd(now, now, 7))
    }

    @Test fun visitDurationLabels() {
        val inAt = now
        assertEquals("—", GymLogic.visitDurationLabel(null, inAt))
        assertEquals("—", GymLogic.visitDurationLabel(inAt, null))
        assertEquals("0 min", GymLogic.visitDurationLabel(inAt, inAt.plusSeconds(59)))
        assertEquals("45 min", GymLogic.visitDurationLabel(inAt, inAt.plusSeconds(45 * 60)))
        assertEquals("1h 0m", GymLogic.visitDurationLabel(inAt, inAt.plusSeconds(3600)))
        assertEquals("2h 5m", GymLogic.visitDurationLabel(inAt, inAt.plusSeconds(2 * 3600 + 5 * 60 + 30)))
    }

    @Test fun dateKeyUsesTheBusinessTimeZoneNotUtc() {
        val lateUtc = Instant.parse("2026-10-10T23:30:00Z") // already 00:30 on the 11th in Lagos (UTC+1)
        assertEquals("2026-10-11", GymLogic.dateKey(lateUtc, "Africa/Lagos"))
        assertEquals("2026-10-10", GymLogic.dateKey(lateUtc, "UTC"))
        assertEquals("2026-10-11", GymLogic.dateKey(lateUtc, "not/a-zone"))
    }

    @Test fun timeAndDateLabels() {
        val t = Instant.parse("2026-03-12T13:05:00Z")
        assertEquals("14:05", GymLogic.timeLabel(t, "Africa/Lagos"))
        assertEquals("—", GymLogic.timeLabel(null, "Africa/Lagos"))
        assertEquals("12 Mar 2026", GymLogic.dateLabel(t, "Africa/Lagos"))
    }
}
