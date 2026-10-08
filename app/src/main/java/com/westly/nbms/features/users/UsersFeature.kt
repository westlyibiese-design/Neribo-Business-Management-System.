package com.westly.nbms.features.users

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Users & Roles (`users`), Roles & Permissions (`roles`) and Audit Log (`audit-log`). Super Admin only.
 * `roles` has no menu entry: it is opened from the Users page.
 */
@Singleton
class UsersFeature @Inject constructor() : NbmsFeature {
    override val id: String = "users"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_USERS) { _, session -> UsersScreen(session) },
        ScreenSpec(route = ROUTE_ROLES) { _, session -> RolesScreen(session) },
        ScreenSpec(route = ROUTE_AUDIT_LOG) { _, session -> AuditLogScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_USERS,
            label = "Users & Roles",
            icon = NbmsIcons.Users,
            group = null,
            order = 220,
            roles = setOf(Role.SUPER_ADMIN),
            module = null
        ),
        NavSpec(
            route = ROUTE_AUDIT_LOG,
            label = "Audit Log",
            icon = NbmsIcons.History,
            group = null,
            order = 230,
            roles = setOf(Role.SUPER_ADMIN),
            module = null
        )
    )

    companion object {
        const val ROUTE_USERS = "users"
        const val ROUTE_ROLES = "roles"
        const val ROUTE_AUDIT_LOG = "audit-log"
    }
}
