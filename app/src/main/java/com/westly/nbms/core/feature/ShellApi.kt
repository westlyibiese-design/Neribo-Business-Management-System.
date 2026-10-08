package com.westly.nbms.core.feature

import androidx.compose.runtime.Composable
import com.westly.nbms.core.session.SessionState

/**
 * A small control shown on the right of the top bar (for example the notification bell, Phase 10).
 * Multibound with `@Binds @IntoSet`. Shown in ascending [order].
 */
interface TopBarAction {
    val order: Int

    @Composable
    fun Content(session: SessionState.SignedIn)
}

/**
 * Opens a screen by a Westly-style link ("/admin/bookings") or a bare route ("bookings").
 * Owned by Phase 7 and injectable as a singleton. An unknown route, or one the person may not open,
 * goes to the Dashboard instead.
 */
interface ShellNavigator {
    fun open(link: String)
}
