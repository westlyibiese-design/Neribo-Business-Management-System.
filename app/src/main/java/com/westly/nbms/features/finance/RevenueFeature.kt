package com.westly.nbms.features.finance

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingUp
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Revenue (`revenue`) in the Finance group, for the Super Admin, Accountant and Manager. */
@Singleton
class RevenueFeature @Inject constructor() : NbmsFeature {
    override val id: String = "revenue"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_REVENUE) { _, session -> RevenueScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_REVENUE,
            label = "Revenue",
            icon = Icons.Outlined.TrendingUp,
            group = "Finance",
            order = 172,
            roles = setOf(Role.SUPER_ADMIN, Role.ACCOUNTANT, Role.MANAGER),
            module = null
        )
    )

    companion object {
        const val ROUTE_REVENUE = "revenue"
    }
}
