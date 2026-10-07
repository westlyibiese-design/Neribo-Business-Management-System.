package com.westly.nbms.features.device

import androidx.compose.runtime.Composable
import com.westly.nbms.core.session.SessionState

/** An extra card on the Device Settings screen. Multibound with `@IntoSet`; shown after the built-in cards, sorted by [order]. */
interface DeviceSettingsSection {
    val order: Int

    @Composable
    fun Content(session: SessionState.SignedIn)
}
