package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontlineDashboardProviderTest {

    private val provider = FrontlineDashboardProvider()
    private val fallback = MyTasksFallbackProvider()
    private val management = ManagementDashboardProvider()

    @Test fun supportsTheFourPhase34A2Roles() {
        assertTrue(provider.supports(Role.RECEPTIONIST))
        assertTrue(provider.supports(Role.STAFF))
        assertTrue(provider.supports(Role.WAITER))
        assertTrue(provider.supports(Role.HOUSEKEEPING))
    }

    @Test fun supportsExactlyThoseFourRoles() {
        val expected = setOf(Role.RECEPTIONIST, Role.STAFF, Role.WAITER, Role.HOUSEKEEPING)
        assertEquals(expected, Role.entries.filter { provider.supports(it) }.toSet())
    }

    @Test fun doesNotYetSupportThePhase34BRoles() {
        assertFalse(provider.supports(Role.BAR_ATTENDANT))
        assertFalse(provider.supports(Role.LAUNDRY_VALET))
        assertFalse(provider.supports(Role.GYM_STAFF))
    }

    @Test fun doesNotClaimAnyFallbackRole() {
        listOf(
            Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF
        ).forEach { assertFalse("${it.key} belongs to My Tasks", provider.supports(it)) }
    }

    @Test fun doesNotClaimAnyManagementRole() {
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.ACCOUNTANT)
            .forEach { assertFalse("${it.key} belongs to the management dashboards", provider.supports(it)) }
    }

    @Test fun noRoleIsSupportedByMoreThanOneDashboardProvider() {
        for (role in Role.entries) {
            val owners = listOf(provider.supports(role), fallback.supports(role), management.supports(role)).count { it }
            assertTrue("${role.key} is supported by $owners providers", owners <= 1)
        }
    }

    @Test fun frontlineAndFallbackNeverOverlap() {
        Role.entries.forEach { assertFalse(provider.supports(it) && fallback.supports(it)) }
    }
}
