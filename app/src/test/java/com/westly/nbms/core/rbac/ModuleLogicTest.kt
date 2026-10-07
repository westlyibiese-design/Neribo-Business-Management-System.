package com.westly.nbms.core.rbac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleLogicTest {

    @Test fun noRolesMeansNoModules() {
        assertEquals(emptySet<ModuleKey>(), Rbac.enabledModules(emptySet()))
    }

    @Test fun superAdminAloneEnablesNothing() {
        assertEquals(emptySet<ModuleKey>(), Rbac.enabledModules(setOf(Role.SUPER_ADMIN)))
    }

    @Test fun restaurantNeedsAnyOfItsThreeRoles() {
        listOf(Role.WAITER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF).forEach {
            assertEquals(setOf(ModuleKey.RESTAURANT), Rbac.enabledModules(setOf(it)))
        }
    }

    @Test fun singleRoleModules() {
        assertEquals(setOf(ModuleKey.BAR), Rbac.enabledModules(setOf(Role.BAR_ATTENDANT)))
        assertEquals(setOf(ModuleKey.LAUNDRY), Rbac.enabledModules(setOf(Role.LAUNDRY_VALET)))
        assertEquals(setOf(ModuleKey.GYM), Rbac.enabledModules(setOf(Role.GYM_STAFF)))
        assertEquals(setOf(ModuleKey.HOUSEKEEPING), Rbac.enabledModules(setOf(Role.HOUSEKEEPING)))
        assertEquals(setOf(ModuleKey.SALES_POS), Rbac.enabledModules(setOf(Role.STAFF)))
    }

    @Test fun alwaysOnRolesEnableNoModule() {
        val roles = setOf(
            Role.MANAGER, Role.RECEPTIONIST, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER,
            Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER
        )
        assertEquals(emptySet<ModuleKey>(), Rbac.enabledModules(roles))
    }

    @Test fun allRolesEnableAllModules() {
        assertEquals(ModuleKey.entries.toSet(), Rbac.enabledModules(Role.entries.toSet()))
    }

    @Test fun owningModuleExtension() {
        assertEquals(ModuleKey.RESTAURANT, Role.KITCHEN_STAFF.owningModule)
        assertEquals(ModuleKey.SALES_POS, Role.STAFF.owningModule)
        assertNull(Role.MANAGER.owningModule)
        assertNull(Role.SUPER_ADMIN.owningModule)
        assertEquals(ModuleKey.GYM, Rbac.moduleOf(Role.GYM_STAFF))
    }
}
