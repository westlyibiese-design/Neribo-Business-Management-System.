package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 34B: the FULL provider-support matrix. Across Phases 33 and 34 every one of the 16 roles has exactly one dashboard
 * provider: management (Phase 33), front-line (Phase 34A + 34B) or My Tasks (Phase 34A).
 */
class FrontlineDashboardProviderTest {

    private val provider = FrontlineDashboardProvider()
    private val fallback = MyTasksFallbackProvider()
    private val management = ManagementDashboardProvider()

    private val managementRoles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.ACCOUNTANT)
    private val frontlineRoles = setOf(
        Role.RECEPTIONIST, Role.STAFF, Role.WAITER, Role.HOUSEKEEPING, Role.BAR_ATTENDANT, Role.LAUNDRY_VALET, Role.GYM_STAFF
    )
    private val fallbackRoles = setOf(
        Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF
    )

    @Test fun thereAreSixteenRolesAndTheThreeGroupsCoverThemAll() {
        assertEquals(16, Role.entries.size)
        assertEquals(Role.entries.toSet(), managementRoles + frontlineRoles + fallbackRoles)
        assertEquals(4 + 7 + 5, managementRoles.size + frontlineRoles.size + fallbackRoles.size)
    }

    @Test fun supportsTheSevenFrontlineRoles() {
        frontlineRoles.forEach { assertTrue("${it.key} must be a front-line role", provider.supports(it)) }
    }

    @Test fun supportsExactlyThoseSevenRoles() {
        assertEquals(frontlineRoles, Role.entries.filter { provider.supports(it) }.toSet())
    }

    @Test fun supportsTheThreePhase34BRoles() {
        assertTrue(provider.supports(Role.BAR_ATTENDANT))
        assertTrue(provider.supports(Role.LAUNDRY_VALET))
        assertTrue(provider.supports(Role.GYM_STAFF))
    }

    @Test fun stillSupportsTheFourPhase34ARoles() {
        assertTrue(provider.supports(Role.RECEPTIONIST))
        assertTrue(provider.supports(Role.STAFF))
        assertTrue(provider.supports(Role.WAITER))
        assertTrue(provider.supports(Role.HOUSEKEEPING))
    }

    @Test fun doesNotClaimAnyFallbackRole() {
        fallbackRoles.forEach { assertFalse("${it.key} belongs to My Tasks", provider.supports(it)) }
    }

    @Test fun doesNotClaimAnyManagementRole() {
        managementRoles.forEach { assertFalse("${it.key} belongs to the management dashboards", provider.supports(it)) }
    }

    @Test fun managementProviderOwnsExactlyTheFourManagementRoles() {
        assertEquals(managementRoles, Role.entries.filter { management.supports(it) }.toSet())
    }

    @Test fun myTasksProviderOwnsExactlyTheFiveFallbackRoles() {
        assertEquals(fallbackRoles, Role.entries.filter { fallback.supports(it) }.toSet())
    }

    @Test fun everyRoleHasExactlyOneProvider() {
        for (role in Role.entries) {
            val owners = listOf(management.supports(role), provider.supports(role), fallback.supports(role)).count { it }
            assertEquals("${role.key} must have exactly one dashboard provider but has $owners", 1, owners)
        }
    }

    @Test fun eachRoleIsOwnedByTheExpectedProvider() {
        for (role in Role.entries) {
            val expected = when (role) {
                in managementRoles -> "management"
                in frontlineRoles -> "frontline"
                else -> "my-tasks"
            }
            val actual = when {
                management.supports(role) -> "management"
                provider.supports(role) -> "frontline"
                fallback.supports(role) -> "my-tasks"
                else -> "none"
            }
            assertEquals("owner of ${role.key}", expected, actual)
        }
    }

    @Test fun frontlineAndFallbackNeverOverlap() {
        Role.entries.forEach { assertFalse(provider.supports(it) && fallback.supports(it)) }
    }
}
