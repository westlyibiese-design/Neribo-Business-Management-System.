package com.westly.nbms.features.inventory

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** The Inventory page of Phase 18: ONE route, `inventory`, a top-level drawer item (order 180) for SA, MGR and ACC. */
@Singleton
class InventoryFeature @Inject constructor() : NbmsFeature {
    override val id: String = "inventory"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("inventory") { _, session -> InventoryScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "inventory",
            label = "Inventory",
            icon = NbmsIcons.Package,
            group = null,
            order = 180,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT),
            module = null
        )
    )
}
