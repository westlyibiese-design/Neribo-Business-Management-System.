package com.westly.nbms.features.gym

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 30: the Gym Reports page (`gym/reports`, drawer group Gym, order 163, built in 30A) and the Gym Management page
 * (`gym-cms`, "Membership Packages", order 164, Super Admin and Manager only, built in 30B).
 */
@Singleton
class GymContentFeature @Inject constructor() : NbmsFeature {
    override val id: String = "gymcontent"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_GYM_REPORTS) { _, session -> GymReportsScreen(session) },
        ScreenSpec(ROUTE_GYM_CMS) { _, session -> GymManagementScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(ROUTE_GYM_REPORTS, "Reports", NbmsIcons.BarChart, "Gym", 163, GymFeature.GYM_ROLES, ModuleKey.GYM),
        NavSpec(ROUTE_GYM_CMS, "Membership Packages", NbmsIcons.Tag, "Gym", 164, MANAGEMENT_ROLES, ModuleKey.GYM)
    )

    companion object {
        const val ROUTE_GYM_REPORTS = "gym/reports"
        const val ROUTE_GYM_CMS = "gym-cms"
        val MANAGEMENT_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.MANAGER)
    }
}
