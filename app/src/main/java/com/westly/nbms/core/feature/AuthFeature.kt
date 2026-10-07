package com.westly.nbms.core.feature

import androidx.compose.runtime.Composable
import com.westly.nbms.core.session.SessionState

interface AuthNavigator {
    fun go(route: String)
    fun back()
}

data class AuthScreenSpec(
    val route: String,
    val content: @Composable (nav: AuthNavigator) -> Unit
)

/** Multibound into a set; its screens are shown while the session is SignedOut. */
interface AuthFeature {
    val screens: List<AuthScreenSpec>
}

/**
 * Wraps the signed-in shell content (inactivity logout, device lock...).
 * Applied in ascending [order]; the outermost wrapper has the lowest order.
 */
interface ShellOverlay {
    val order: Int
    @Composable
    fun Wrap(session: SessionState.SignedIn, content: @Composable () -> Unit)
}
