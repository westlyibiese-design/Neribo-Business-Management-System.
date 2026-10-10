package com.westly.nbms.features.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricRulesTest {

    @Test fun offeredOnlyWhenEverythingLinesUp() {
        assertTrue(biometricOfferedOnLock(enabled = true, hasPin = true, available = true, pausedByServer = false))
    }

    @Test fun neverOfferedWithoutADevicePin() {
        // Roles and phones with no Device PIN never get biometrics, even if a stale "on" flag is left over.
        assertFalse(biometricOfferedOnLock(enabled = true, hasPin = false, available = true, pausedByServer = false))
    }

    @Test fun notOfferedWhenThePersonDidNotTurnItOn() {
        assertFalse(biometricOfferedOnLock(enabled = false, hasPin = true, available = true, pausedByServer = false))
    }

    @Test fun notOfferedWhenThePhoneHasNoStrongBiometric() {
        assertFalse(biometricOfferedOnLock(enabled = true, hasPin = true, available = false, pausedByServer = false))
    }

    @Test fun notOfferedWhileTooManyWrongPinsHavePausedTheLock() {
        assertFalse(biometricOfferedOnLock(enabled = true, hasPin = true, available = true, pausedByServer = true))
    }
}
