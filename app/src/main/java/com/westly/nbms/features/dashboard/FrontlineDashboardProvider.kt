package com.westly.nbms.features.dashboard

import androidx.compose.runtime.Composable
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject

/** The roles this provider owns in Phase 34A2. Phase 34B adds Bar Attendant, Laundry Valet and Gym Staff. */
internal val FRONTLINE_ROLES_34A2: Set<Role> = setOf(Role.RECEPTIONIST, Role.STAFF, Role.WAITER, Role.HOUSEKEEPING)

/**
 * Phase 34A2: the Dashboard of the Receptionist, Staff (sales), Waiter and Housekeeping roles. It has no constructor
 * parameters so Phase 34B can replace this file with a longer `when`. Maintenance technician, security guard, driver,
 * restaurant attendant and kitchen staff belong to [MyTasksFallbackProvider], never to this provider.
 */
class FrontlineDashboardProvider @Inject constructor() : DashboardProvider {

    override fun supports(role: Role): Boolean = role in FRONTLINE_ROLES_34A2

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        when (session.user.role) {
            Role.RECEPTIONIST -> ReceptionistDashboard(session)
            Role.STAFF -> StaffSalesDashboard(session)
            Role.WAITER -> WaiterDashboard(session)
            Role.HOUSEKEEPING -> HousekeepingDashboard(session)
            else -> Unit
        }
    }
}
