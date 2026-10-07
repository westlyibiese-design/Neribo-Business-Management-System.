package com.westly.nbms.features.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockEngineTest {

    private class FakeClock(var now: Long = 10_000_000L) : WallClock {
        override fun nowMs(): Long = now
        fun advanceMs(ms: Long) { now += ms }
        fun advanceMinutes(m: Long) { now += m * 60_000L }
    }

    private class MemoryStore : ActivityStore {
        val map = mutableMapOf<String, Long>()
        var writes = 0
        override fun read(uid: String): Long? = map[uid]
        override fun write(uid: String, ms: Long) { map[uid] = ms; writes++ }
        override fun remove(uid: String) { map.remove(uid) }
    }

    private val clock = FakeClock()
    private val store = MemoryStore()
    private val engine = DeviceLockEngine(clock, store)

    @Test fun constantsMatchTheSpec() {
        assertEquals(5L * 60_000L, LOCK_AFTER_MS)
        assertEquals(15_000L, CHECK_INTERVAL_MS)
    }

    @Test fun noPinMeansNeverLocked() {
        engine.beginSession("u1", hasPin = false, freshSignIn = false)
        clock.advanceMinutes(60)
        engine.check()
        assertFalse(engine.locked.value)
    }

    @Test fun restartWithPinAndNoSavedTimeStartsLocked() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        assertTrue(engine.locked.value)
    }

    @Test fun freshSignInStartsUnlockedAndStampsNow() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        assertFalse(engine.locked.value)
        assertEquals(clock.now, store.map["u1"])
    }

    @Test fun restartWithRecentTimeStaysUnlocked() {
        store.map["u1"] = clock.now - 60_000L
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        assertFalse(engine.locked.value)
    }

    @Test fun restartWithOldTimeIsLocked() {
        store.map["u1"] = clock.now - 5L * 60_000L
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        assertTrue(engine.locked.value)
    }

    @Test fun locksExactlyAtFiveMinutes() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        clock.advanceMinutes(4)
        clock.advanceMs(59_999L)
        engine.check()
        assertFalse(engine.locked.value)
        clock.advanceMs(1L)
        engine.check()
        assertTrue(engine.locked.value)
    }

    @Test fun touchesKeepItUnlocked() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        repeat(10) {
            clock.advanceMinutes(2)
            engine.onTouch()
            engine.check()
        }
        assertFalse(engine.locked.value)
    }

    @Test fun touchWritesAreThrottled() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        val before = store.writes
        repeat(20) {
            clock.advanceMs(100L)
            engine.onTouch()
        }
        assertEquals(before, store.writes)
        clock.advanceMs(5_000L)
        engine.onTouch()
        assertEquals(before + 1, store.writes)
    }

    @Test fun touchesWhileLockedAreIgnored() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        assertTrue(engine.locked.value)
        clock.advanceMs(60_000L)
        engine.onTouch()
        assertTrue(engine.locked.value)
        assertNull(store.map["u1"])
    }

    @Test fun checkNeverUnlocks() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        engine.check()
        assertTrue(engine.locked.value)
    }

    @Test fun markUnlockedClearsLockAndRestartsTheClock() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        engine.markUnlocked()
        assertFalse(engine.locked.value)
        clock.advanceMinutes(4)
        engine.check()
        assertFalse(engine.locked.value)
    }

    @Test fun clockMovedBackwardsLocks() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        clock.now -= 60_000L
        engine.check()
        assertTrue(engine.locked.value)
    }

    @Test fun lockNowNeedsAPin() {
        engine.beginSession("u1", hasPin = false, freshSignIn = true)
        engine.lockNow()
        assertFalse(engine.locked.value)
        engine.setHasPin(true)
        engine.lockNow()
        assertTrue(engine.locked.value)
    }

    @Test fun savingAPinDoesNotLockImmediately() {
        engine.beginSession("u1", hasPin = false, freshSignIn = false)
        engine.onPinSaved()
        assertTrue(engine.hasPin.value)
        assertFalse(engine.locked.value)
    }

    @Test fun removingTheDeviceUnlocksAndForgetsTheTime() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        engine.onDeviceRemoved()
        assertFalse(engine.locked.value)
        assertFalse(engine.hasPin.value)
        assertNull(store.map["u1"])
    }

    @Test fun signOutForgetsEverything() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        engine.endSession()
        assertFalse(engine.locked.value)
        assertFalse(engine.hasPin.value)
        assertNull(store.map["u1"])
    }

    @Test fun serverSayingNoPinUnlocks() {
        engine.beginSession("u1", hasPin = true, freshSignIn = false)
        engine.setHasPin(false)
        assertFalse(engine.locked.value)
    }

    @Test fun twoPeopleKeepSeparateTimes() {
        engine.beginSession("u1", hasPin = true, freshSignIn = true)
        engine.endSession()
        engine.beginSession("u2", hasPin = true, freshSignIn = false)
        assertTrue(engine.locked.value)
    }
}
