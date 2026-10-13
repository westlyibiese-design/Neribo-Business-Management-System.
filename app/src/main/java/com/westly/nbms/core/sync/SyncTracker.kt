package com.westly.nbms.core.sync

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.westly.nbms.core.data.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** One change that the server refused after it was saved on the phone (for example by a database rule). */
data class SyncFailure(val id: Long, val label: String, val message: String)

/** [pending] = changes saved on this phone that have not reached the server yet. */
data class SyncStatus(val online: Boolean, val pending: Int, val failures: List<SyncFailure>)

/** How long a save waits for the server before it is treated as "saved on the phone, uploading later". */
private const val WRITE_ACK_TIMEOUT_MS = 6_000L
private const val TAG = "SyncTracker"

/**
 * Keeps count of changes waiting to upload and remembers any the server refused, so the app can show
 * "Offline - 3 changes waiting" and never lose track silently. Plain object so it needs no wiring.
 */
object SyncTracker {
    private val pending = MutableStateFlow(0)
    private val failures = MutableStateFlow<List<SyncFailure>>(emptyList())
    private var nextId = 1L

    val status: Flow<SyncStatus> = combine(NetworkState.online, pending, failures) { online, p, f ->
        SyncStatus(online, p, f)
    }

    /** Called once with the live Firestore: counts writes that were saved on the phone in an earlier session. */
    @Synchronized
    fun attach(firestore: FirebaseFirestore) {
        if (attached) return
        attached = true
        try {
            pending.update { it + 1 }
            firestore.waitForPendingWrites().addOnCompleteListener { pending.update { n -> (n - 1).coerceAtLeast(0) } }
        } catch (e: Exception) {
            pending.update { n -> (n - 1).coerceAtLeast(0) }
        }
    }

    private var attached = false

    fun dismissFailures() {
        failures.value = emptyList()
    }

    internal fun trackPending(task: Task<*>) {
        pending.update { it + 1 }
        task.addOnCompleteListener { pending.update { n -> (n - 1).coerceAtLeast(0) } }
    }

    /** Nobody is waiting on this write, so if the server refuses it later the banner has to say so. */
    internal fun watchFailure(task: Task<*>, label: String) {
        task.addOnCompleteListener { t ->
            val error = t.exception ?: return@addOnCompleteListener
            Log.w(TAG, "A saved change was refused by the server: ${error.javaClass.simpleName}")
            val message = error.message?.takeIf { it.isNotBlank() } ?: "The server refused this change."
            synchronized(this) {
                failures.update { it + SyncFailure(nextId++, label, message) }
            }
        }
    }

    /** Offline: the write is already saved on the phone. Do not wait for the server. */
    fun trackDetached(task: Task<*>, label: String) {
        trackPending(task)
        watchFailure(task, label)
    }

    /**
     * Waits for a write to reach the server only when the phone is online, and only for a few seconds.
     * Offline (or on a very slow connection) the write is kept safely on the phone and uploads by itself.
     * A refusal while we are waiting still throws, exactly as before.
     */
    suspend fun awaitWrite(task: Task<*>, label: String) {
        trackPending(task)
        if (!NetworkState.isOnline) {
            watchFailure(task, label)
            return
        }
        val done = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
            @Suppress("UNCHECKED_CAST")
            (task as Task<Any?>).await()
            true
        }
        if (done == null) watchFailure(task, label)
    }
}

/** Use instead of `.await()` on any Firestore or Realtime Database write. */
suspend fun Task<*>.awaitWrite(label: String = "A saved change") {
    SyncTracker.awaitWrite(this, label)
}
