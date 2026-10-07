package com.westly.nbms.core.feature

import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role

/** Pure route-access rules from the navigation registry (Appendix A.9). Phase 7 uses these. */
object NavRules {

    private val SA = Role.SUPER_ADMIN
    private val MGR = Role.MANAGER
    private val OPS = Role.OPERATIONS_MANAGER
    private val ACC = Role.ACCOUNTANT
    private val REC = Role.RECEPTIONIST

    private class Row(val route: String, val roles: Set<Role>?, val module: ModuleKey? = null)

    private fun row(route: String, vararg roles: Role, module: ModuleKey? = null) =
        Row(route, roles.toSet(), module)

    private fun rowAll(route: String) = Row(route, null, null)

    private val rows: List<Row> = listOf(
        rowAll("dashboard"),
        rowAll("my-tasks"),
        row("messages", SA, MGR, REC),
        row("rooms", SA, MGR, OPS, REC),
        row("venues", SA),
        row("tasks", SA, MGR, OPS),
        row("shifts", SA, MGR, OPS),
        row("bookings", SA, MGR, OPS, REC),
        row("room-reservations", SA, REC),
        row("checkin", SA, REC),
        row("checkout", SA, REC),
        row("guests", SA, MGR, REC),
        row("housekeeping", SA, MGR, Role.HOUSEKEEPING, OPS, module = ModuleKey.HOUSEKEEPING),
        row("housekeeping/assignments", SA, MGR, OPS, module = ModuleKey.HOUSEKEEPING),
        row("lost-found", SA, MGR, Role.HOUSEKEEPING),
        row("maintenance", SA, MGR, Role.HOUSEKEEPING, OPS),
        row("sales/new", SA, Role.STAFF, module = ModuleKey.SALES_POS),
        row("sales/history", SA, Role.STAFF, MGR, ACC, module = ModuleKey.SALES_POS),
        row("orders/new", SA, Role.WAITER, module = ModuleKey.RESTAURANT),
        row("orders/history", SA, Role.WAITER, MGR, ACC, OPS, module = ModuleKey.RESTAURANT),
        row("restaurant-menu", SA, MGR, module = ModuleKey.RESTAURANT),
        row("bar/new-sale", SA, Role.BAR_ATTENDANT, module = ModuleKey.BAR),
        row("bar/sales-history", SA, Role.BAR_ATTENDANT, MGR, ACC, OPS, module = ModuleKey.BAR),
        row("bar-menu", SA, MGR, module = ModuleKey.BAR),
        row("bar-inventory", SA, MGR, ACC, Role.BAR_ATTENDANT, module = ModuleKey.BAR),
        row("laundry", SA, MGR, Role.LAUNDRY_VALET, module = ModuleKey.LAUNDRY),
        row("laundry/history", SA, MGR, Role.LAUNDRY_VALET, ACC, OPS, module = ModuleKey.LAUNDRY),
        row("gym/checkin", SA, MGR, OPS, Role.GYM_STAFF, module = ModuleKey.GYM),
        row("gym/members", SA, MGR, OPS, Role.GYM_STAFF, module = ModuleKey.GYM),
        row("gym/attendance", SA, MGR, OPS, Role.GYM_STAFF, module = ModuleKey.GYM),
        row("gym/reports", SA, MGR, OPS, Role.GYM_STAFF, module = ModuleKey.GYM),
        row("gym-cms", SA, MGR, module = ModuleKey.GYM),
        row("approvals", SA, ACC, MGR),
        row("expenses", SA, ACC, MGR),
        row("revenue", SA, ACC, MGR),
        row("payments", SA, REC, ACC, MGR),
        row("financial-reports", SA, ACC, MGR),
        row("inventory", SA, MGR, ACC),
        row("attendance", SA, MGR, REC, OPS),
        row("attendance/record", SA, REC),
        row("reports", SA, MGR, ACC),
        row("staff-performance", SA, MGR),
        row("users", SA),
        row("roles", SA),
        row("audit-log", SA),
        row("deleted-records", SA),
        row("cms", SA),
        row("facilities", SA, MGR),
        row("gallery", SA, MGR),
        row("reviews", SA, MGR),
        rowAll("device-settings"),
        rowAll("notifications"),
        row("settings", SA),
        row("system-maintenance", SA)
    )

    /** Route allowlist: role keys per route. null = any signed-in role. */
    val routeRoles: Map<String, Set<Role>?> = rows.associate { it.route to it.roles }

    /** Optional module a route belongs to. null = always on. */
    val routeModule: Map<String, ModuleKey?> = rows.associate { it.route to it.module }

    /** Role allowed AND (module == null || module enabled). Unknown routes are never allowed. */
    fun canOpen(route: String, role: Role, modules: Set<ModuleKey>): Boolean {
        if (!routeRoles.containsKey(route)) return false
        val allowed = routeRoles[route]
        if (allowed != null && role !in allowed) return false
        val module = routeModule[route]
        return module == null || module in modules
    }

    /** Navigation entries the person may see: filtered by role + module, sorted by order. */
    fun visibleNav(features: Set<NbmsFeature>, role: Role, modules: Set<ModuleKey>): List<NavSpec> =
        features
            .flatMap { it.nav }
            .filter { spec ->
                (spec.roles == null || role in spec.roles) &&
                    (spec.module == null || spec.module in modules)
            }
            .sortedBy { it.order }
}
