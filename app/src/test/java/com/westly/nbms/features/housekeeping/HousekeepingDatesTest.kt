package com.westly.nbms.features.housekeeping

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HousekeepingDatesTest {
    private val lagos = ZoneId.of("Africa/Lagos")          // UTC+1, no daylight saving
    private val newYork = ZoneId.of("America/New_York")    // west of UTC

    @Test fun middayIsTheSameDayEverywhere() {
        val noon = Instant.parse("2026-03-05T12:00:00Z")
        assertEquals("2026-03-05", dateKeyInZone(noon, ZoneId.of("UTC")))
        assertEquals("2026-03-05", dateKeyInZone(noon, lagos))
        assertEquals("2026-03-05", dateKeyInZone(noon, newYork))
    }

    @Test fun justBeforeMidnightUtcIsAlreadyTomorrowInLagos() {
        val t = Instant.parse("2026-03-04T23:30:00Z")
        assertEquals("2026-03-04", dateKeyInZone(t, ZoneId.of("UTC")))
        assertEquals("2026-03-05", dateKeyInZone(t, lagos))
    }

    @Test fun justAfterMidnightUtcIsStillYesterdayWestOfUtc() {
        val t = Instant.parse("2026-03-05T00:30:00Z")
        assertEquals("2026-03-05", dateKeyInZone(t, ZoneId.of("UTC")))
        assertEquals("2026-03-04", dateKeyInZone(t, newYork))
    }

    @Test fun lagosMidnightBoundary() {
        assertEquals("2026-03-04", dateKeyInZone(Instant.parse("2026-03-04T22:59:59Z"), lagos))
        assertEquals("2026-03-05", dateKeyInZone(Instant.parse("2026-03-04T23:00:00Z"), lagos))
    }

    @Test fun monthAndYearAreZeroPadded() {
        assertEquals("2026-01-02", dateKeyInZone(Instant.parse("2026-01-02T10:00:00Z"), lagos))
        assertEquals("2027-01-01", dateKeyInZone(Instant.parse("2026-12-31T23:30:00Z"), lagos))
    }
}
