package com.westly.nbms.features.laundry

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Laundry feature of Part 22A: ONE route in the Laundry drawer group, module LAUNDRY.
 *
 * - `laundry` "Manage Laundry" (order 150): Super Admin, Manager, Laundry Valet
 *
 * Laundry History (`laundry/history`) is registered by Part 22B's own feature.
 */
@Singleton
class LaundryFeature @Inject constructor() : NbmsFeature {
    override val id: String = "laundry"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_LAUNDRY) { _, session -> LaundryScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_LAUNDRY,
            label = "Manage Laundry",
            icon = NbmsIcons.Laundry,
            group = "Laundry",
            order = 150,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.LAUNDRY_VALET),
            module = ModuleKey.LAUNDRY
        )
    )

    companion object {
        const val ROUTE_LAUNDRY = "laundry"
    }
}
