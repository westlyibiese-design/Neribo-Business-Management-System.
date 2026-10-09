package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.util.Format
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure rules for Part 22B (Laundry History): filters, figures, order, row texts and the CSV export.
// Nothing here touches Android or the database, so the unit tests run it directly.

/** Filter key for "All Status". */
internal const val LAUNDRY_HISTORY_STATUS_ALL = ""

internal const val MSG_LAUNDRY_HISTORY_LOAD_FAILED = "We couldn't load laundry history."
internal const val MSG_LAUNDRY_HISTORY_SHARE_FAILED = "The file could not be shared."

// ── who sees what ──

/** The valet id to query for. The laundry valet sees only their own requests; every other role sees all of them. */
internal fun laundryHistoryScopeFor(role: Role?, uid: String): String? = if (role == Role.LAUNDRY_VALET) uid else null

// ── months ──

internal fun laundryHistoryZone(timezone: String?): ZoneId = try {
    ZoneId.of(timezone ?: "Africa/Lagos")
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun laundryHistoryCurrentMonth(zone: ZoneId, now: Instant = Instant.now()): String =
    YearMonth.from(now.atZone(zone)).toString()

/** "October 2026"; text that is not a month is returned unchanged. */
internal fun laundryHistoryMonthLabel(month: String): String = try {
    val ym = YearMonth.parse(month)
    "${ym.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)} ${ym.year}"
} catch (e: Exception) {
    month
}

/** Blank ("All months") followed by the current month and the 23 before it. */
internal fun laundryHistoryMonthOptions(zone: ZoneId, now: Instant = Instant.now(), count: Int = 24): List<String> {
    val current = YearMonth.from(now.atZone(zone))
    return listOf("") + (0 until count).map { current.minusMonths(it.toLong()).toString() }
}

// ── filters ──

/** The status dropdown: (filter key, label). "All Status", the six workflow labels, then "Cancelled". */
internal fun laundryHistoryStatusOptions(): List<Pair<String, String>> =
    listOf(LAUNDRY_HISTORY_STATUS_ALL to "All Status") + LaundryStatus.entries.map { it.key to it.label }

/** What the three controls above the list hold. [month] is "yyyy-MM" (blank = every month); [status] is a status key (blank = all). */
internal data class LaundryHistoryFilters(
    val month: String,
    val search: String = "",
    val status: String = LAUNDRY_HISTORY_STATUS_ALL
)

/**
 * The rows Laundry History shows: deleted requests removed, then month (judged in [zone]), search over guest name, room number
 * and valet name (trimmed, ignoring case) and status; newest first, requests without a date last.
 */
internal fun filterLaundryHistory(
    requests: List<LaundryRequest>,
    filters: LaundryHistoryFilters,
    zone: ZoneId
): List<LaundryRequest> {
    val ym = filters.month.trim().takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val needle = filters.search.trim()
    val status = filters.status.trim()
    return requests
        .filter { !it.isDeleted }
        .filter { r -> ym == null || r.createdAt?.let { YearMonth.from(it.atZone(zone)) == ym } == true }
        .filter { r ->
            needle.isEmpty() ||
                (r.guestName?.contains(needle, ignoreCase = true) == true) ||
                (r.roomNumber?.contains(needle, ignoreCase = true) == true) ||
                r.laundryValetName.contains(needle, ignoreCase = true)
        }
        .filter { r -> status.isEmpty() || r.status.key == status }
        .sortedWith(
            compareByDescending<LaundryRequest> { it.createdAt?.toEpochMilli() ?: Long.MIN_VALUE }.thenBy { it.id }
        )
}

// ── figures ──

/** Rounds to 2 decimals (kobo) so a total never carries floating-point noise. */
internal fun laundryHistoryMoney(value: Double): Double = Math.round(value * 100.0) / 100.0

/** How many requests count: cancelled requests are left out. */
internal fun laundryHistoryCount(rows: List<LaundryRequest>): Int = rows.count { it.status != LaundryStatus.CANCELLED }

/** What the counted requests add up to: the sum of `charge`, cancelled requests left out. */
internal fun laundryHistoryTotal(rows: List<LaundryRequest>): Double =
    laundryHistoryMoney(rows.filter { it.status != LaundryStatus.CANCELLED }.sumOf { it.charge })

/** "12 requests · ₦45,000" (cancelled requests are not counted, and their charge is not added). */
internal fun laundryHistorySubtitle(rows: List<LaundryRequest>, symbol: String): String =
    "${laundryHistoryCount(rows)} requests · ${Format.currency(laundryHistoryTotal(rows), symbol)}"

// ── row texts ──

/** "12 Mar 2025, 14:30" in the business time zone; "—" when the request has no date yet. (Format works with kotlinx Instants.) */
internal fun laundryHistoryDateTime(at: Instant?, tz: kotlinx.datetime.TimeZone): String =
    Format.dateTime(at?.let { kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong()) }, tz)

/** The items text with the count in brackets: "2 shirts, 1 suit (3)"; "— (3)" when no description was typed. */
internal fun laundryHistoryItemsText(r: LaundryRequest): String = "${r.itemsDescription ?: "—"} (${r.itemCount})"

/** The line under the guest name: "Room 201". Only when BOTH a guest name and a room exist (otherwise the first line already says it). */
internal fun laundryHistoryRoomLine(r: LaundryRequest): String? {
    val room = r.roomNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (r.guestName?.isNotBlank() == true) "Room $room" else null
}

// ── CSV export ──

/** The columns of the export, in order. */
internal val LAUNDRY_HISTORY_CSV_COLUMNS: List<String> = listOf(
    "Date", "Guest", "Room", "Items", "Item Count", "Status", "Charge", "Payment Status", "Logged By", "Delivered At"
)

/** Wraps a value in double quotes and doubles every quote inside it, so commas, quotes and line breaks stay in one cell. */
internal fun laundryHistoryCsvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** The first line of every export. Every cell, the header too, is double-quoted. */
internal fun laundryHistoryCsvHeader(): String = LAUNDRY_HISTORY_CSV_COLUMNS.joinToString(",") { laundryHistoryCsvCell(it) }

/** "25000" for 25000.0 and "2500.5" for 2500.5: plain digits, no symbol, no thousands separators, so a spreadsheet can add them up. */
internal fun laundryHistoryCsvAmount(amount: Double): String =
    if (amount.isNaN() || amount.isInfinite()) "0" else BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

private val LAUNDRY_HISTORY_CSV_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

/** "2026-10-09 14:30" in the business time zone, or an empty cell when there is no time. */
internal fun laundryHistoryCsvDateTime(at: Instant?, zone: ZoneId): String = at?.atZone(zone)?.format(LAUNDRY_HISTORY_CSV_TIME).orEmpty()

/** One line, in the order of [LAUNDRY_HISTORY_CSV_COLUMNS]. Status and payment status are their labels; missing text is an empty cell. */
internal fun laundryHistoryCsvRow(r: LaundryRequest, zone: ZoneId): String =
    listOf(
        laundryHistoryCsvDateTime(r.createdAt, zone),
        r.guestName.orEmpty(),
        r.roomNumber.orEmpty(),
        r.itemsDescription.orEmpty(),
        r.itemCount.toString(),
        r.status.label,
        laundryHistoryCsvAmount(r.charge),
        r.paymentStatus.label,
        r.laundryValetName,
        laundryHistoryCsvDateTime(r.deliveredAt, zone)
    ).joinToString(",") { laundryHistoryCsvCell(it) }

/** The whole file: the header, then one line per row. Callers pass the FILTERED list. */
internal fun buildLaundryHistoryCsv(rows: List<LaundryRequest>, zone: ZoneId): String =
    (listOf(laundryHistoryCsvHeader()) + rows.map { laundryHistoryCsvRow(it, zone) }).joinToString("\n")

/** "laundry-history-2026-10.csv" for a month written as yyyy-MM, or "laundry-history-all.csv" when no month is chosen. */
internal fun laundryHistoryCsvFileName(month: String): String {
    val clean = month.trim()
    return if (clean.isEmpty()) "laundry-history-all.csv" else "laundry-history-$clean.csv"
}

/** A file ready to share: its name and its text. */
internal data class LaundryHistoryExport(val fileName: String, val content: String)

internal fun laundryHistoryExportOf(rows: List<LaundryRequest>, filters: LaundryHistoryFilters, zone: ZoneId): LaundryHistoryExport =
    LaundryHistoryExport(laundryHistoryCsvFileName(filters.month), buildLaundryHistoryCsv(rows, zone))

// ── what the page is showing ──

internal sealed interface LaundryHistoryView {
    data object Loading : LaundryHistoryView
    data class Error(val message: String) : LaundryHistoryView
    data class Ready(val rows: List<LaundryRequest>) : LaundryHistoryView
}

internal fun laundryHistoryViewOf(
    resource: Resource<List<LaundryRequest>>,
    filters: LaundryHistoryFilters,
    zone: ZoneId
): LaundryHistoryView = when (resource) {
    is Resource.Loading -> LaundryHistoryView.Loading
    is Resource.Error -> LaundryHistoryView.Error(MSG_LAUNDRY_HISTORY_LOAD_FAILED)
    is Resource.Success -> LaundryHistoryView.Ready(filterLaundryHistory(resource.data, filters, zone))
}
