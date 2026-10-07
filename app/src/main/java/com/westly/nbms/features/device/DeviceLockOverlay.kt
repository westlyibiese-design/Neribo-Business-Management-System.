package com.westly.nbms.features.device

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.westly.nbms.core.feature.ShellOverlay
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the app after 5 minutes without a touch, when this phone has a Device PIN.
 * Shared-device PIN sessions are never locked. Wraps the shell like the other overlays (Appendix A.6.4).
 */
@Singleton
class DeviceLockOverlay @Inject constructor(
    private val controller: DeviceLockControllerImpl
) : ShellOverlay {

    override val order: Int = 20

    @Composable
    override fun Wrap(session: SessionState.SignedIn, content: @Composable () -> Unit) {
        if (session.user.usesPin) {
            content()
            return
        }

        val locked by controller.locked.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current

        // Coming back to the app (or opening it) re-checks the saved time straight away.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) controller.check()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            while (true) {
                controller.check()
                delay(CHECK_INTERVAL_MS)
            }
        }

        // While locked, the back button must not reveal the app underneath.
        val activity = LocalContext.current.findActivity()
        BackHandler(enabled = locked) { activity?.moveTaskToBack(true) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // Watch only: touches carry on to the screen underneath.
                            awaitPointerEvent(PointerEventPass.Initial)
                            controller.onTouch()
                        }
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (locked) Modifier.clearAndSetSemantics { } else Modifier)
            ) { content() }
            if (locked) DeviceLockScreen(userName = session.user.name)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
