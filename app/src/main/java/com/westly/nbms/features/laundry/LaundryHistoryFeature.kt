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
 * The Laundry History feature of Part 22B: ONE route in the Laundry drawer group, module LAUNDRY.
 *
 * - `laundry/history` "Laundry History" (order 151): Super Admin, Manager, Laundry Valet, Accountant, Operations Manager
 *
 * Manage Laundry (`laundry`) is registered by Part 22A's own feature.
 */
@Singleton
class LaundryHistoryFeature @Inject constructor() : NbmsFeature {
    override val id: String = "laundry-history"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_LAUNDRY_HISTORY) { _, session -> LaundryHistoryScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_LAUNDRY_HISTORY,
            label = "Laundry History",
            icon = NbmsIcons.History,
            group = "Laundry",
            order = 151,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.LAUNDRY_VALET, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER),
            module = ModuleKey.LAUNDRY
        )
    )

    companion object {
        const val ROUTE_LAUNDRY_HISTORY = "laundry/history"
    }
}
