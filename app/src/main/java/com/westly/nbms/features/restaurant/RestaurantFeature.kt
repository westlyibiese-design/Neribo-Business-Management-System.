package com.westly.nbms.features.restaurant

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Restaurant feature, complete: three routes in the Restaurant drawer group, module RESTAURANT.
 *
 * - `orders/new` "New Order" (order 130): Super Admin, Waiter
 * - `orders/history` "Order History" (order 131): Super Admin, Waiter, Manager, Accountant, Operations Manager
 * - `restaurant-menu` "Menu Management" (order 132, Phase 20a): Super Admin, Manager
 *
 * This file replaces Phase 20a's version of the same name.
 */
@Singleton
class RestaurantFeature @Inject constructor() : NbmsFeature {
    override val id: String = "restaurant"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_NEW_ORDER) { _, session -> NewOrderScreen(session) },
        ScreenSpec(ROUTE_ORDER_HISTORY) { _, session -> OrderHistoryScreen(session) },
        ScreenSpec(ROUTE_MENU) { _, session -> MenuManagementScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_NEW_ORDER,
            label = "New Order",
            icon = NbmsIcons.Coffee,
            group = "Restaurant",
            order = 130,
            roles = setOf(Role.SUPER_ADMIN, Role.WAITER),
            module = ModuleKey.RESTAURANT
        ),
        NavSpec(
            route = ROUTE_ORDER_HISTORY,
            label = "Order History",
            icon = NbmsIcons.History,
            group = "Restaurant",
            order = 131,
            roles = setOf(Role.SUPER_ADMIN, Role.WAITER, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER),
            module = ModuleKey.RESTAURANT
        ),
        NavSpec(
            route = ROUTE_MENU,
            label = "Menu Management",
            icon = NbmsIcons.Utensils,
            group = "Restaurant",
            order = 132,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = ModuleKey.RESTAURANT
        )
    )

    companion object {
        const val ROUTE_NEW_ORDER = "orders/new"
        const val ROUTE_ORDER_HISTORY = "orders/history"
        const val ROUTE_MENU = "restaurant-menu"
    }
}
