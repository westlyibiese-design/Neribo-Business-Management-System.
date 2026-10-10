package com.westly.nbms.features.gym

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 30A: the Gym Reports page (`gym/reports`, drawer group Gym, order 163). The Gym Management route (`gym-cms`) is
 * added by Phase 30B, which replaces this whole file.
 */
@Singleton
class GymContentFeature @Inject constructor() : NbmsFeature {
    override val id: String = "gymcontent"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_GYM_REPORTS) { _, session -> GymReportsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(ROUTE_GYM_REPORTS, "Reports", NbmsIcons.BarChart, "Gym", 163, GymFeature.GYM_ROLES, ModuleKey.GYM)
    )

    companion object {
        const val ROUTE_GYM_REPORTS = "gym/reports"
    }
}
