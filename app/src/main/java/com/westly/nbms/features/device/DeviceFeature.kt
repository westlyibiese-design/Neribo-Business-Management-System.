package com.westly.nbms.features.device

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Device Settings: open to every signed-in role, no module switch (Appendix A.6.3). */
@Singleton
class DeviceFeature @Inject constructor() : NbmsFeature {
    override val id: String = "device"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE) { _, session -> DeviceSettingsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE,
            label = "Device Settings",
            icon = Icons.Outlined.PhoneAndroid,
            group = null,
            order = 900,
            roles = null,
            module = null
        )
    )

    companion object {
        const val ROUTE = "device-settings"
    }
}
