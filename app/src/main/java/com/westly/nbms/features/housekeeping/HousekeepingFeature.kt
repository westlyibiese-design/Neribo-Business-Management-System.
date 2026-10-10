package com.westly.nbms.features.housekeeping

import androidx.compose.runtime.Composable
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject
import javax.inject.Singleton

/** Which of the two screens the `housekeeping` route shows. */
internal enum class HousekeepingRouteView { MY_HOUSEKEEPING, OVERVIEW }

/** The Housekeeping role gets My Housekeeping; Super Admin, Manager and Operations Manager get the Overview. */
internal fun housekeepingRouteView(role: Role): HousekeepingRouteView =
    if (role == Role.HOUSEKEEPING) HousekeepingRouteView.MY_HOUSEKEEPING else HousekeepingRouteView.OVERVIEW

@Composable
internal fun HousekeepingRouteContent(session: SessionState.SignedIn) {
    when (housekeepingRouteView(session.user.role)) {
        HousekeepingRouteView.MY_HOUSEKEEPING -> MyHousekeepingScreen(session)
        HousekeepingRouteView.OVERVIEW -> HousekeepingOverviewScreen(session)
    }
}

/**
 * The Housekeeping feature of Part 23B: ONE route in the Housekeeping drawer group, module HOUSEKEEPING.
 *
 * - `housekeeping` "Overview" (order 90): Super Admin, Manager, Housekeeping, Operations Manager
 *
 * Room Assignments (`housekeeping/assignments`) belongs to Part 23A's own feature.
 */
@Singleton
class HousekeepingFeature @Inject constructor() : NbmsFeature {
    override val id: String = "housekeeping"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_HOUSEKEEPING) { _, session -> HousekeepingRouteContent(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_HOUSEKEEPING,
            label = "Overview",
            icon = NbmsIcons.Sparkles,
            group = "Housekeeping",
            order = 90,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER),
            module = ModuleKey.HOUSEKEEPING
        )
    )

    companion object {
        const val ROUTE_HOUSEKEEPING = "housekeeping"
    }
}
