package com.westly.nbms.features.device

import com.westly.nbms.core.session.SecureStore
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal class SecureStoreActivityStore(private val store: SecureStore) : ActivityStore {
    override fun read(uid: String): Long? = store.getString(key(uid))?.toLongOrNull()
    override fun write(uid: String, ms: Long) = store.putString(key(uid), ms.toString())
    override fun remove(uid: String) = store.remove(key(uid))

    companion object {
        fun key(uid: String) = "device_lock_last_activity_$uid"
    }
}

/**
 * Keeps the lock state for the signed-in person. It watches the session itself:
 * email sign-ins get the lock (when this phone has a PIN), shared-device PIN sessions never do.
 */
@Singleton
class DeviceLockControllerImpl @Inject constructor(
    private val session: SessionManager,
    private val store: SecureStore,
    private val api: DeviceApi,
    private val deviceIds: DeviceIdProvider
) : DeviceLockController {

    private val engine = DeviceLockEngine(SystemWallClock, SecureStoreActivityStore(store))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentUid: String? = null
    private var sawSignedOut = false

    override val locked: StateFlow<Boolean> = engine.locked

    /** True when this phone has a Device PIN for the signed-in person. */
    val hasPin: StateFlow<Boolean> = engine.hasPin

    init {
        scope.launch { session.state.collect { handle(it) } }
    }

    private fun handle(state: SessionState) {
        when (state) {
            is SessionState.SignedIn -> {
                val user = state.user
                if (user.usesPin) {
                    if (currentUid != null) engine.endSession()
                    currentUid = null
                } else if (currentUid != user.uid) {
                    currentUid = user.uid
                    engine.beginSession(
                        uid = user.uid,
                        hasPin = store.getString(pinCacheKey(user.uid)) == "1",
                        freshSignIn = sawSignedOut
                    )
                    scope.launch { refresh() }
                }
            }
            is SessionState.SignedOut -> {
                sawSignedOut = true
                engine.endSession()
                currentUid = null
            }
            else -> Unit
        }
    }

    /** Asks the server whether this phone has a PIN and remembers the answer. A failed call keeps the last known answer. */
    suspend fun refresh() {
        val uid = currentUid ?: return
        val devices = api.list().getOrNull() ?: return
        if (currentUid != uid) return
        val mine = deviceIds.get()
        val has = devices.any { it.deviceId == mine && it.hasPin }
        store.putString(pinCacheKey(uid), if (has) "1" else "0")
        engine.setHasPin(has)
    }

    fun onTouch() = engine.onTouch()

    fun check() = engine.check()

    fun onUnlocked() = engine.markUnlocked()

    fun onPinSaved() {
        currentUid?.let { store.putString(pinCacheKey(it), "1") }
        engine.onPinSaved()
    }

    fun onDeviceRemoved() {
        currentUid?.let { store.putString(pinCacheKey(it), "0") }
        engine.onDeviceRemoved()
    }

    override fun lockNow() = engine.lockNow()

    private fun pinCacheKey(uid: String) = "device_lock_has_pin_$uid"
}
