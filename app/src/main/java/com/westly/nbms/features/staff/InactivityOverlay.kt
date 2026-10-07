package com.westly.nbms.features.staff

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellOverlay
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 15 minutes. */
const val INACTIVITY_TIMEOUT_MS: Long = 15L * 60L * 1000L

/** A clock that can be swapped in tests. Values are milliseconds from any fixed starting point. */
fun interface InactivityClock {
    fun nowMs(): Long
}

/** Counts time that keeps running while the phone sleeps. */
object SystemInactivityClock : InactivityClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Decides when a PIN session has been idle for too long. Pure logic, so it can be tested with a fake clock.
 * Idle time counts from the last touch or key press; time spent in the background counts too.
 */
class InactivityTracker(
    private val clock: InactivityClock,
    val timeoutMs: Long = INACTIVITY_TIMEOUT_MS
) {
    private var lastActivityMs: Long = clock.nowMs()
    private var backgroundedAtMs: Long? = null

    /** Call on any touch or key event. */
    fun onActivity() {
        lastActivityMs = clock.nowMs()
    }

    fun idleMs(): Long = clock.nowMs() - lastActivityMs

    fun remainingMs(): Long = (timeoutMs - idleMs()).coerceAtLeast(0L)

    fun isExpired(): Boolean = idleMs() >= timeoutMs

    /** The app left the screen. */
    fun onBackground() {
        if (backgroundedAtMs == null) backgroundedAtMs = clock.nowMs()
    }

    /** The app came back. True when it was away for more than the timeout, or the idle time ran out. */
    fun onForeground(): Boolean {
        val left = backgroundedAtMs
        backgroundedAtMs = null
        val awayMs = if (left != null) clock.nowMs() - left else 0L
        return awayMs > timeoutMs || isExpired()
    }
}

/**
 * Shared-device protection: when the signed-in person uses a PIN, 15 minutes without a touch (or 15 minutes
 * in the background) signs them out. Email-login accounts are not affected.
 */
@Singleton
class InactivityOverlay @Inject constructor(
    private val sessionManager: SessionManager,
    private val toast: ToastController
) : ShellOverlay {

    override val order: Int = 10

    @Composable
    override fun Wrap(session: SessionState.SignedIn, content: @Composable () -> Unit) {
        if (!session.user.usesPin) {
            content()
            return
        }
        val scope = rememberCoroutineScope()
        InactivityGuard(
            userId = session.user.uid,
            clock = SystemInactivityClock,
            onExpired = {
                toast.show("Signed out due to inactivity", ToastType.Info)
                scope.launch { sessionManager.signOut() }
            },
            content = content
        )
    }
}

@Composable
internal fun InactivityGuard(
    userId: String,
    clock: InactivityClock,
    onExpired: () -> Unit,
    content: @Composable () -> Unit
) {
    val tracker = remember(userId) { InactivityTracker(clock) }
    val fired = remember(userId) { AtomicBoolean(false) }
    val latestOnExpired by rememberUpdatedState(onExpired)
    val expire = remember(tracker) {
        {
            if (fired.compareAndSet(false, true)) latestOnExpired()
        }
    }

    // Wakes up now and then and checks the clock (the clock keeps counting while the phone sleeps).
    LaunchedEffect(tracker) {
        while (isActive) {
            val remaining = tracker.remainingMs()
            if (remaining <= 0L) {
                expire()
                break
            }
            delay(remaining.coerceIn(500L, 30_000L))
        }
    }

    // Coming back after a long time away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> tracker.onBackground()
                Lifecycle.Event.ON_START -> {
                    if (tracker.onForeground()) expire()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .pointerInput(tracker) {
                // Watch every touch without consuming it, so nothing underneath is affected.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        tracker.onActivity()
                    }
                }
            }
            .onPreviewKeyEvent {
                tracker.onActivity()
                false
            }
    ) {
        content()
    }
}
