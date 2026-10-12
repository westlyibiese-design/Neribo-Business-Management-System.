package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementDashboardProviderTest {

    private val provider = ManagementDashboardProvider()

    private val supported = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.ACCOUNTANT)

    @Test fun supportsTheFourManagementRoles() {
        assertTrue(provider.supports(Role.SUPER_ADMIN))
        assertTrue(provider.supports(Role.MANAGER))
        assertTrue(provider.supports(Role.OPERATIONS_MANAGER))
        assertTrue(provider.supports(Role.ACCOUNTANT))
    }

    @Test fun supportsNoOtherRole() {
        val others = Role.entries.filter { it !in supported }
        assertEquals(Role.entries.size - 4, others.size)
        for (role in others) assertFalse("$role must not be supported by the management provider", provider.supports(role))
    }
}
