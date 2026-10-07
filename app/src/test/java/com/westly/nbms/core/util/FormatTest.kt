package com.westly.nbms.core.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test fun currencyWholeHasNoDecimals() {
        assertEquals("₦1,500", Format.currency(1500.0))
        assertEquals("₦20,735,000", Format.currency(20_735_000.0))
        assertEquals("₦0", Format.currency(0.0))
    }

    @Test fun currencyFractionHasTwoDecimals() {
        assertEquals("₦1,234.50", Format.currency(1234.5))
        assertEquals("₦45,000.50", Format.currency(45_000.5))
        assertEquals("₦0.05", Format.currency(0.05))
    }

    @Test fun currencyCustomSymbolAndNegative() {
        assertEquals("$10", Format.currency(10.0, "$"))
        assertEquals("-₦250", Format.currency(-250.0))
    }

    @Test fun dateAndDateTimeUseGivenZone() {
        val instant = Instant.parse("2025-03-12T14:30:00Z")
        assertEquals("12 Mar 2025", Format.date(instant, TimeZone.UTC))
        assertEquals("12 Mar 2025, 14:30", Format.dateTime(instant, TimeZone.UTC))
        assertEquals("12 Mar 2025, 15:30", Format.dateTime(instant, TimeZone.of("Africa/Lagos")))
    }

    @Test fun nullDatesShowDash() {
        assertEquals("—", Format.date(null))
        assertEquals("—", Format.dateTime(null))
        assertEquals("—", Format.relative(null))
    }

    @Test fun relativeBuckets() {
        val now = Instant.parse("2025-03-12T12:00:00Z")
        assertEquals("just now", Format.relative(Instant.parse("2025-03-12T11:59:40Z"), now))
        assertEquals("5m ago", Format.relative(Instant.parse("2025-03-12T11:55:00Z"), now))
        assertEquals("2h ago", Format.relative(Instant.parse("2025-03-12T10:00:00Z"), now))
        assertEquals("3d ago", Format.relative(Instant.parse("2025-03-09T12:00:00Z"), now))
        assertEquals("1 Jan 2025", Format.relative(Instant.parse("2025-01-01T12:00:00Z"), now, TimeZone.UTC))
    }

    @Test fun phoneFormatting() {
        assertEquals("0803 123 4567", Format.phone("08031234567"))
        assertEquals("+234 803 123 4567", Format.phone("+2348031234567"))
        assertEquals("12345", Format.phone(" 12345 "))
        assertEquals("—", Format.phone(null))
    }

    @Test fun initialsFromNames() {
        assertEquals("WI", Format.initials("Westly Ibiese"))
        assertEquals("W", Format.initials("westly"))
        assertEquals("AC", Format.initials("Ada Nneka Chukwu"))
        assertEquals("?", Format.initials("   "))
    }

    @Test fun nightsBetweenDates() {
        assertEquals(3, Format.nights(LocalDate(2025, 3, 10), LocalDate(2025, 3, 13)))
        assertEquals(0, Format.nights(LocalDate(2025, 3, 13), LocalDate(2025, 3, 10)))
    }
}
