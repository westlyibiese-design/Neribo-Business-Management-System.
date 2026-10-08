package com.westly.nbms.core.notify

import com.westly.nbms.core.rbac.Role

/** The recipient groups used by the Notifier (Westly's role groups). */
object RoleGroups {
    /** super_admin, manager */
    val MGMT: List<Role> = listOf(Role.SUPER_ADMIN, Role.MANAGER)

    /** MGMT + receptionist */
    val FRONT_DESK: List<Role> = withRoles(MGMT, Role.RECEPTIONIST)

    /** MGMT + accountant */
    val FINANCE: List<Role> = withRoles(MGMT, Role.ACCOUNTANT)

    /** MGMT + operations_manager */
    val OPS: List<Role> = withRoles(MGMT, Role.OPERATIONS_MANAGER)

    /** MGMT + operations_manager */
    val GYM_OVERSIGHT: List<Role> = withRoles(MGMT, Role.OPERATIONS_MANAGER)

    /** "group + extra roles" without duplicates, keeping the group's order first. */
    fun withRoles(group: List<Role>, vararg extra: Role): List<Role> = (group + extra).distinct()
}

/** Top-level alias so wrappers read like the spec: `withRoles(FRONT_DESK, Role.OPERATIONS_MANAGER)`. */
internal fun withRoles(group: List<Role>, vararg extra: Role): List<Role> = (group + extra).distinct()
