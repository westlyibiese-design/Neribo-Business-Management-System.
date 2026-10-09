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
 * The Restaurant feature, part 1: ONE route, `restaurant-menu` (Menu Management), in the Restaurant drawer group (order 132)
 * for Super Admin and Manager. Phase 20b replaces this whole file with the three-route version.
 */
@Singleton
class RestaurantFeature @Inject constructor() : NbmsFeature {
    override val id: String = "restaurant"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("restaurant-menu") { _, session -> MenuManagementScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "restaurant-menu",
            label = "Menu Management",
            icon = NbmsIcons.Utensils,
            group = "Restaurant",
            order = 132,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = ModuleKey.RESTAURANT
        )
    )
}
