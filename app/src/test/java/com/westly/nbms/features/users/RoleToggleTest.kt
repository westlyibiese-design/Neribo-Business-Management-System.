package com.westly.nbms.features.users

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.users.models.StaffUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleToggleTest {

    private fun user(id: String, role: Role, status: String = "active") =
        StaffUser(id = id, uid = id, name = id, role = role.key, status = status)

    @Test
    fun onlyActiveAccountsAreCounted() {
        val counts = activeCountsByRole(
            listOf(
                user("1", Role.RECEPTIONIST),
                user("2", Role.RECEPTIONIST),
                user("3", Role.RECEPTIONIST, status = "suspended"),
                user("4", Role.WAITER, status = "suspended")
            )
        )
        assertEquals(2, counts[Role.RECEPTIONIST] ?: 0)
        assertNull(counts[Role.WAITER])
    }

    @Test
    fun unknownRoleKeysAreIgnored() {
        val counts = activeCountsByRole(listOf(StaffUser(id = "x", role = "developer")))
        assertTrue(counts.isEmpty())
    }

    @Test
    fun aRoleWithActiveStaffCannotBeTurnedOff() {
        assertFalse(canToggleRole(isEnabled = true, activeCount = 1))
        assertFalse(canToggleRole(isEnabled = true, activeCount = 5))
    }

    @Test
    fun aRoleWithNoActiveStaffCanBeTurnedOff() {
        assertTrue(canToggleRole(isEnabled = true, activeCount = 0))
    }

    @Test
    fun aRoleThatIsOffCanAlwaysBeTurnedOn() {
        assertTrue(canToggleRole(isEnabled = false, activeCount = 0))
        assertTrue(canToggleRole(isEnabled = false, activeCount = 3))
    }

    @Test
    fun blockedCaptionMatchesTheSpec() {
        assertEquals("2 active user(s) — suspend or move them first", roleBlockedCaption(2))
    }

    @Test
    fun turningARoleOnAddsIt() {
        val next = toggledRoles(setOf(Role.MANAGER), Role.BAR_ATTENDANT, enable = true)
        assertEquals(setOf(Role.MANAGER, Role.BAR_ATTENDANT), next)
    }

    @Test
    fun turningARoleOffRemovesIt() {
        val next = toggledRoles(setOf(Role.MANAGER, Role.BAR_ATTENDANT), Role.BAR_ATTENDANT, enable = false)
        assertEquals(setOf(Role.MANAGER), next)
    }

    @Test
    fun superAdminNeverEntersTheSet() {
        val next = toggledRoles(setOf(Role.SUPER_ADMIN, Role.MANAGER), Role.SUPER_ADMIN, enable = true)
        assertEquals(setOf(Role.MANAGER), next)
        assertFalse(Role.SUPER_ADMIN in toggledRoles(setOf(Role.SUPER_ADMIN), Role.DRIVER, enable = true))
    }

    @Test
    fun everyRoleHasADescription() {
        Role.entries.forEach { assertTrue(roleDescription(it).isNotBlank()) }
    }

    @Test
    fun requestBodyListsAssignableRolesInEnumOrder() {
        val body = rolesRequestBody(setOf(Role.RECEPTIONIST, Role.SUPER_ADMIN, Role.MANAGER))
        assertEquals("{\"enabledRoles\":[\"manager\",\"receptionist\"]}", body.toString())
    }

    @Test
    fun requestBodyCanBeEmpty() {
        assertEquals("{\"enabledRoles\":[]}", rolesRequestBody(emptySet()).toString())
    }

    @Test
    fun okReplyIsASuccess() {
        assertTrue(parseRolesReply("{\"ok\":true,\"enabledRoles\":[\"manager\"]}").isSuccess)
    }

    @Test
    fun errorReplyKeepsTheServerMessage() {
        val result = parseRolesReply("{\"ok\":false,\"error\":\"Can't turn off Waiter: 2 active user(s) still have this role.\"}")
        assertEquals("Can't turn off Waiter: 2 active user(s) still have this role.", result.exceptionOrNull()?.message)
    }

    @Test
    fun unreadableReplyGivesAGenericMessage() {
        val result = parseRolesReply("not json")
        assertEquals("Something went wrong. Please try again.", result.exceptionOrNull()?.message)
    }

    @Test
    fun serverErrorIsPulledOutOfRawText() {
        assertEquals("Not allowed", rolesServerError(null, "", "{\"ok\":false,\"error\":\"Not allowed\"}"))
        assertNull(rolesServerError("plain text"))
    }
}
