package com.westly.nbms.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test fun pinRules() {
        assertTrue(Validators.pin("1234"))
        assertTrue(Validators.pin("123456"))
        assertFalse(Validators.pin("123"))
        assertFalse(Validators.pin("1234567"))
        assertFalse(Validators.pin("12a4"))
        assertFalse(Validators.pin(""))
    }

    @Test fun passwordRules() {
        assertNull(Validators.password("abcdefg1"))
        assertNotNull(Validators.password("abc123"))
        assertNotNull(Validators.password("abcdefgh"))
        assertNotNull(Validators.password("12345678"))
        assertEquals(
            "Password must be at least 8 characters with a letter and a number.",
            Validators.password("short")
        )
    }

    @Test fun emailRules() {
        assertTrue(Validators.email("name@example.com"))
        assertTrue(Validators.email(" name.last+tag@sub.example.ng "))
        assertFalse(Validators.email("name@"))
        assertFalse(Validators.email("name@example"))
        assertFalse(Validators.email("not an email"))
    }

    @Test fun phoneRules() {
        assertTrue(Validators.phone("08031234567"))
        assertTrue(Validators.phone("+234 803 123 4567"))
        assertFalse(Validators.phone("12345"))
        assertFalse(Validators.phone("080312345abc"))
        assertFalse(Validators.phone("+1234567890123456"))
    }
}
