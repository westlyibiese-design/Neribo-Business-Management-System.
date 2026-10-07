package com.westly.nbms.features.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val LOCK_AFTER_MS: Long = 5L * 60L * 1000L
const val CHECK_INTERVAL_MS: Long = 15L * 1000L
internal const val ACTIVITY_WRITE_THROTTLE_MS: Long = 5L * 1000L

/** Wall-clock milliseconds. A fake one is used in tests. */
fun interface WallClock {
    fun nowMs(): Long
}

object SystemWallClock : WallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Where the last-activity time is kept (the encrypted SecureStore in the app, memory in tests). */
interface ActivityStore {
    fun read(uid: String): Long?
    fun write(uid: String, ms: Long)
    fun remove(uid: String)
}

/**
 * The lock rules, with no Android in them so they can be tested with a fake clock.
 * The saved time of the last touch decides everything; "unlocked" is never saved.
 */
class DeviceLockEngine(
    private val clock: WallClock,
    private val store: ActivityStore,
    private val lockAfterMs: Long = LOCK_AFTER_MS,
    private val writeThrottleMs: Long = ACTIVITY_WRITE_THROTTLE_MS
) {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _hasPin = MutableStateFlow(false)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    private var uid: String? = null
    private var lastWriteMs: Long? = null

    /**
     * Starts watching [uid]. [freshSignIn] = the person has just typed their password, so the clock starts now.
     * Otherwise (app restarted with a saved session) the saved time decides, and no saved time means locked.
     */
    @Synchronized
    fun beginSession(uid: String, hasPin: Boolean, freshSignIn: Boolean) {
        this.uid = uid
        lastWriteMs = null
        if (freshSignIn) stamp(force = true)
        _hasPin.value = hasPin
        _locked.value = hasPin && shouldLock(uid)
    }

    /** The server told us whether this phone has a PIN. */
    @Synchronized
    fun setHasPin(has: Boolean) {
        val u = uid ?: return
        _hasPin.value = has
        _locked.value = has && (_locked.value || shouldLock(u))
    }

    /** Runs on start, on return to the app and every [CHECK_INTERVAL_MS]. It can lock, never unlock. */
    @Synchronized
    fun check() {
        val u = uid ?: return
        if (!_hasPin.value) {
            _locked.value = false
            return
        }
        if (!_locked.value && shouldLock(u)) _locked.value = true
    }

    /** Any touch. Saves the time at most once every few seconds. Ignored while locked. */
    @Synchronized
    fun onTouch() {
        if (uid == null || !_hasPin.value || _locked.value) return
        stamp(force = false)
    }

    @Synchronized
    fun markUnlocked() {
        if (uid == null) return
        stamp(force = true)
        _locked.value = false
    }

    @Synchronized
    fun lockNow() {
        if (uid == null || !_hasPin.value) return
        _locked.value = true
    }

    /** A PIN was just saved on this phone: it counts as activity, so the app does not lock right away. */
    @Synchronized
    fun onPinSaved() {
        if (uid == null) return
        _hasPin.value = true
        stamp(force = true)
        _locked.value = false
    }

    /** This phone's registration was removed: no PIN, no lock. */
    @Synchronized
    fun onDeviceRemoved() {
        val u = uid ?: return
        store.remove(u)
        lastWriteMs = null
        _hasPin.value = false
        _locked.value = false
    }

    /** Signed out (or a shared-device PIN session): forget everything about this person. */
    @Synchronized
    fun endSession() {
        uid?.let { store.remove(it) }
        uid = null
        lastWriteMs = null
        _hasPin.value = false
        _locked.value = false
    }

    private fun shouldLock(uid: String): Boolean {
        val last = store.read(uid) ?: return true
        val diff = clock.nowMs() - last
        return diff < 0 || diff >= lockAfterMs
    }

    private fun stamp(force: Boolean) {
        val u = uid ?: return
        val now = clock.nowMs()
        val last = lastWriteMs
        if (!force && last != null && now >= last && now - last < writeThrottleMs) return
        store.write(u, now)
        lastWriteMs = now
    }
}
