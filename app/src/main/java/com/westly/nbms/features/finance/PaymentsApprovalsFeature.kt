package com.westly.nbms.features.finance

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** The Finance pages of Phase 16B. In this part it registers one route, `payments`; the Approvals route is added by the next part. */
@Singleton
class PaymentsApprovalsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "payments-approvals"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("payments") { _, session -> PaymentsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "payments",
            label = "Payments",
            icon = NbmsIcons.Banknote,
            group = "Finance",
            order = 173,
            roles = setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST, Role.ACCOUNTANT, Role.MANAGER),
            module = null
        )
    )
}
