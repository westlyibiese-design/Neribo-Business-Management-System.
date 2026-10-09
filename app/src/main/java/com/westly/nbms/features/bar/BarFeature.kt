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
 * The Bar feature of Part 21A: two routes in the Bar drawer group, module BAR.
 *
 * - `bar/new-sale` "New Sale" (order 140): Super Admin, Bar Attendant
 * - `bar-menu` "Drinks Menu" (order 142): Super Admin, Manager
 *
 * Sales History (`bar/sales-history`) and Bar Inventory (`bar-inventory`) are registered by Part 21B's own feature.
 */
@Singleton
class BarFeature @Inject constructor() : NbmsFeature {
    override val id: String = "bar"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_BAR_NEW_SALE) { _, session -> BarNewSaleScreen(session) },
        ScreenSpec(ROUTE_BAR_MENU) { _, session -> DrinksMenuScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_BAR_NEW_SALE,
            label = "New Sale",
            icon = NbmsIcons.Wine,
            group = "Bar",
            order = 140,
            roles = setOf(Role.SUPER_ADMIN, Role.BAR_ATTENDANT),
            module = ModuleKey.BAR
        ),
        NavSpec(
            route = ROUTE_BAR_MENU,
            label = "Drinks Menu",
            icon = NbmsIcons.Wine,
            group = "Bar",
            order = 142,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = ModuleKey.BAR
        )
    )

    companion object {
        const val ROUTE_BAR_NEW_SALE = "bar/new-sale"
        const val ROUTE_BAR_MENU = "bar-menu"
    }
}
