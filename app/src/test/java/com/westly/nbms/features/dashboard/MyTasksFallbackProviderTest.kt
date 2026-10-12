package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyTasksFallbackProviderTest {

    private val provider = MyTasksFallbackProvider()

    @Test fun supportsExactlyTheFiveRolesWithoutADashboard() {
        val expected = setOf(
            Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF
        )
        assertEquals(expected, Role.entries.filter { provider.supports(it) }.toSet())
    }

    @Test fun doesNotSupportManagementOrFrontlineDashboardRoles() {
        listOf(
            Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.ACCOUNTANT, Role.RECEPTIONIST, Role.STAFF,
            Role.WAITER, Role.HOUSEKEEPING, Role.BAR_ATTENDANT, Role.LAUNDRY_VALET, Role.GYM_STAFF
        ).forEach { assertFalse("${it.key} must not be supported", provider.supports(it)) }
    }

    @Test fun eachSupportedRoleIsSupported() {
        assertTrue(provider.supports(Role.DRIVER))
        assertTrue(provider.supports(Role.SECURITY_GUARD))
        assertTrue(provider.supports(Role.MAINTENANCE_TECHNICIAN))
        assertTrue(provider.supports(Role.RESTAURANT_ATTENDANT))
        assertTrue(provider.supports(Role.KITCHEN_STAFF))
    }

    @Test fun opensTheMyTasksRoute() {
        assertEquals("my-tasks", MY_TASKS_ROUTE)
    }
}
