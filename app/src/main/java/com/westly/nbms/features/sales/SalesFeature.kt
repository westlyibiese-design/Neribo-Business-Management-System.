package com.westly.nbms.features.sales

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Sales & POS: `sales/new` (order 120) and `sales/history` (order 121), module SALES_POS. */
@Singleton
class SalesFeature @Inject constructor() : NbmsFeature {
    override val id: String = "sales"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_NEW) { _, session -> NewSaleScreen(session) },
        ScreenSpec(ROUTE_HISTORY) { _, session -> SalesHistoryScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_NEW, label = "New Sale", icon = NbmsIcons.ShoppingCart, group = "Sales & POS", order = 120,
            roles = setOf(Role.SUPER_ADMIN, Role.STAFF), module = ModuleKey.SALES_POS
        ),
        NavSpec(
            route = ROUTE_HISTORY, label = "Sales History", icon = NbmsIcons.History, group = "Sales & POS", order = 121,
            roles = setOf(Role.SUPER_ADMIN, Role.STAFF, Role.MANAGER, Role.ACCOUNTANT), module = ModuleKey.SALES_POS
        )
    )

    companion object {
        const val ROUTE_NEW = "sales/new"
        const val ROUTE_HISTORY = "sales/history"
    }
}
