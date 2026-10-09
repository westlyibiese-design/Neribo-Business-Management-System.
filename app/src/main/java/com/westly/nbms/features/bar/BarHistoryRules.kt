package com.westly.nbms.features.bar

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.inventory.InventoryItem
import com.westly.nbms.features.inventory.InventoryLogic
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// Pure rules for Part 21B: Sales History filters and figures, the "Mark Served" update, the drinks-stock view, restock math,
// the Add Bar Stock Item form and who may do what. Nothing here touches Android or the database, so the unit tests run it directly.

internal const val BAR_HISTORY_ORDERS_PATH = "bar_orders"
internal const val BAR_STOCK_PATH = "inventory"
internal const val BAR_STOCK_CATEGORY = "drinks"
internal const val BAR_STOCK_DEFAULT_UNIT = "bottles"

/** Filter key for "All Status". */
internal const val BAR_HISTORY_STATUS_ALL = ""

internal const val MSG_BAR_HISTORY_LOAD_FAILED = "We couldn't load bar sales."
internal const val MSG_BAR_STOCK_LOAD_FAILED = "We couldn't load bar inventory."
internal const val MSG_BAR_HISTORY_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_BAR_HISTORY_NOT_ALLOWED = "You are not allowed to update bar sales."
internal const val MSG_BAR_HISTORY_NOT_PENDING = "Only pending sales can be marked as served."
internal const val MSG_BAR_HISTORY_TIMEOUT = "The update took too long. Check your connection and try again."
internal const val MSG_BAR_HISTORY_GENERIC = "Something went wrong. Please try again."
internal const val MSG_BAR_STOCK_ITEM_MISSING = "This item no longer exists."
internal const val MSG_BAR_STOCK_RESTOCK_AMOUNT = "Enter a whole number of 1 or more."
internal const val MSG_BAR_STOCK_TOO_LARGE = "That quantity is too large."
internal const val MSG_BAR_STOCK_CHECK_FORM = "Please check the form."

/** A bar-history or bar-stock problem with a clear sentence for the person (shown in the "Failed" / "Update Failed" toast). */
class BarStockException(message: String) : Exception(message)

/** Stands for the server time inside a Sales History payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object BarHistoryServerTime

/** Stands for the server time inside a Bar Inventory payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object BarStockServerTime

// ── who may do what ──

/** Mark Served: super admin, bar attendant, manager. Accountant and operations manager are read-only. */
internal fun canMarkBarSaleServed(role: Role?): Boolean =
    role == Role.SUPER_ADMIN || role == Role.BAR_ATTENDANT || role == Role.MANAGER

/** The Mark Served button shows only on pending sales, and only to a role that may use it. */
internal fun showMarkServed(role: Role?, sale: BarSale): Boolean =
    canMarkBarSaleServed(role) && sale.status == BarSaleStatus.PENDING

/** Add Item (Bar Inventory): super admin and manager only. */
internal fun canAddBarStock(role: Role?): Boolean = role == Role.SUPER_ADMIN || role == Role.MANAGER

/** Restock: everyone allowed to open Bar Inventory. */
internal fun canRestockBarStock(role: Role?): Boolean =
    role == Role.SUPER_ADMIN || role == Role.MANAGER || role == Role.ACCOUNTANT || role == Role.BAR_ATTENDANT

/** The attendant id to query for. The bar attendant sees only their own sales; every other role sees all of them. */
internal fun barHistoryScopeFor(role: Role?, uid: String): String? = if (role == Role.BAR_ATTENDANT) uid else null

// ── months ──

internal fun barHistoryZone(timezone: String?): ZoneId = try {
    ZoneId.of(timezone ?: "Africa/Lagos")
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun barHistoryCurrentMonth(zone: ZoneId, now: Instant = Instant.now()): String =
    YearMonth.from(now.atZone(zone)).toString()

/** "October 2026"; text that is not a month is returned unchanged. */
internal fun barHistoryMonthLabel(month: String): String = try {
    val ym = YearMonth.parse(month)
    "${ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${ym.year}"
} catch (e: Exception) {
    month
}

/** Blank ("All months") followed by the current month and the 23 before it. */
internal fun barHistoryMonthOptions(zone: ZoneId, now: Instant = Instant.now(), count: Int = 24): List<String> {
    val current = YearMonth.from(now.atZone(zone))
    return listOf("") + (0 until count).map { current.minusMonths(it.toLong()).toString() }
}

// ── Sales History ──

/** The status dropdown: (filter key, label). The first entry is "All Status". */
internal fun barHistoryStatusOptions(): List<Pair<String, String>> =
    listOf(BAR_HISTORY_STATUS_ALL to "All Status") + BarSaleStatus.entries.map { it.key to it.label }

/** What the three controls above the list hold. [month] is "yyyy-MM" (blank = every month); [status] is a status key (blank = all). */
internal data class BarHistoryFilters(val month: String, val search: String = "", val status: String = BAR_HISTORY_STATUS_ALL)

/**
 * The rows Sales History shows: deleted sales removed, then month (judged in [zone]), search over attendant name, guest name and
 * room number (trimmed, ignoring case) and status; newest first, sales without a date last.
 */
internal fun filterBarHistory(sales: List<BarSale>, filters: BarHistoryFilters, zone: ZoneId): List<BarSale> {
    val ym = filters.month.trim().takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val needle = filters.search.trim()
    val status = filters.status.trim()
    return sales
        .filter { !it.isDeleted }
        .filter { s -> ym == null || s.createdAt?.let { YearMonth.from(it.atZone(zone)) == ym } == true }
        .filter { s ->
            needle.isEmpty() ||
                s.barAttendantName.contains(needle, ignoreCase = true) ||
                (s.customerName?.contains(needle, ignoreCase = true) == true) ||
                (s.roomNumber?.contains(needle, ignoreCase = true) == true)
        }
        .filter { s -> status.isEmpty() || s.status.key == status }
        .sortedByDescending { it.createdAt?.toEpochMilli() ?: Long.MIN_VALUE }
}

/** Rounds to 2 decimals (kobo) so a total never carries floating-point noise. */
internal fun barHistoryMoney(value: Double): Double = Math.round(value * 100.0) / 100.0

/** How many sales and how much they add up to. Cancelled sales are left out of both. */
internal fun barHistoryCount(rows: List<BarSale>): Int = rows.count { it.status != BarSaleStatus.CANCELLED }

internal fun barHistoryTotal(rows: List<BarSale>): Double =
    barHistoryMoney(rows.filter { it.status != BarSaleStatus.CANCELLED }.sumOf { it.total })

/** "12 sales · ₦45,000" (cancelled sales are not counted). */
internal fun barHistorySubtitle(rows: List<BarSale>, symbol: String): String =
    "${barHistoryCount(rows)} sales · ${Format.currency(barHistoryTotal(rows), symbol)}"

/** "12 Mar 2025, 14:30" in the business time zone; "—" when the sale has no date yet. (Format works with kotlinx Instants.) */
internal fun barHistoryDateTime(at: Instant?, tz: kotlinx.datetime.TimeZone): String =
    Format.dateTime(at?.let { kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong()) }, tz)

/** The guest name, or "Walk-in" when none was typed. */
internal fun barHistoryGuestText(sale: BarSale): String = sale.customerName?.trim()?.takeIf { it.isNotEmpty() } ?: "Walk-in"

/** "Room 204" or "Table B-03"; the room wins when both are set. Null when neither is. */
internal fun barHistoryLocationText(sale: BarSale): String? {
    val room = sale.roomNumber?.trim()?.takeIf { it.isNotEmpty() }
    if (room != null) return "Room $room"
    val table = sale.tableNumber?.trim()?.takeIf { it.isNotEmpty() }
    return table?.let { "Table $it" }
}

/** The first two item chips ("Heineken 60cl ×2") and how many more there are (shown as "+{n}"). */
internal fun barHistoryChips(items: List<BarSaleLine>): Pair<List<String>, Int> =
    items.take(2).map { "${it.name} ×${it.quantity}" } to maxOf(0, items.size - 2)

/** The update Mark Served writes: `{status: "served", updatedAt, updatedBy}`. */
internal fun buildMarkServedFields(uid: String): Map<String, Any?> = mapOf(
    "status" to BarSaleStatus.SERVED.key,
    "updatedAt" to BarHistoryServerTime,
    "updatedBy" to uid
)

/** The toast after Mark Served: title "Sale Updated", message "Status → served". */
internal const val TITLE_BAR_SALE_UPDATED = "Sale Updated"
internal const val MSG_BAR_SALE_UPDATED = "Status → served"
internal const val TITLE_BAR_SALE_UPDATE_FAILED = "Update Failed"

/** What the Sales History page is showing. */
internal sealed interface BarHistoryView {
    data object Loading : BarHistoryView
    data class Error(val message: String) : BarHistoryView
    data class Ready(val rows: List<BarSale>) : BarHistoryView
}

internal fun barHistoryViewOf(resource: Resource<List<BarSale>>, filters: BarHistoryFilters, zone: ZoneId): BarHistoryView = when (resource) {
    is Resource.Loading -> BarHistoryView.Loading
    is Resource.Error -> BarHistoryView.Error(MSG_BAR_HISTORY_LOAD_FAILED)
    is Resource.Success -> BarHistoryView.Ready(filterBarHistory(resource.data, filters, zone))
}

// ── Bar Inventory ──

/** The drinks stock: `category == "drinks"` and not deleted (deleted ones are removed here, on the client), by name. */
internal fun drinksStockOf(items: List<InventoryItem>): List<InventoryItem> =
    items.filter { it.category == BAR_STOCK_CATEGORY && !it.isDeleted }.sortedBy { it.name.trim().lowercase() }

/** Items whose name contains [search] (trimmed, ignoring case). Order is kept. */
internal fun filterBarStock(items: List<InventoryItem>, search: String): List<InventoryItem> {
    val needle = search.trim()
    return if (needle.isEmpty()) items else items.filter { it.name.contains(needle, ignoreCase = true) }
}

/** Low = at or below the minimum, decided by the shared Phase 18 rule. */
internal fun isBarStockLow(item: InventoryItem): Boolean = InventoryLogic.isLow(item)

internal fun lowBarStock(items: List<InventoryItem>): List<InventoryItem> = items.filter { isBarStockLow(it) }

/** "{n} drink stock items". */
internal fun barStockSubtitle(count: Int): String = "$count drink stock items"

internal fun barStockLowHeading(count: Int): String = "Low Stock Alert ($count items)"

/** "Heineken 60cl: 3/5 bottles". */
internal fun barStockLowChip(item: InventoryItem): String = "${item.name}: ${item.quantity}/${item.minStock} ${item.unit}"

/** The quantity after a restock. Throws [BarStockException] for an amount below 1, or a sum too big for an Int. */
internal fun barRestockedQuantity(before: Int, amount: Int): Int {
    if (amount < 1) throw BarStockException(MSG_BAR_STOCK_RESTOCK_AMOUNT)
    return try {
        Math.addExact(before, amount)
    } catch (e: ArithmeticException) {
        throw BarStockException(MSG_BAR_STOCK_TOO_LARGE)
    }
}

/** The fields a restock writes to `inventory/{id}`: `{quantity, updatedAt}`. */
internal fun buildBarRestockFields(after: Int): Map<String, Any?> = mapOf(
    "quantity" to after,
    "updatedAt" to BarStockServerTime
)

/** The typed text as a whole number of 0 or more; blank, decimals, letters and numbers too big for an Int are null. */
internal fun parseBarWhole(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    return clean.toIntOrNull()
}

/** The restock amount: a whole number of 1 or more, otherwise null. */
internal fun parseBarRestockAmount(text: String): Int? = parseBarWhole(text)?.takeIf { it >= 1 }

/** The typed cost: blank means 0, otherwise a number of 0 or more. Anything else is null. */
internal fun parseBarCost(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return 0.0
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps only digits (at most 9, so the number always fits an Int) while the person types. */
internal fun filterBarWholeInput(text: String): String = text.filter { it in '0'..'9' }.take(9)

/** Keeps only digits and the first dot while the person types a cost. */
internal fun filterBarCostInput(text: String): String {
    var seenDot = false
    val out = StringBuilder()
    for (c in text) {
        if (c in '0'..'9') out.append(c)
        else if (c == '.' && !seenDot) {
            seenDot = true
            out.append(c)
        }
    }
    return out.toString()
}

/** What the person typed in the Add Bar Stock Item dialog. */
data class BarStockForm(
    val name: String = "",
    val quantityText: String = "",
    val unit: String = BAR_STOCK_DEFAULT_UNIT,
    val costText: String = "",
    val thresholdText: String = ""
)

internal data class BarStockErrors(
    val name: String? = null,
    val quantity: String? = null,
    val cost: String? = null,
    val threshold: String? = null
) {
    val any: Boolean get() = name != null || quantity != null || cost != null || threshold != null
    val first: String? get() = name ?: quantity ?: cost ?: threshold
}

internal fun validateBarStockForm(form: BarStockForm): BarStockErrors = BarStockErrors(
    name = if (form.name.isBlank()) "Item name is required." else null,
    quantity = if (parseBarWhole(form.quantityText) == null) "Enter a whole number of 0 or more." else null,
    cost = if (parseBarCost(form.costText) == null) "Enter an amount of 0 or more." else null,
    threshold = if (parseBarWhole(form.thresholdText) == null) "Enter a whole number of 0 or more." else null
)

/**
 * The new `inventory/{id}` document, with the same fields Phase 18 writes: `category` "drinks", `isDeleted` false, `lastRestocked`
 * and `createdAt` server time, `costPerUnit` 0 when blank, `supplier` null. A blank unit falls back to "bottles".
 */
internal fun buildBarStockPayload(form: BarStockForm): Map<String, Any?> = mapOf(
    "name" to form.name.trim(),
    "category" to BAR_STOCK_CATEGORY,
    "quantity" to (parseBarWhole(form.quantityText) ?: 0),
    "minStock" to (parseBarWhole(form.thresholdText) ?: 0),
    "unit" to form.unit.trim().ifEmpty { BAR_STOCK_DEFAULT_UNIT },
    "costPerUnit" to (parseBarCost(form.costText) ?: 0.0),
    "supplier" to null,
    "isDeleted" to false,
    "lastRestocked" to BarStockServerTime,
    "createdAt" to BarStockServerTime
)

/** What the Bar Inventory page is showing. [all] is every drinks item (the Restock dialog looks its item up there); [rows] is searched. */
internal sealed interface BarStockView {
    data object Loading : BarStockView
    data class Error(val message: String) : BarStockView
    data class Ready(val all: List<InventoryItem>, val lowItems: List<InventoryItem>, val rows: List<InventoryItem>) : BarStockView
}

internal fun barStockViewOf(resource: Resource<List<InventoryItem>>, search: String): BarStockView = when (resource) {
    is Resource.Loading -> BarStockView.Loading
    is Resource.Error -> BarStockView.Error(MSG_BAR_STOCK_LOAD_FAILED)
    is Resource.Success -> {
        val all = drinksStockOf(resource.data)
        BarStockView.Ready(all = all, lowItems = lowBarStock(all), rows = filterBarStock(all, search))
    }
}
