package com.westly.nbms.features.reports

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** The Expenses page of Phase 17A-2: ONE route, `expenses`, in the Finance group. */
@Singleton
class ExpensesFeature @Inject constructor() : NbmsFeature {
    override val id: String = "expenses"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("expenses") { _, session -> ExpensesScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "expenses",
            label = "Expenses",
            icon = NbmsIcons.Receipt,
            group = "Finance",
            order = 171,
            roles = setOf(Role.SUPER_ADMIN, Role.ACCOUNTANT, Role.MANAGER),
            module = null
        )
    )
}
