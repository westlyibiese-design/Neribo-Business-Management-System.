package com.westly.nbms.features.dashboard

import androidx.compose.runtime.Composable
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject

/** Phase 33A2: the Dashboard of the Super Admin and the Manager. Phase 33B replaces this file to add the other two roles. */
class ManagementDashboardProvider @Inject constructor() : DashboardProvider {

    override fun supports(role: Role): Boolean = role == Role.SUPER_ADMIN || role == Role.MANAGER

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        when (session.user.role) {
            Role.SUPER_ADMIN -> SuperAdminDashboard(session)
            Role.MANAGER -> ManagerDashboard(session)
            else -> Unit
        }
    }
}
