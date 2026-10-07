package com.westly.nbms.core.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.math.abs

/** Port of the Westly utils.ts formatting helpers. */
object Format {

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val EMPTY = "—"

    /** `1234.5` -> "₦1,234.50", `1500.0` -> "₦1,500". Whole amounts have no decimals. */
    fun currency(amount: Double, symbol: String = "₦"): String {
        val totalCents = Math.round(abs(amount) * 100.0)
        val whole = totalCents / 100
        val cents = (totalCents % 100).toInt()
        val wholeText = String.format(Locale.US, "%,d", whole)
        val fraction = if (cents != 0) String.format(Locale.US, ".%02d", cents) else ""
        val sign = if (amount < 0 && totalCents != 0L) "-" else ""
        return "$sign$symbol$wholeText$fraction"
    }

    /** "12 Mar 2025" in the device time zone unless [zone] is given. */
    fun date(d: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (d == null) return EMPTY
        return localDate(d.toLocalDateTime(zone).date)
    }

    /** "12 Mar 2025, 14:30" in the device time zone unless [zone] is given. */
    fun dateTime(d: Instant?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (d == null) return EMPTY
        val local = d.toLocalDateTime(zone)
        val time = String.format(Locale.US, "%02d:%02d", local.hour, local.minute)
        return "${localDate(local.date)}, $time"
    }

    /** "12 Mar 2025" for a plain calendar date (no time zone involved). */
    fun localDate(d: LocalDate?): String {
        if (d == null) return EMPTY
        return "${d.dayOfMonth} ${MONTHS[d.monthNumber - 1]} ${d.year}"
    }

    /** "just now", "5m ago", "2h ago", "3d ago". Older than 30 days (or in the future) shows the date. */
    fun relative(
        d: Instant?,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        if (d == null) return EMPTY
        val seconds = now.epochSeconds - d.epochSeconds
        return when {
            seconds < 0 -> date(d, zone)
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            seconds < 30 * 86_400L -> "${seconds / 86_400}d ago"
            else -> date(d, zone)
        }
    }

    /**
     * Nigerian-friendly phone display. "08031234567" -> "0803 123 4567",
     * "+2348031234567" -> "+234 803 123 4567". Anything else is returned trimmed.
     */
    fun phone(p: String?): String {
        val clean = p?.trim().orEmpty()
        if (clean.isEmpty()) return EMPTY
        val hasPlus = clean.startsWith("+")
        val digits = clean.filter { it in '0'..'9' }
        return when {
            !hasPlus && digits.length == 11 && digits.startsWith("0") ->
                "${digits.substring(0, 4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
            hasPlus && digits.length == 13 && digits.startsWith("234") ->
                "+234 ${digits.substring(3, 6)} ${digits.substring(6, 9)} ${digits.substring(9)}"
            else -> clean
        }
    }

    /** "Westly Ibiese" -> "WI", "Westly" -> "W", blank -> "?". */
    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        val first = parts.first().first().uppercaseChar()
        if (parts.size == 1) return first.toString()
        val last = parts.last().first().uppercaseChar()
        return "$first$last"
    }

    /** Number of nights between check-in and check-out (never negative). */
    fun nights(checkIn: LocalDate, checkOut: LocalDate): Int =
        checkIn.daysUntil(checkOut).coerceAtLeast(0)
}
