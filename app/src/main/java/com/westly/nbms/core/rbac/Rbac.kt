package com.westly.nbms.core.rbac

/** Role permissions and module logic, copied from Westly Hotel (Appendix A.4 / A.4.1). */
object Rbac {

    val pinEligible: Set<Role> = setOf(
        Role.RECEPTIONIST, Role.STAFF, Role.WAITER, Role.HOUSEKEEPING, Role.BAR_ATTENDANT,
        Role.LAUNDRY_VALET, Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER,
        Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF, Role.GYM_STAFF
    )

    val fullAuthOnly: Set<Role> = setOf(
        Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER
    )

    val shiftRoles: Set<Role> = setOf(
        Role.RECEPTIONIST, Role.HOUSEKEEPING, Role.WAITER, Role.BAR_ATTENDANT, Role.LAUNDRY_VALET,
        Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER, Role.RESTAURANT_ATTENDANT,
        Role.KITCHEN_STAFF, Role.GYM_STAFF
    )

    /** All roles except SUPER_ADMIN, in enum order. */
    val assignableRoles: List<Role> = Role.entries.filter { it != Role.SUPER_ADMIN }

    private val permissions: Map<Role, Set<String>> = mapOf(
        Role.SUPER_ADMIN to setOf("*"),
        Role.MANAGER to setOf(
            "view:all", "approve:bookings", "reject:bookings", "view:reports", "view:attendance",
            "view:inventory", "request:delete", "view:staff_performance", "view:financial_summary",
            "view:lost_found", "update:lost_found_status", "view:messages", "reply:messages",
            "view:gym", "view:gym_reports", "manage:gym_content", "manage:gym_staff"
        ),
        Role.RECEPTIONIST to setOf(
            "checkin", "checkout", "register:walkin", "manage:bookings", "create:bookings",
            "view:rooms", "record:payment", "record:attendance", "view:attendance", "print:receipts",
            "view:guests", "create:guests", "view:messages", "reply:messages"
        ),
        Role.STAFF to setOf("record:sales", "view:own_sales"),
        Role.WAITER to setOf("record:orders", "view:own_orders"),
        Role.ACCOUNTANT to setOf(
            "record:expenses", "view:revenue", "generate:reports", "export:reports",
            "view:financial_reports", "view:payments"
        ),
        Role.HOUSEKEEPING to setOf(
            "view:rooms_cleaning", "mark:room_clean", "report:damage", "request:maintenance",
            "create:lost_found", "view:lost_found"
        ),
        Role.BAR_ATTENDANT to setOf(
            "record:bar_sales", "view:own_bar_sales", "view:bar_menu", "view:bar_inventory"
        ),
        Role.LAUNDRY_VALET to setOf(
            "record:laundry_requests", "view:own_laundry_requests", "update:laundry_status"
        ),
        Role.OPERATIONS_MANAGER to setOf(
            "view:rooms", "view:housekeeping", "view:maintenance", "view:restaurant_orders",
            "view:bar_orders", "view:laundry_requests", "view:attendance", "view:operational_reports",
            "view:checkins_checkouts", "create:tasks", "assign:tasks", "reassign:tasks",
            "view:all_tasks", "create:shifts", "manage:shifts", "view:all_shifts", "view:gym",
            "view:gym_reports"
        ),
        Role.GYM_STAFF to setOf(
            "manage:gym_members", "register:gym_membership", "renew:gym_membership", "checkin:gym",
            "checkout:gym", "view:gym_attendance", "view:gym_reports", "view:own_shifts"
        ),
        Role.MAINTENANCE_TECHNICIAN to setOf(
            "view:maintenance", "update:maintenance_status", "view:own_tasks", "view:own_shifts"
        ),
        Role.SECURITY_GUARD to setOf("view:own_tasks", "view:own_shifts"),
        Role.DRIVER to setOf("view:own_tasks", "view:own_shifts"),
        Role.RESTAURANT_ATTENDANT to setOf("view:own_tasks", "view:own_shifts"),
        Role.KITCHEN_STAFF to setOf("view:own_tasks", "view:own_shifts")
    )

    private val moduleOwners: Map<ModuleKey, Set<Role>> = mapOf(
        ModuleKey.RESTAURANT to setOf(Role.WAITER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF),
        ModuleKey.BAR to setOf(Role.BAR_ATTENDANT),
        ModuleKey.LAUNDRY to setOf(Role.LAUNDRY_VALET),
        ModuleKey.GYM to setOf(Role.GYM_STAFF),
        ModuleKey.HOUSEKEEPING to setOf(Role.HOUSEKEEPING),
        ModuleKey.SALES_POS to setOf(Role.STAFF)
    )

    fun permissionsOf(role: Role): Set<String> = permissions[role].orEmpty()

    fun hasPermission(role: Role, permission: String): Boolean {
        if (role == Role.SUPER_ADMIN) return true
        val perms = permissionsOf(role)
        return "*" in perms || permission in perms
    }

    fun canUsePinLogin(role: Role): Boolean = role in pinEligible

    /** A module is on when at least one of its owner roles is enabled. SUPER_ADMIN alone enables nothing. */
    fun enabledModules(enabledRoles: Set<Role>): Set<ModuleKey> =
        moduleOwners.filterValues { owners -> owners.any { it in enabledRoles } }.keys

    fun moduleOf(role: Role): ModuleKey? =
        moduleOwners.entries.firstOrNull { role in it.value }?.key
}
