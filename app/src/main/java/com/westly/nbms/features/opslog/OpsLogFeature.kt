package com.westly.nbms.features.opslog

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * The Operations Log feature. It registers two routes, both without a drawer group:
 * - `lost-found` "Lost & Found" (order 100): Super Admin, Manager, Housekeeping (Phase 25A).
 * - `maintenance` "Maintenance" (order 110): Super Admin, Manager, Housekeeping, Operations Manager (Phase 25B).
 */
class OpsLogFeature @Inject constructor() : NbmsFeature {
    override val id = "opslog"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("lost-found") { _, session -> LostFoundScreen(session) },
        ScreenSpec("maintenance") { _, session -> MaintenanceScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "lost-found",
            label = "Lost & Found",
            icon = NbmsIcons.PackageSearch,
            group = null,
            order = 100,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING),
            module = null
        ),
        NavSpec(
            route = "maintenance",
            label = "Maintenance",
            icon = NbmsIcons.Wrench,
            group = null,
            order = 110,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER),
            module = null
        )
    )
}
