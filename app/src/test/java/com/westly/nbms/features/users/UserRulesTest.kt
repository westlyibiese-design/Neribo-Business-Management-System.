package com.westly.nbms.features.users

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.users.models.StaffUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserRulesTest {

    @Test
    fun missingNameEmailOrPasswordGivesTheRequiredMessage() {
        assertEquals("Name, email and password are required.", validateNewUser("", "a@b.com", "password1", Role.MANAGER, ""))
        assertEquals("Name, email and password are required.", validateNewUser("Ada", " ", "password1", Role.MANAGER, ""))
        assertEquals("Name, email and password are required.", validateNewUser("Ada", "a@b.com", "", Role.MANAGER, ""))
    }

    @Test
    fun shortPasswordIsRejected() {
        assertEquals("Password must be at least 8 characters.", validateNewUser("Ada", "a@b.com", "1234567", Role.MANAGER, ""))
    }

    @Test
    fun eightCharacterPasswordIsAccepted() {
        assertNull(validateNewUser("Ada", "a@b.com", "12345678", Role.MANAGER, ""))
    }

    @Test
    fun noRoleToChooseGivesAHelpfulMessage() {
        assertEquals(MSG_NO_ROLE, validateNewUser("Ada", "a@b.com", "12345678", null, ""))
    }

    @Test
    fun pinIsOptionalForPinRoles() {
        assertNull(validateNewUser("Ada", "a@b.com", "12345678", Role.RECEPTIONIST, ""))
    }

    @Test
    fun shortPinIsRejectedForPinRoles() {
        assertEquals("PIN must be at least 4 digits.", validateNewUser("Ada", "a@b.com", "12345678", Role.RECEPTIONIST, "123"))
    }

    @Test
    fun fourToSixDigitPinsAreAccepted() {
        assertNull(validateNewUser("Ada", "a@b.com", "12345678", Role.RECEPTIONIST, "1234"))
        assertNull(validateNewUser("Ada", "a@b.com", "12345678", Role.RECEPTIONIST, "123456"))
    }

    @Test
    fun pinIsIgnoredForEmailOnlyRoles() {
        assertNull(validateNewUser("Ada", "a@b.com", "12345678", Role.MANAGER, "12"))
    }

    @Test
    fun resetValidation() {
        assertEquals("Password must be at least 8 characters.", validateNewPassword("short"))
        assertNull(validateNewPassword("longenough"))
        assertEquals("PIN must be at least 4 digits.", validateNewPin("12"))
        assertNull(validateNewPin("1234"))
    }

    @Test
    fun pinInputKeepsOnlySixDigits() {
        assertEquals("123456", cleanPinInput("12a3-45 67890"))
        assertEquals("", cleanPinInput("abc"))
    }

    @Test
    fun usersAreSortedByNameIgnoringCase() {
        val sorted = sortUsers(
            listOf(StaffUser(id = "1", name = "bola"), StaffUser(id = "2", name = "Ada"), StaffUser(id = "3", name = "Chidi"))
        )
        assertEquals(listOf("Ada", "bola", "Chidi"), sorted.map { it.name })
    }
}
