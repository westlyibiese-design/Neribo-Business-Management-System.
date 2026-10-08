package com.westly.nbms.features.settings

import com.westly.nbms.features.settings.models.HotelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun cleaningQueueTimeSubtractsTheLeadTime() {
        assertEquals("10:00", cleaningQueueTime("11:00", 60))
        assertEquals("10:15", cleaningQueueTime("11:00", 45))
        assertEquals("11:00", cleaningQueueTime("11:00", 0))
    }

    @Test
    fun cleaningQueueTimeWrapsPastMidnight() {
        assertEquals("23:30", cleaningQueueTime("00:30", 60))
        assertEquals("21:00", cleaningQueueTime("01:00", 240))
    }

    @Test
    fun cleaningQueueTimeIsZeroPadded() {
        assertEquals("08:05", cleaningQueueTime("09:00", 55))
    }

    @Test
    fun badTimeTextGivesNull() {
        assertNull(cleaningQueueTime("25:00", 10))
        assertNull(cleaningQueueTime("abc", 10))
        assertNull(parseHm("9"))
        assertNull(parseHm("12:60"))
    }

    @Test
    fun helperLineReadsLikeTheSpec() {
        assertEquals(
            "E.g. with Check-Out Time 11:00 and 60 minutes, tasks queue at 10:00.",
            leadTimeHelper("11:00", 60)
        )
    }

    @Test
    fun timezoneMustBeARealIanaId() {
        assertTrue(isValidTimezone("Africa/Lagos"))
        assertTrue(isValidTimezone("UTC"))
        assertFalse(isValidTimezone("Mars/Olympus"))
        assertFalse(isValidTimezone(""))
    }

    @Test
    fun currencyInputIsThreeCapitalLetters() {
        assertEquals("NGN", cleanCurrencyInput("ngn"))
        assertEquals("USD", cleanCurrencyInput("us1dollars"))
        assertEquals("", cleanCurrencyInput("123"))
    }

    @Test
    fun validFormHasNoErrors() {
        assertFalse(validateSettingsForm(SettingsForm(hotelName = "Westly Hotel", email = "a@b.com")).any)
        assertFalse(validateSettingsForm(SettingsForm(hotelName = "Westly Hotel", email = "")).any)
    }

    @Test
    fun invalidFieldsAreReportedOneByOne() {
        val e = validateSettingsForm(
            SettingsForm(hotelName = "W", email = "nope", currency = "NG", leadMinutes = "481", timezone = "Mars/Olympus")
        )
        assertEquals(MSG_NAME_LENGTH, e.hotelName)
        assertEquals(MSG_EMAIL, e.email)
        assertEquals(MSG_CURRENCY, e.currency)
        assertEquals(MSG_LEAD, e.leadMinutes)
        assertEquals(MSG_TIMEZONE, e.timezone)
    }

    @Test
    fun hotelNameLongerThan120IsRejected() {
        assertEquals(MSG_NAME_LENGTH, validateSettingsForm(SettingsForm(hotelName = "x".repeat(121))).hotelName)
        assertNull(validateSettingsForm(SettingsForm(hotelName = "x".repeat(120))).hotelName)
    }

    @Test
    fun emptyLeadTimeIsRejected() {
        assertEquals(MSG_LEAD, validateSettingsForm(SettingsForm(hotelName = "Hotel", leadMinutes = "")).leadMinutes)
    }

    @Test
    fun savedMapHasTheAgreedFields() {
        val m = hotelSettingsMap(
            SettingsForm(hotelName = " Westly ", currency = "ngn", leadMinutes = "45", serviceEnabled = false, instagram = " https://i "),
            "SERVER_TIME", "u1"
        )
        assertEquals("Westly", m["hotelName"])
        assertEquals("NGN", m["currency"])
        assertEquals(45, m["housekeepingLeadTimeMinutes"])
        assertEquals(false, m["occupiedStayServiceEnabled"])
        assertEquals("SERVER_TIME", m["updatedAt"])
        assertEquals("u1", m["updatedBy"])
        @Suppress("UNCHECKED_CAST")
        val social = m["socialLinks"] as Map<String, String>
        assertEquals(setOf("instagram", "facebook", "twitter"), social.keys)
        assertEquals("https://i", social["instagram"])
        assertEquals(
            setOf(
                "hotelName", "tagline", "phone", "email", "address", "currency", "checkInTime", "checkOutTime",
                "timezone", "housekeepingLeadTimeMinutes", "occupiedStayServiceTime", "occupiedStayServiceEnabled",
                "socialLinks", "updatedAt", "updatedBy"
            ),
            m.keys
        )
    }

    @Test
    fun missingDocumentUsesDefaultsAndTheBusinessName() {
        val f = formFrom(HotelSettings(), "My Hotel")
        assertEquals("My Hotel", f.hotelName)
        assertEquals("14:00", f.checkInTime)
        assertEquals("11:00", f.checkOutTime)
        assertEquals("NGN", f.currency)
        assertEquals("60", f.leadMinutes)
        assertEquals("Africa/Lagos", f.timezone)
        assertEquals("10:00", f.serviceTime)
        assertTrue(f.serviceEnabled)
    }
}
