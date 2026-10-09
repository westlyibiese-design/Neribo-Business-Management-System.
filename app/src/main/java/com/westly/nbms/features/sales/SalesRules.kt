package com.westly.nbms.features.sales

import com.westly.nbms.features.inventory.InventoryItem
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal const val SELLING_MARKUP = 1.3
internal const val ALL_CATEGORIES = "all"

internal const val MSG_MANUAL_NAME = "Enter the item name or description."
internal const val MSG_MANUAL_PRICE = "Enter a valid price greater than 0."
internal const val MSG_MANUAL_QUANTITY = "Quantity must be at least 1."
internal const val MSG_STOCK_LIMIT = "Cannot add more than available stock."
internal const val MSG_SALES_LOAD_FAILED = "We couldn't load sales history."

/** Rounds to 2 decimals (kobo) so stored money never carries floating-point noise. */
internal fun money(value: Double): Double = Math.round(value * 100.0) / 100.0

/** The price a customer pays: cost × 1.3. */
internal fun sellingPrice(costPerUnit: Double): Double = money(costPerUnit * SELLING_MARKUP)

/** "cleaning_supplies" -> "Cleaning Supplies"; blank is "—". */
internal fun salesWords(raw: String?): String {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) return "—"
    return clean.replace('_', ' ').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

// ── catalog ──

/** "all" first, then every distinct category of the (non-deleted) inventory, alphabetical. */
internal fun categoriesOf(items: List<InventoryItem>): List<String> =
    listOf(ALL_CATEGORIES) + items.filter { !it.isDeleted }.map { it.category }.distinct().sorted()

/** Items that can be sold: in stock, not deleted, in the chosen category ("all" = every category). */
internal fun catalogFor(items: List<InventoryItem>, category: String): List<InventoryItem> =
    items.filter { !it.isDeleted && it.quantity > 0 && (category == ALL_CATEGORIES || it.category == category) }

// ── cart ──

internal sealed interface CartAdd {
    data class Added(val cart: List<CartItem>) : CartAdd
    data object StockLimit : CartAdd
}

/** Tap on a catalog card: stock limit when the cart already holds all of it, else quantity + 1 or a new line of 1. */
internal fun addCatalogItem(cart: List<CartItem>, item: InventoryItem): CartAdd {
    val existing = cart.firstOrNull { it.id == item.id }
    if (existing == null) {
        if (item.quantity < 1) return CartAdd.StockLimit
        return CartAdd.Added(
            cart + CartItem(item.id, item.name, sellingPrice(item.costPerUnit), 1, item.quantity, false)
        )
    }
    if (existing.quantity >= item.quantity) return CartAdd.StockLimit
    return CartAdd.Added(cart.map { if (it.id == item.id) it.copy(quantity = it.quantity + 1) else it })
}

/** The + button: ignored once the quantity reaches [CartItem.available]. */
internal fun incrementLine(cart: List<CartItem>, id: String): List<CartItem> =
    cart.map { if (it.id == id && it.quantity < it.available) it.copy(quantity = it.quantity + 1) else it }

/** The − button: quantity − 1; reaching 0 removes the line. */
internal fun decrementLine(cart: List<CartItem>, id: String): List<CartItem> =
    cart.mapNotNull { if (it.id != id) it else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1) }

internal fun lineTotal(item: CartItem): Double = money(item.price * item.quantity)

internal fun cartTotal(cart: List<CartItem>): Double = money(cart.sumOf { lineTotal(it) })

// ── manual entry ──

data class ManualErrors(val name: String? = null, val price: String? = null, val quantity: String? = null) {
    val any: Boolean get() = name != null || price != null || quantity != null
}

/** The typed price: a number above 0 (finite). Anything else is null. */
internal fun parseManualPrice(text: String): Double? {
    val v = text.trim().toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite() || v <= 0.0) null else v
}

/** The typed quantity: a whole number of 1 or more. Anything else is null. */
internal fun parseManualQuantity(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    val v = clean.toIntOrNull() ?: return null
    return if (v < 1) null else v
}

internal fun validateManualItem(name: String, priceText: String, quantityText: String): ManualErrors = ManualErrors(
    name = if (name.isBlank()) MSG_MANUAL_NAME else null,
    price = if (parseManualPrice(priceText) == null) MSG_MANUAL_PRICE else null,
    quantity = if (parseManualQuantity(quantityText) == null) MSG_MANUAL_QUANTITY else null
)

/** Keeps digits and the first dot while the person types a price. */
internal fun filterPriceInput(text: String): String {
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

internal fun filterQuantityInput(text: String): String = text.filter { it in '0'..'9' }.take(6)

/** A new manual line. Id: `manual-{millis}-{random6}`. */
internal fun manualCartItem(name: String, price: Double, quantity: Int, millis: Long, random6: String): CartItem =
    CartItem("manual-$millis-$random6", name.trim(), price, quantity, UNLIMITED_STOCK, true)

private val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
internal fun random6(random: java.util.Random = java.util.Random()): String =
    buildString { repeat(6) { append(ID_CHARS[random.nextInt(ID_CHARS.length)]) } }

// ── history ──

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun currentMonth(zone: ZoneId, now: Instant = Instant.now()): String = YearMonth.from(now.atZone(zone)).toString()

internal fun monthLabel(month: String): String = try {
    val ym = YearMonth.parse(month)
    "${ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${ym.year}"
} catch (e: Exception) {
    month
}

internal fun zoneOf(timezone: String?): ZoneId = try {
    ZoneId.of(timezone ?: "Africa/Lagos")
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

/**
 * The rows History shows: deleted removed, month "yyyy-MM" (blank = all months, judged in [zone]), search over staff and
 * customer name (trimmed, case-insensitive), newest first with undated rows last.
 */
internal fun filterSales(sales: List<Sale>, search: String, month: String, zone: ZoneId): List<Sale> {
    val ym = month.trim().takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val needle = search.trim()
    fun instantOf(s: Sale): Instant? = s.createdAt?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
    return sales
        .filter { !it.isDeleted }
        .filter { s -> ym == null || instantOf(s)?.let { YearMonth.from(it.atZone(zone)) == ym } == true }
        .filter { s ->
            needle.isEmpty() || s.staffName.contains(needle, ignoreCase = true) ||
                (s.customerName?.contains(needle, ignoreCase = true) == true)
        }
        .sortedByDescending { instantOf(it)?.toEpochMilli() ?: Long.MIN_VALUE }
}

internal fun salesTotal(rows: List<Sale>): Double = money(rows.sumOf { it.total })

internal fun historySubtitle(rows: List<Sale>, symbol: String): String =
    "${rows.size} sales · ${com.westly.nbms.core.util.Format.currency(salesTotal(rows), symbol)} total"

/** "Bank Transfer" style text for the payment column. */
internal fun paymentText(key: String?): String = salesWords(key)

internal fun customerText(s: Sale): String = s.customerName?.trim()?.takeIf { it.isNotEmpty() } ?: "Walk-in"

/** The first two item chips ("Bottled Water ×2") and how many more there are. */
internal fun itemChips(items: List<SaleLine>): Pair<List<String>, Int> =
    items.take(2).map { "${it.name} ×${it.quantity}" } to maxOf(0, items.size - 2)
