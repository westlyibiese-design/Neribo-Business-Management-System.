package com.westly.nbms.features.gym

import com.westly.nbms.core.util.Format
import kotlinx.datetime.LocalTime

/** The six tabs of the Gym Management page, in display order. The first one (About) is the default. */
enum class GymTab(val label: String) {
    ABOUT("About"),
    EQUIPMENT("Equipment & Services"),
    HOURS("Hours"),
    PACKAGES("Membership Packages"),
    PROGRAMS("Programs"),
    GALLERY("Gallery")
}

/** Pure helpers for the Gym Management forms. Nothing here touches the database or the screen. */
object GymFormRules {

    /** The tabs in display order. */
    val TABS: List<GymTab> = GymTab.entries.toList()

    val DEFAULT_TAB: GymTab = GymTab.ABOUT

    /** The duration label a new package starts with (and falls back to when left blank). */
    const val DEFAULT_DURATION = "Monthly"

    private val PRICE_PATTERN = Regex("^(\\d+\\.?\\d*|\\.\\d+)$")
    private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    // ── price ──

    /**
     * Price text to a number that is 0 or more. Blank means 0. Commas and spaces are ignored ("15,000" is 15000).
     * Returns null for anything that is not a plain non-negative number (negative, letters, "1e3", "NaN"…).
     */
    fun parsePrice(text: String): Double? {
        val cleaned = text.filterNot { it == ',' || it.isWhitespace() }
        if (cleaned.isEmpty()) return 0.0
        if (!PRICE_PATTERN.matches(cleaned)) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
    }

    /** A price as it is shown in the price field: whole amounts without decimals ("15000"), others as they are ("1500.5"). */
    fun priceToText(price: Double): String {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return "0"
        return if (price == Math.floor(price) && price < 1.0e15) price.toLong().toString() else price.toString()
    }

    // ── feature chips ──

    /** Adds [input] (trimmed) to [features]. A blank entry or one already in the list (ignoring upper/lower case) changes nothing. */
    fun addFeature(features: List<String>, input: String): List<String> {
        val clean = input.trim()
        if (clean.isEmpty()) return features
        if (features.any { it.equals(clean, ignoreCase = true) }) return features
        return features + clean
    }

    /** The list without the chip at [index]; a bad index changes nothing. */
    fun removeFeature(features: List<String>, index: Int): List<String> =
        if (index in features.indices) features.filterIndexed { i, _ -> i != index } else features

    // ── changed checks ──

    /** True when the About text differs from the saved one (surrounding spaces do not count). */
    fun aboutChanged(saved: String, edited: String): Boolean = saved.trim() != edited.trim()

    /** True when any day's open time, close time or closed switch differs from the saved hours. */
    fun hoursChanged(saved: List<HoursRow>, edited: List<HoursRow>): Boolean = saved != edited

    // ── hours editing ──

    /** "HH:mm" to a time of day, or null when it is not a valid 24-hour time. */
    fun parseTime(text: String): LocalTime? {
        val match = TIME_PATTERN.matchEntire(text.trim()) ?: return null
        return LocalTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /** A time of day as "HH:mm". */
    fun formatTime(hour: Int, minute: Int): String = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

    fun withClosed(rows: List<HoursRow>, index: Int, closed: Boolean): List<HoursRow> =
        rows.mapIndexed { i, row -> if (i == index) row.copy(closed = closed) else row }

    fun withOpenTime(rows: List<HoursRow>, index: Int, hour: Int, minute: Int): List<HoursRow> =
        rows.mapIndexed { i, row -> if (i == index) row.copy(open = formatTime(hour, minute)) else row }

    fun withCloseTime(rows: List<HoursRow>, index: Int, hour: Int, minute: Int): List<HoursRow> =
        rows.mapIndexed { i, row -> if (i == index) row.copy(close = formatTime(hour, minute)) else row }

    // ── texts ──

    /** The line under a package name: "{duration} · {₦price}", for example "Monthly · ₦15,000". */
    fun packageSubtitle(duration: String, price: Double, symbol: String = "₦"): String =
        "${duration.trim().ifEmpty { DEFAULT_DURATION }} · ${Format.currency(price, symbol)}"

    /** The heading of a list: "{title} ({count}/{limit})". */
    fun countHeading(title: String, count: Int, limit: Int): String = "$title ($count/$limit)"

    /** The title of the delete confirmation: Delete "{name}"? */
    fun deleteTitle(name: String): String = "Delete \"$name\"?"
}
