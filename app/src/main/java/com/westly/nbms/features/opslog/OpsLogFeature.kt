package com.westly.nbms.features.opslog

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * The Operations Log feature. In Phase 25A it registers ONLY the Lost & Found route:
 * `lost-found` "Lost & Found" (order 100, no group): Super Admin, Manager, Housekeeping.
 * Phase 25B replaces this file to add the `maintenance` route.
 */
class OpsLogFeature @Inject constructor() : NbmsFeature {
    override val id = "opslog"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("lost-found") { _, session -> LostFoundScreen(session) }
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
        )
    )
}
