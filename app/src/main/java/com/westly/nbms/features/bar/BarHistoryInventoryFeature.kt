package com.westly.nbms.features.bar

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The second Bar feature (Part 21B): two routes in the Bar drawer group, module BAR.
 *
 * - `bar/sales-history` "Sales History" (order 141): Super Admin, Bar Attendant, Manager, Accountant, Operations Manager
 * - `bar-inventory` "Bar Inventory" (order 143): Super Admin, Manager, Accountant, Bar Attendant
 *
 * New Sale and Drinks Menu are registered by Part 21A's `BarFeature`.
 */
@Singleton
class BarHistoryInventoryFeature @Inject constructor() : NbmsFeature {
    override val id: String = "bar-history-inventory"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_BAR_SALES_HISTORY) { _, session -> BarSalesHistoryScreen(session) },
        ScreenSpec(ROUTE_BAR_INVENTORY) { _, session -> BarInventoryScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_BAR_SALES_HISTORY,
            label = "Sales History",
            icon = NbmsIcons.History,
            group = "Bar",
            order = 141,
            roles = setOf(Role.SUPER_ADMIN, Role.BAR_ATTENDANT, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER),
            module = ModuleKey.BAR
        ),
        NavSpec(
            route = ROUTE_BAR_INVENTORY,
            label = "Bar Inventory",
            icon = NbmsIcons.Package,
            group = "Bar",
            order = 143,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.BAR_ATTENDANT),
            module = ModuleKey.BAR
        )
    )

    companion object {
        const val ROUTE_BAR_SALES_HISTORY = "bar/sales-history"
        const val ROUTE_BAR_INVENTORY = "bar-inventory"
    }
}
