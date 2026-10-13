package com.westly.nbms.core.sync

import android.util.Log
import com.google.firebase.firestore.Source
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.NetworkState
import com.westly.nbms.core.session.SecureStore
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.notifications.PushRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OfflineSync"

/** The business data a phone needs to keep working with no internet. Downloaded in the background when online. */
private val PRIMED_COLLECTIONS = listOf(
    "rooms", "bookings", "booking_dates", "checkins", "checkouts", "guests", "payments", "expenses",
    "inventory", "sales", "orders", "bar_orders", "laundry_requests", "housekeeping_tasks",
    "room_assignments", "room_assignment_groups", "shifts", "attendance", "users", "venues", "settings",
    "cms_content", "lost_found", "maintenance", "tasks", "gym_members", "gym_attendance", "reviews",
    "notifications"
)

/** These grow without end, so only the newest-looking slice is downloaded up front. */
private val LARGE_COLLECTIONS = setOf("attendance", "notifications", "gym_attendance")
private const val LARGE_LIMIT = 500L

/** A full download is repeated at most this often (live screens keep their own data fresh anyway). */
private const val PRIME_EVERY_MS = 6L * 60L * 60L * 1000L
private const val KEY_LAST_PRIME = "last_offline_prime"

/**
 * Runs in the background for the whole life of the app:
 *  - when the phone is online and someone is signed in, it downloads the business data once in a while so the
 *    app already has what it needs when the internet drops;
 *  - when the internet comes back it makes sure this phone's push registration is current.
 * Saved changes upload by themselves (Firestore does that); [SyncTracker] only counts them for the banner.
 */
@Singleton
class OfflineSyncCoordinator @Inject constructor(
    private val session: SessionManager,
    private val firestore: BusinessFirestore,
    private val push: PushRegistrar,
    private val store: SecureStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(session.state, NetworkState.online) { state, online ->
                if (state is SessionState.SignedIn && online) state.user.uid else null
            }.distinctUntilChanged().collectLatest { uid ->
                if (uid == null) return@collectLatest
                delay(3_000L) // let the connection settle and the first screen load first
                try {
                    push.registerCurrentToken()
                    primeCache(uid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Background download stopped: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun primeCache(uid: String) {
        val now = System.currentTimeMillis()
        val last = store.getString(KEY_LAST_PRIME)?.split("|")
        if (last != null && last.size == 2 && last[0] == uid) {
            val at = last[1].toLongOrNull() ?: 0L
            if (now - at < PRIME_EVERY_MS) return
        }
        var allOk = true
        for (name in PRIMED_COLLECTIONS) {
            if (!NetworkState.isOnline) return
            try {
                var query: com.google.firebase.firestore.Query = firestore.collection(name)
                if (name in LARGE_COLLECTIONS) query = query.limit(LARGE_LIMIT)
                query.get(Source.SERVER).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A collection this role may not read is simply skipped.
                allOk = allOk && e is com.google.firebase.firestore.FirebaseFirestoreException &&
                    e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            }
        }
        if (allOk) store.putString(KEY_LAST_PRIME, "$uid|${System.currentTimeMillis()}")
    }
}
