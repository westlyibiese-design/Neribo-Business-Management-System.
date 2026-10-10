package com.westly.nbms.features.shifts

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * The shifts feature: `shifts` "Shift Scheduling" (group Operations, order 61) for Super Admin, Manager and
 * Operations Manager (Phase 27B).
 */
class ShiftsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "shifts"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("shifts") { _, session -> ShiftSchedulingScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "shifts",
            label = "Shift Scheduling",
            icon = NbmsIcons.CalendarClock,
            group = "Operations",
            order = 61,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER),
            module = null
        )
    )
}
