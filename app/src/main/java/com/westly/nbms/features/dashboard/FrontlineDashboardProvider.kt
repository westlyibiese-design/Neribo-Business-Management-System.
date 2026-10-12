package com.westly.nbms.features.dashboard

import androidx.compose.runtime.Composable
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject

/** The roles this provider owns (Phase 34A and 34B together). */
internal val FRONTLINE_ROLES: Set<Role> = setOf(
    Role.RECEPTIONIST,
    Role.STAFF,
    Role.WAITER,
    Role.HOUSEKEEPING,
    Role.BAR_ATTENDANT,
    Role.LAUNDRY_VALET,
    Role.GYM_STAFF
)

/**
 * The Dashboard of the front-line roles: Receptionist, Staff (sales), Waiter, Housekeeping (Phase 34A) and Bar Attendant,
 * Laundry Valet and Gym Staff (Phase 34B). It has no constructor parameters. Maintenance technician, security guard, driver,
 * restaurant attendant and kitchen staff belong to [MyTasksFallbackProvider], never to this provider; the management roles
 * belong to Phase 33's management provider.
 */
class FrontlineDashboardProvider @Inject constructor() : DashboardProvider {

    override fun supports(role: Role): Boolean = role in FRONTLINE_ROLES

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        when (session.user.role) {
            Role.RECEPTIONIST -> ReceptionistDashboard(session)
            Role.STAFF -> StaffSalesDashboard(session)
            Role.WAITER -> WaiterDashboard(session)
            Role.HOUSEKEEPING -> HousekeepingDashboard(session)
            Role.BAR_ATTENDANT -> BarAttendantDashboard(session)
            Role.LAUNDRY_VALET -> LaundryValetDashboard(session)
            Role.GYM_STAFF -> GymDeskDashboard(session)
            else -> Unit
        }
    }
}
