package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementDashboardProviderTest {

    private val provider = ManagementDashboardProvider()

    @Test fun supportsSuperAdminAndManager() {
        assertTrue(provider.supports(Role.SUPER_ADMIN))
        assertTrue(provider.supports(Role.MANAGER))
    }

    @Test fun supportsNoOtherRole() {
        val others = Role.entries.filter { it != Role.SUPER_ADMIN && it != Role.MANAGER }
        assertEquals(Role.entries.size - 2, others.size)
        for (role in others) assertFalse("$role must not be supported in 33A2", provider.supports(role))
    }

    @Test fun operationsManagerAndAccountantStillFallBackUntil33B() {
        assertFalse(provider.supports(Role.OPERATIONS_MANAGER))
        assertFalse(provider.supports(Role.ACCOUNTANT))
    }
}
