package com.westly.nbms.features.auth

import com.westly.nbms.core.rbac.Rbac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {

    @Test fun fullNameNeedsTwoToOneTwentyCharacters() {
        assertNotNull(RegisterValidation.fullName(""))
        assertNotNull(RegisterValidation.fullName(" a "))
        assertNull(RegisterValidation.fullName("Jo"))
        assertNull(RegisterValidation.fullName("a".repeat(120)))
        assertNotNull(RegisterValidation.fullName("a".repeat(121)))
    }

    @Test fun emailMustBeValid() {
        assertNotNull(RegisterValidation.email(""))
        assertNotNull(RegisterValidation.email("not-an-email"))
        assertNull(RegisterValidation.email("owner@hotel.com"))
    }

    @Test fun phoneIsOptionalButMustBeValidWhenGiven() {
        assertNull(RegisterValidation.phone(""))
        assertNull(RegisterValidation.phone("+234 801 234 5678"))
        assertNotNull(RegisterValidation.phone("12"))
        assertNotNull(RegisterValidation.phone("abc"))
    }

    @Test fun passwordNeedsEightCharactersLetterAndNumber() {
        assertNotNull(RegisterValidation.password("short1"))
        assertNotNull(RegisterValidation.password("onlyletters"))
        assertNotNull(RegisterValidation.password("12345678"))
        assertNull(RegisterValidation.password("goodpass1"))
    }

    @Test fun confirmPasswordMustMatch() {
        assertNotNull(RegisterValidation.confirmPassword("goodpass1", ""))
        assertNotNull(RegisterValidation.confirmPassword("goodpass1", "goodpass2"))
        assertNull(RegisterValidation.confirmPassword("goodpass1", "goodpass1"))
    }

    @Test fun businessNameNeedsTwoToOneTwentyCharacters() {
        assertNotNull(RegisterValidation.businessName("x"))
        assertNull(RegisterValidation.businessName("Grand Palace Hotel"))
        assertNotNull(RegisterValidation.businessName("b".repeat(121)))
    }

    @Test fun everyAssignableRoleIsOnTheRolesStepExactlyOnce() {
        val shown = RegisterRoles.groups.flatMap { g -> g.options.map { it.role } }
        assertEquals(15, shown.size)
        assertEquals(Rbac.assignableRoles.toSet(), shown.toSet())
    }

    @Test fun defaultRolesAreManagerReceptionistAccountant() {
        assertEquals(
            setOf("manager", "receptionist", "accountant"),
            RegisterRoles.defaultSelection.map { it.key }.toSet()
        )
    }

    @Test fun loginFailureTextMatchesTheSpec() {
        assertEquals("Invalid email or password. Please try again.", loginFailureMessage("Invalid email or password."))
        assertEquals("Invalid email or password. Please try again.", loginFailureMessage(null))
        assertEquals(
            "Can't reach the server. Check your connection.",
            loginFailureMessage("Can't reach the server. Check your connection.")
        )
    }

    @Test fun serverErrorIsPulledOutOfJson() {
        assertEquals(
            "An account with this email already exists.",
            extractServerError("""{"ok":false,"error":"An account with this email already exists."}""")
        )
        assertNull(extractServerError("something else", null))
        assertTrue(extractServerError(null, """{"error":"Nope"}""") == "Nope")
        assertFalse(extractServerError("") != null)
    }
}
