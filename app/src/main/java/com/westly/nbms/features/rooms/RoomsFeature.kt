package com.westly.nbms.features.rooms

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Rooms (`rooms`) for the front office and Venues (`venues`) for the Super Admin. */
@Singleton
class RoomsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "rooms"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_ROOMS) { _, session -> RoomsScreen(session) },
        ScreenSpec(route = ROUTE_VENUES) { _, session -> VenuesScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_ROOMS,
            label = "Rooms",
            icon = NbmsIcons.Bed,
            group = null,
            order = 40,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.RECEPTIONIST),
            module = null
        ),
        NavSpec(
            route = ROUTE_VENUES,
            label = "Venues",
            icon = NbmsIcons.Landmark,
            group = null,
            order = 50,
            roles = setOf(Role.SUPER_ADMIN),
            module = null
        )
    )

    companion object {
        const val ROUTE_ROOMS = "rooms"
        const val ROUTE_VENUES = "venues"
    }
}
