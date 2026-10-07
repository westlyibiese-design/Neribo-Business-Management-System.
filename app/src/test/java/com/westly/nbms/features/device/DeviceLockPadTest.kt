package com.westly.nbms.features.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockPadTest {

    @Test fun submitNeedsSixDigits() {
        var pad = LockPad()
        "12345".forEach { pad = pad.add(it) }
        assertFalse(pad.canSubmit)
        pad = pad.add('6')
        assertTrue(pad.canSubmit)
    }

    @Test fun stopsAtTenDigits() {
        var pad = LockPad()
        "12345678901234".forEach { pad = pad.add(it) }
        assertEquals("1234567890", pad.pin)
        assertTrue(pad.isFull)
    }

    @Test fun ignoresNonDigits() {
        assertEquals("", LockPad().add('a').pin)
    }

    @Test fun deleteRemovesTheLastDigit() {
        assertEquals("12", LockPad("123").delete().pin)
        assertEquals("", LockPad().delete().pin)
    }

    @Test fun newPinMustBeSixToTenDigits() {
        assertEquals("PIN must be 6–10 digits.", validateNewPin("12345"))
        assertEquals("PIN must be 6–10 digits.", validateNewPin("12345678901"))
        assertEquals("PIN must be 6–10 digits.", validateNewPin("12345a"))
        assertNull(validateNewPin("123456"))
        assertNull(validateNewPin("1234567890"))
    }

    @Test fun confirmMustMatch() {
        assertEquals("PINs don't match.", validateConfirm("123456", "123457"))
        assertNull(validateConfirm("123456", "123456"))
    }
}
