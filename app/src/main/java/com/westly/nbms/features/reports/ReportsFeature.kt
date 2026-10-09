package com.westly.nbms.features.reports

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Phase 17B: the two report pages, `financial-reports` (Finance group) and `reports` (Annual Reports, top level). */
@Singleton
class ReportsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "reports"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("financial-reports") { _, session -> FinancialReportsScreen(session) },
        ScreenSpec("reports") { _, session -> ReportsScreen(session) }
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
        ),
        NavSpec(
            route = "reports",
            label = "Reports",
            icon = NbmsIcons.FileBarChart,
            group = null,
            order = 200,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT),
            module = null
        )
    )
}
