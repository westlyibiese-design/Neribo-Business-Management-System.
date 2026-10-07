package com.westly.nbms.core.rbac

/** Every staff role in NBMS. There is NO Developer role. */
enum class Role(val key: String, val label: String) {
    SUPER_ADMIN("super_admin", "Super Admin"),
    MANAGER("manager", "Manager"),
    RECEPTIONIST("receptionist", "Receptionist"),
    ACCOUNTANT("accountant", "Accountant"),
    STAFF("staff", "Staff"),
    WAITER("waiter", "Waiter"),
    HOUSEKEEPING("housekeeping", "Housekeeping"),
    BAR_ATTENDANT("bar_attendant", "Bar Attendant"),
    LAUNDRY_VALET("laundry_valet", "Laundry Valet"),
    OPERATIONS_MANAGER("operations_manager", "Operations Manager"),
    MAINTENANCE_TECHNICIAN("maintenance_technician", "Maintenance Technician"),
    SECURITY_GUARD("security_guard", "Security Guard"),
    DRIVER("driver", "Driver"),
    RESTAURANT_ATTENDANT("restaurant_attendant", "Restaurant Attendant"),
    KITCHEN_STAFF("kitchen_staff", "Kitchen Staff"),
    GYM_STAFF("gym_staff", "Gym Staff");

    companion object {
        /** Returns the role for a stored key, or null for unknown keys (including "developer"). */
        fun fromKey(key: String?): Role? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Optional modules. A module is enabled for a business when at least one of its owner roles
 * is in the business's enabled roles. Everything else in the app is always on.
 */
enum class ModuleKey { RESTAURANT, BAR, LAUNDRY, GYM, HOUSEKEEPING, SALES_POS }

/** True for the 12 roles that may sign in with a shared-device PIN. */
fun Role.isPinEligible(): Boolean = Rbac.canUsePinLogin(this)

/** The optional module this role owns, or null if the role belongs to no optional module. */
val Role.owningModule: ModuleKey? get() = Rbac.moduleOf(this)
