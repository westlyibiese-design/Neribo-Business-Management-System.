package com.westly.nbms.core.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM checks for the rule that decides which values must never be split. */
class AdaptiveRowsTest {

    @Test
    fun moneyIsNeverBroken() {
        assertTrue(isUnbreakableValue("₦90,000"))
        assertTrue(isUnbreakableValue("₦50,000"))
        assertTrue(isUnbreakableValue("+₦1,250,000.50"))
        assertTrue(isUnbreakableValue("-₦500"))
        assertTrue(isUnbreakableValue("$12.00"))
    }

    @Test
    fun shortNumbersAreNeverBroken() {
        assertTrue(isUnbreakableValue("42"))
        assertTrue(isUnbreakableValue("1,250"))
    }

    @Test
    fun datesAndTimesMayWrapOnlyAtSpaces() {
        assertFalse(isUnbreakableValue("12 Oct 2026, 11:00"))
        assertFalse(isUnbreakableValue("Room 101"))
        assertFalse(isUnbreakableValue("Gideon Ibiese"))
    }

    @Test
    fun emptyValueIsNotSpecial() {
        assertFalse(isUnbreakableValue(""))
        assertFalse(isUnbreakableValue("   "))
    }
}
