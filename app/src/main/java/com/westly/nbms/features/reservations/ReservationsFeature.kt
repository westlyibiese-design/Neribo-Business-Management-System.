package com.westly.nbms.features.reservations

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Room Reservations (`room-reservations`) in the Bookings group, for the Super Admin and the Receptionist. */
@Singleton
class ReservationsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "room-reservations"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_RESERVATIONS) { _, session -> ReservationsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_RESERVATIONS,
            label = "Room Reservations",
            icon = NbmsIcons.Globe,
            group = "Bookings",
            order = 71,
            roles = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST),
            module = null
        )
    )

    companion object {
        const val ROUTE_RESERVATIONS = "room-reservations"
    }
}
