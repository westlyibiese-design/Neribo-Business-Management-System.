package com.westly.nbms.features.bookings

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** All Bookings (`bookings`, in the Bookings group) and Guests (`guests`). */
@Singleton
class BookingsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "bookings"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_BOOKINGS) { _, session -> BookingsScreen(session) },
        ScreenSpec(route = ROUTE_GUESTS) { _, session -> GuestsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_BOOKINGS,
            label = "All Bookings",
            icon = NbmsIcons.Calendar,
            group = "Bookings",
            order = 70,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.RECEPTIONIST),
            module = null
        ),
        NavSpec(
            route = ROUTE_GUESTS,
            label = "Guests",
            icon = NbmsIcons.Users,
            group = null,
            order = 80,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST),
            module = null
        )
    )

    companion object {
        const val ROUTE_BOOKINGS = "bookings"
        const val ROUTE_GUESTS = "guests"
    }
}
