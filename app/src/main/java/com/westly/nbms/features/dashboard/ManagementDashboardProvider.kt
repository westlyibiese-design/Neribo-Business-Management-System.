package com.westly.nbms.features.dashboard

import androidx.compose.runtime.Composable
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject

/**
 * Phase 33: the Dashboard of the four management roles (Super Admin, Manager, Operations Manager, Accountant).
 * 33A shipped the first two; 33B replaced this file to add the other two. DashboardModule binds it as before.
 */
class ManagementDashboardProvider @Inject constructor() : DashboardProvider {

    override fun supports(role: Role): Boolean =
        role == Role.SUPER_ADMIN || role == Role.MANAGER || role == Role.OPERATIONS_MANAGER || role == Role.ACCOUNTANT

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        when (session.user.role) {
            Role.SUPER_ADMIN -> SuperAdminDashboard(session)
            Role.MANAGER -> ManagerDashboard(session)
            Role.OPERATIONS_MANAGER -> OperationsDashboard(session)
            Role.ACCOUNTANT -> AccountantDashboard(session)
            else -> Unit
        }
    }
}
