package com.westly.nbms.features.staff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityTrackerTest {

    private class FakeClock(var now: Long = 1_000L) : InactivityClock {
        override fun nowMs(): Long = now
        fun advanceMinutes(m: Long) { now += m * 60_000L }
        fun advanceMs(ms: Long) { now += ms }
    }

    @Test fun timeoutIsFifteenMinutes() {
        assertEquals(15L * 60_000L, INACTIVITY_TIMEOUT_MS)
    }

    @Test fun notExpiredJustBeforeFifteenMinutes() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        clock.advanceMinutes(14)
        clock.advanceMs(59_999L)
        assertFalse(tracker.isExpired())
        assertEquals(1L, tracker.remainingMs())
    }

    @Test fun expiredAtFifteenMinutes() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        clock.advanceMinutes(15)
        assertTrue(tracker.isExpired())
        assertEquals(0L, tracker.remainingMs())
    }

    @Test fun activityRestartsTheTimer() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        clock.advanceMinutes(10)
        tracker.onActivity()
        clock.advanceMinutes(10)
        assertFalse(tracker.isExpired())
        assertEquals(5L * 60_000L, tracker.remainingMs())
        clock.advanceMinutes(5)
        assertTrue(tracker.isExpired())
    }

    @Test fun backgroundLongerThanFifteenMinutesExpires() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        tracker.onActivity()
        tracker.onBackground()
        clock.advanceMinutes(15)
        clock.advanceMs(1L)
        assertTrue(tracker.onForeground())
    }

    @Test fun shortTripToTheBackgroundIsFine() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        clock.advanceMinutes(2)
        tracker.onActivity()
        tracker.onBackground()
        clock.advanceMinutes(5)
        assertFalse(tracker.onForeground())
    }

    @Test fun returningWithNoBackgroundRecordOnlyChecksIdleTime() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        assertFalse(tracker.onForeground())
        clock.advanceMinutes(16)
        assertTrue(tracker.onForeground())
    }

    @Test fun backgroundMarkerIsClearedAfterReturn() {
        val clock = FakeClock()
        val tracker = InactivityTracker(clock)
        tracker.onBackground()
        clock.advanceMinutes(3)
        assertFalse(tracker.onForeground())
        tracker.onActivity()
        clock.advanceMinutes(20)
        // No new background trip happened, so only the idle rule applies (and it has run out).
        assertTrue(tracker.onForeground())
    }
}
