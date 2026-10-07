package com.westly.nbms.features.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPadStateTest {

    private fun typed(digits: String): PinPadState =
        digits.fold(PinPadState()) { s, d -> s.press(d) }

    @Test fun startsEmptyAndNotSubmittable() {
        val s = PinPadState()
        assertEquals("", s.pin)
        assertFalse(s.canSubmit)
        assertFalse(s.shouldAutoSubmit)
    }

    @Test fun pressAddsDigitsInOrder() {
        assertEquals("1203", typed("1203").pin)
    }

    @Test fun okNeedsAtLeastFourDigits() {
        assertFalse(typed("123").canSubmit)
        assertTrue(typed("1234").canSubmit)
        assertTrue(typed("12345").canSubmit)
        assertTrue(typed("123456").canSubmit)
    }

    @Test fun neverMoreThanSixDigits() {
        val s = typed("12345678")
        assertEquals("123456", s.pin)
    }

    @Test fun autoSubmitOnlyAtSixDigits() {
        assertFalse(typed("1234").shouldAutoSubmit)
        assertFalse(typed("12345").shouldAutoSubmit)
        assertTrue(typed("123456").shouldAutoSubmit)
    }

    @Test fun deleteRemovesLastDigitAndIgnoresEmpty() {
        assertEquals("12", typed("123").delete().pin)
        assertEquals("", PinPadState().delete().pin)
    }

    @Test fun nonDigitsAreIgnored() {
        val s = typed("12")
        assertSame(s, s.press('a'))
        assertSame(s, s.press(' '))
    }

    @Test fun nothingChangesWhileSubmitting() {
        val busy = typed("1234").withSubmitting(true)
        assertSame(busy, busy.press('5'))
        assertSame(busy, busy.delete())
        assertFalse(busy.canSubmit)
        assertFalse(typed("123456").withSubmitting(true).shouldAutoSubmit)
    }

    @Test fun clearedKeepsSubmittingFlagButEmptiesPin() {
        val s = typed("1234").cleared()
        assertEquals("", s.pin)
        assertFalse(s.canSubmit)
    }

    @Test fun businessCodeIsUppercasedAndFiltered() {
        assertEquals("ABC123", normalizeBusinessCode("abc-123 "))
        assertEquals("ABCDEFGH", normalizeBusinessCode("abcdefghijk"))
        assertEquals("", normalizeBusinessCode("--- "))
    }

    @Test fun businessCodeNeedsSixToEightCharacters() {
        assertFalse(isValidBusinessCode(""))
        assertFalse(isValidBusinessCode("ABC12"))
        assertTrue(isValidBusinessCode("ABC123"))
        assertTrue(isValidBusinessCode("ABCD1234"))
        assertFalse(isValidBusinessCode("ABCD12345"))
        assertFalse(isValidBusinessCode("abc123"))
        assertFalse(isValidBusinessCode("ABC 12"))
    }
}
