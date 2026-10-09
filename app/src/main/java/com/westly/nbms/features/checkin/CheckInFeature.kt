package com.westly.nbms.features.checkin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Check-In (`checkin`, Westly's Walk-In page) in the Bookings group, for the Super Admin and the Receptionist. */
@Singleton
class CheckInFeature @Inject constructor() : NbmsFeature {
    override val id: String = "checkin"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_CHECKIN) { _, session -> WalkInScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_CHECKIN,
            label = "Check-In",
            icon = Icons.AutoMirrored.Outlined.Login,
            group = "Bookings",
            order = 72,
            roles = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST),
            module = null
        )
    )

    companion object {
        const val ROUTE_CHECKIN = "checkin"
    }
}
