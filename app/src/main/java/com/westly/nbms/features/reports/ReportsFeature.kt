package com.westly.nbms.features.reports

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Phase 17B-1: ONE route, `financial-reports`, in the Finance group. Phase 17B-2 replaces this file to add `reports`. */
@Singleton
class ReportsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "reports"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("financial-reports") { _, session -> FinancialReportsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "financial-reports",
            label = "Financial Reports",
            icon = NbmsIcons.FileBarChart,
            group = "Finance",
            order = 174,
            roles = setOf(Role.SUPER_ADMIN, Role.ACCOUNTANT, Role.MANAGER),
            module = null
        )
    )
}
