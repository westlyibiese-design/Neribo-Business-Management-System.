package com.westly.nbms.core.feature

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState

data class ScreenSpec(
    /** Exact Westly path after "/admin/", e.g. "rooms", "gym/members", "sales/new". */
    val route: String,
    val content: @Composable (route: String, session: SessionState.SignedIn) -> Unit
)

data class NavSpec(
    /** Must equal a ScreenSpec route. */
    val route: String,
    /** Exact label from the navigation registry. */
    val label: String,
    val icon: ImageVector,
    /** null = top-level item, otherwise the group label, e.g. "Bookings". */
    val group: String?,
    val order: Int,
    /** null = all signed-in roles. */
    val roles: Set<Role>?,
    /** null = always on. */
    val module: ModuleKey?,
    val badge: (@Composable (SessionState.SignedIn) -> Int?)? = null
)

/** Every feature phase contributes one of these through Hilt multibinding. */
interface NbmsFeature {
    val id: String
    val screens: List<ScreenSpec>
    val nav: List<NavSpec>
}

/** One per role family. The shell picks the first provider that supports the signed-in role. */
interface DashboardProvider {
    fun supports(role: Role): Boolean
    @Composable
    fun Content(session: SessionState.SignedIn)
}
