package com.westly.nbms.core.rbac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RbacTest {

    @Test fun developerRoleDoesNotExist() {
        assertNull(Role.fromKey("developer"))
        assertNull(Role.fromKey(null))
        assertNull(Role.fromKey(""))
        assertEquals(16, Role.entries.size)
    }

    @Test fun fromKeyFindsEveryRole() {
        Role.entries.forEach { assertEquals(it, Role.fromKey(it.key)) }
    }

    @Test fun pinAndFullAuthSetsAreComplete() {
        assertEquals(12, Rbac.pinEligible.size)
        assertEquals(4, Rbac.fullAuthOnly.size)
        assertEquals(Role.entries.toSet(), Rbac.pinEligible + Rbac.fullAuthOnly)
        assertTrue(Rbac.pinEligible.intersect(Rbac.fullAuthOnly).isEmpty())
        assertTrue(Rbac.canUsePinLogin(Role.RECEPTIONIST))
        assertFalse(Rbac.canUsePinLogin(Role.MANAGER))
        assertTrue(Role.WAITER.isPinEligible())
        assertFalse(Role.SUPER_ADMIN.isPinEligible())
    }

    @Test fun shiftRolesExcludeOfficeRoles() {
        assertEquals(11, Rbac.shiftRoles.size)
        assertFalse(Role.MANAGER in Rbac.shiftRoles)
        assertFalse(Role.STAFF in Rbac.shiftRoles)
        assertTrue(Role.GYM_STAFF in Rbac.shiftRoles)
    }

    @Test fun assignableRolesAreAllButSuperAdminInEnumOrder() {
        assertEquals(Role.entries.filter { it != Role.SUPER_ADMIN }, Rbac.assignableRoles)
        assertFalse(Role.SUPER_ADMIN in Rbac.assignableRoles)
    }

    @Test fun superAdminHasEverything() {
        assertEquals(setOf("*"), Rbac.permissionsOf(Role.SUPER_ADMIN))
        assertTrue(Rbac.hasPermission(Role.SUPER_ADMIN, "anything:at_all"))
    }

    @Test fun managerViewAllDoesNotGrantViewRooms() {
        assertTrue(Rbac.hasPermission(Role.MANAGER, "view:all"))
        assertFalse(Rbac.hasPermission(Role.MANAGER, "view:rooms"))
    }

    @Test fun permissionSpotChecks() {
        assertTrue(Rbac.hasPermission(Role.RECEPTIONIST, "checkin"))
        assertTrue(Rbac.hasPermission(Role.ACCOUNTANT, "view:payments"))
        assertFalse(Rbac.hasPermission(Role.ACCOUNTANT, "checkin"))
        assertEquals(setOf("record:sales", "view:own_sales"), Rbac.permissionsOf(Role.STAFF))
        assertEquals(setOf("view:own_tasks", "view:own_shifts"), Rbac.permissionsOf(Role.DRIVER))
        assertEquals(Rbac.permissionsOf(Role.DRIVER), Rbac.permissionsOf(Role.KITCHEN_STAFF))
        assertEquals(18, Rbac.permissionsOf(Role.OPERATIONS_MANAGER).size)
    }

    @Test fun everyRoleHasPermissions() {
        Role.entries.forEach { assertTrue("${it.key} has permissions", Rbac.permissionsOf(it).isNotEmpty()) }
    }
}
