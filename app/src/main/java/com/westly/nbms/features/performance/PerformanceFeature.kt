package com.westly.nbms.features.performance

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/** Staff Performance (Phase 34B): one route, `staff-performance`, no drawer group, no module, Super Admin and Manager only. */
class PerformanceFeature @Inject constructor() : NbmsFeature {
    override val id: String = "performance"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_STAFF_PERFORMANCE) { _, session -> PerformanceScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_STAFF_PERFORMANCE,
            label = "Staff Performance",
            icon = NbmsIcons.BarChart,
            group = null,
            order = 210,
            roles = PERFORMANCE_ROLES,
            module = null
        )
    )

    companion object {
        const val ROUTE_STAFF_PERFORMANCE = "staff-performance"
        val PERFORMANCE_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.MANAGER)
    }
}
