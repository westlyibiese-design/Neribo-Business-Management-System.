package com.westly.nbms.features.bar

import kotlin.random.Random

// Pure rules for the bar: labels, drinks-menu filters and counts, the live-array edit helpers, cart rules, totals and manual-item
// validation. Nothing here touches Android or the database, so the unit tests run them directly.

internal const val BAR_MENU_COLLECTION = "cms_content"
internal const val BAR_MENU_DOC_ID = "bar_menu"
internal const val BAR_ORDERS_COLLECTION = "bar_orders"

/** Filter-pill key for "All". */
internal const val BAR_FILTER_ALL = "all"

internal const val MSG_DRINKS_LOAD_FAILED = "The drinks menu failed to load. Reload before adding or editing items."
internal const val MSG_DRINK_MISSING = "This drink no longer exists. Someone may have deleted it."
internal const val MSG_DRINK_CHECK = "Please check the drink: a name is needed and the price cannot be negative."
internal const val MSG_DRINKS_SAVE_FAILED = "The drinks menu could not be saved. Please try again."
internal const val TITLE_BAR_CANT_SAVE = "Can't save yet"
internal const val MSG_BAR_CANT_SAVE =
    "The drinks menu failed to load, so saving now could overwrite it with incomplete data. Reload the page first."

internal const val MSG_BAR_MANUAL_NAME = "Enter the item name or description."
internal const val MSG_BAR_MANUAL_PRICE = "Enter a valid price greater than 0."
internal const val MSG_BAR_MANUAL_QUANTITY = "Quantity must be at least 1."
internal const val MSG_BAR_MENU_LOAD_FAILED = "We couldn't load the drinks menu."
internal const val MSG_BAR_SALE_EMPTY = "The sale is empty."
internal const val MSG_BAR_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_BAR_SALE_TIMEOUT = "The sale took too long to save. Check your connection and try again."
internal const val MSG_BAR_SALE_GENERIC = "Something went wrong. Please try again."

/** A drinks-menu problem with a clear sentence for the person (shown in the "Error" toast). */
class DrinksMenuException(message: String) : Exception(message)

/** A bar-sale problem with a clear sentence for the person (shown in the "Failed" toast). */
class BarSaleException(message: String) : Exception(message)

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object BarServerTime

/** Rounds to 2 decimals (kobo) so stored money never carries floating-point noise. */
internal fun barMoney(value: Double): Double = Math.round(value * 100.0) / 100.0

// ── labels ──

/** The category with this stored key, or null for a missing or unknown key. */
internal fun drinkCategoryFromKey(key: String?): DrinkCategory? = DrinkCategory.entries.firstOrNull { it.key == key }

/** "Soft Drinks" for a known key; an unknown key gets a capital first letter; blank is "Other". */
internal fun drinkCategoryLabel(key: String?): String {
    drinkCategoryFromKey(key)?.let { return it.label }
    val clean = key?.trim().orEmpty()
    return if (clean.isEmpty()) "Other" else clean.replaceFirstChar { it.uppercase() }
}

/** The payment method with this stored key, or null for a missing or unknown key. */
internal fun barPaymentMethodFromKey(key: String?): BarPaymentMethod? = BarPaymentMethod.entries.firstOrNull { it.key == key }

/** "Bank Transfer" for a known key; an unknown key gets a capital first letter; blank is "—". */
internal fun barPaymentLabel(key: String?): String {
    barPaymentMethodFromKey(key)?.let { return it.label }
    val clean = key?.trim().orEmpty()
    return if (clean.isEmpty()) "—" else clean.replaceFirstChar { it.uppercase() }
}

/** The category pills: "All" first, then the six drink categories in menu order. Pair = (filter key, label). */
internal fun barCategoryPills(): List<Pair<String, String>> =
    listOf(BAR_FILTER_ALL to "All") + DrinkCategory.entries.map { it.key to it.label }

// ── menu filters and counts ──

/** "all" first, then one entry per category in menu order. */
internal fun drinkCategoryCounts(items: List<DrinkItem>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    counts[BAR_FILTER_ALL] = items.size
    DrinkCategory.entries.forEach { c -> counts[c.key] = items.count { it.category == c } }
    return counts
}

/** The drinks of one pill: [BAR_FILTER_ALL] or a category key. List order is kept. */
internal fun filterDrinks(items: List<DrinkItem>, filterKey: String): List<DrinkItem> =
    if (filterKey == BAR_FILTER_ALL) items else items.filter { it.category.key == filterKey }

/** The cards New Sale shows: only AVAILABLE drinks in the chosen category ("all" = every category). Menu order is kept. */
internal fun availableDrinksFor(items: List<DrinkItem>, filterKey: String): List<DrinkItem> =
    filterDrinks(items.filter { it.available }, filterKey)

/** "No drinks yet. Add your first one above." or, inside a category, "No drinks in Beer yet. …". */
internal fun drinksEmptyMessage(filterKey: String): String {
    val category = drinkCategoryFromKey(filterKey)
    val where = if (category != null) "in ${category.label} " else ""
    return "No drinks ${where}yet. Add your first one above."
}

// ── reading the menu document (tolerant) ──

/**
 * One entry of the `data` array as a [DrinkItem], or null when it cannot be one (not a map, or no id).
 * Missing fields get the defaults a new drink has; an unknown or missing category is Other; a price that is a number or numeric text is accepted.
 */
internal fun parseDrinkEntry(raw: Any?): DrinkItem? {
    val map = raw as? Map<*, *> ?: return null
    val id = (map["id"] as? String)?.trim().orEmpty()
    if (id.isEmpty()) return null
    val price = when (val p = map["price"]) {
        is Number -> p.toDouble()
        is String -> p.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }.let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
    return DrinkItem(
        id = id,
        name = (map["name"] as? String).orEmpty(),
        image = (map["image"] as? String).orEmpty(),
        description = (map["description"] as? String).orEmpty(),
        price = price,
        category = drinkCategoryFromKey(map["category"] as? String) ?: DrinkCategory.OTHER,
        available = map["available"] as? Boolean ?: true
    )
}

/** The whole `data` field as drinks: a missing field or anything that is not a list is an empty menu; bad entries are skipped. */
internal fun parseDrinksDocument(data: Any?): List<DrinkItem> =
    (data as? List<*>)?.mapNotNull { parseDrinkEntry(it) } ?: emptyList()

// ── writing the menu document ──

/** The single change one save applies to the live array. */
sealed interface DrinkChange {
    data class Add(val item: DrinkItem) : DrinkChange
    data class Replace(val item: DrinkItem) : DrinkChange          // by id
    data class Remove(val id: String) : DrinkChange
    data class SetAvailable(val id: String, val available: Boolean) : DrinkChange
}

/** One drink as it is stored in the array: id, name, image, description, price, category key, available. */
internal fun drinkToMap(item: DrinkItem): Map<String, Any?> = mapOf(
    "id" to item.id,
    "name" to item.name,
    "image" to item.image,
    "description" to item.description,
    "price" to item.price,
    "category" to item.category.key,
    "available" to item.available
)

private fun drinkEntryId(entry: Any?): String? = (entry as? Map<*, *>)?.get("id") as? String

/**
 * Applies ONE change to the LIVE array (the raw `data` list the transaction just read) and returns the new array.
 * Entries the change does not touch are returned exactly as they were (even ones this app cannot read), so two people
 * editing different drinks never overwrite each other.
 *
 * - [DrinkChange.Add]: appended; if an entry with that id is already there it is replaced instead (never a duplicate).
 * - [DrinkChange.Replace] / [DrinkChange.SetAvailable]: the entry with that id; a missing id throws [DrinksMenuException].
 * - [DrinkChange.Remove]: the entry with that id is dropped; a missing id changes nothing (already gone).
 */
internal fun applyDrinkChange(live: List<Any?>, change: DrinkChange): List<Any?> = when (change) {
    is DrinkChange.Add -> {
        val map = drinkToMap(change.item)
        if (live.any { drinkEntryId(it) == change.item.id }) live.map { if (drinkEntryId(it) == change.item.id) map else it }
        else live + map
    }
    is DrinkChange.Replace -> {
        if (live.none { drinkEntryId(it) == change.item.id }) throw DrinksMenuException(MSG_DRINK_MISSING)
        val map = drinkToMap(change.item)
        live.map { if (drinkEntryId(it) == change.item.id) map else it }
    }
    is DrinkChange.Remove -> live.filterNot { drinkEntryId(it) == change.id }
    is DrinkChange.SetAvailable -> {
        if (live.none { drinkEntryId(it) == change.id }) throw DrinksMenuException(MSG_DRINK_MISSING)
        live.map { entry ->
            if (drinkEntryId(entry) != change.id) entry
            else {
                val copy = LinkedHashMap<Any?, Any?>((entry as Map<*, *>))
                copy["available"] = change.available
                copy
            }
        }
    }
}

/** What one save writes (merge) to `cms_content/bar_menu`: the new array and the server time. */
internal fun buildDrinksPayload(newArray: List<Any?>): Map<String, Any?> = mapOf(
    "data" to newArray,
    "updatedAt" to BarServerTime
)

private const val BAR_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** A new drink id: 8 random lowercase letters and digits. */
internal fun generateDrinkId(random: Random = Random.Default): String =
    buildString { repeat(8) { append(BAR_ID_ALPHABET[random.nextInt(BAR_ID_ALPHABET.length)]) } }

/** A drink the database may store: a name and a price of 0 or more (a NaN or infinite price is not valid). */
internal fun isValidDrink(item: DrinkItem): Boolean =
    item.name.isNotBlank() && !item.price.isNaN() && !item.price.isInfinite() && item.price >= 0.0

// ── the Add / Edit drink form ──

/** What the person typed in the Add / Edit form. [categoryKey] holds the stored key; the price is typed text. */
data class DrinkFormState(
    val name: String = "",
    val categoryKey: String = DrinkCategory.BEER.key,
    val image: String = "",
    val description: String = "",
    val priceText: String = "0",
    val available: Boolean = true
)

/** A form that starts out holding an existing drink (editing). */
internal fun drinkFormOf(item: DrinkItem): DrinkFormState = DrinkFormState(
    name = item.name,
    categoryKey = item.category.key,
    image = item.image,
    description = item.description,
    priceText = drinkPriceText(item.price),
    available = item.available
)

/** 1500.0 -> "1500", 99.5 -> "99.5": the number as it is typed in the price box. */
internal fun drinkPriceText(price: Double): String =
    if (price == Math.floor(price) && price < 1e15) price.toLong().toString() else price.toString()

/** The typed price. Blank means 0 (the default); text that is not a finite number, or is negative, is null. */
internal fun parseDrinkPrice(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return 0.0
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps digits, at most one dot and a leading minus while the person types (a minus is kept so a negative price can be flagged). */
internal fun filterDrinkPriceInput(text: String): String {
    var seenDot = false
    val out = StringBuilder()
    text.forEachIndexed { index, c ->
        when {
            c in '0'..'9' -> out.append(c)
            c == '.' && !seenDot -> { seenDot = true; out.append(c) }
            c == '-' && index == 0 -> out.append(c)
        }
    }
    return out.toString()
}

internal data class DrinkFormErrors(val name: String? = null, val price: String? = null) {
    val any: Boolean get() = name != null || price != null
}

internal fun validateDrinkForm(form: DrinkFormState): DrinkFormErrors = DrinkFormErrors(
    name = if (form.name.isBlank()) "Name is required." else null,
    price = if (parseDrinkPrice(form.priceText) == null) "Enter a price of 0 or more." else null
)

/** Save is allowed only with a name and a price of 0 or more. */
internal fun canSaveDrinkForm(form: DrinkFormState, saving: Boolean = false): Boolean = !saving && !validateDrinkForm(form).any

/** The drink a form describes, with the given [id] (the id of the drink being edited, or a new one). */
internal fun drinkFromForm(form: DrinkFormState, id: String): DrinkItem = DrinkItem(
    id = id,
    name = form.name.trim(),
    image = form.image.trim(),
    description = form.description.trim(),
    price = parseDrinkPrice(form.priceText) ?: 0.0,
    category = drinkCategoryFromKey(form.categoryKey) ?: DrinkCategory.BEER,
    available = form.available
)

// ── cart ──

/** One line of the sale being built. Menu lines use the drink's id; manual lines use `manual-{millis}-{random6}`. */
data class BarCartLine(
    val id: String,
    val name: String,
    val price: Double,
    val quantity: Int,
    val isManual: Boolean = false
)

/** What the person typed in the sale panel (everything except the lines). */
data class BarSaleForm(
    val roomNumber: String = "",
    val tableNumber: String = "",
    val guestName: String = "",
    val notes: String = "",
    val payment: BarPaymentMethod = BarPaymentMethod.CASH
)

/** What the success screen shows after a recorded sale (a snapshot, because the sale panel is cleared). */
data class BarSaleSuccess(val itemCount: Int, val total: Double)

/** The result of a recorded sale. */
data class BarSaleResult(val saleId: String, val total: Double, val itemCount: Int)

/** Tap on a drink card: quantity + 1 when the drink is already in the sale, else a new line of 1. There is no stock limit. */
internal fun addDrinkToCart(cart: List<BarCartLine>, item: DrinkItem): List<BarCartLine> =
    if (cart.any { it.id == item.id && !it.isManual }) {
        cart.map { if (it.id == item.id && !it.isManual) it.copy(quantity = it.quantity + 1) else it }
    } else {
        cart + BarCartLine(item.id, item.name, item.price, 1, false)
    }

/** The + button. */
internal fun incrementBarLine(cart: List<BarCartLine>, id: String): List<BarCartLine> =
    cart.map { if (it.id == id) it.copy(quantity = it.quantity + 1) else it }

/** The − button: quantity − 1; reaching 0 removes the line. */
internal fun decrementBarLine(cart: List<BarCartLine>, id: String): List<BarCartLine> =
    cart.mapNotNull { if (it.id != id) it else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1) }

internal fun barLineSubtotal(line: BarCartLine): Double = barMoney(line.price * line.quantity)

internal fun barCartTotal(cart: List<BarCartLine>): Double = barMoney(cart.sumOf { barLineSubtotal(it) })

// ── manual entry ──

data class BarManualErrors(val name: String? = null, val price: String? = null, val quantity: String? = null) {
    val any: Boolean get() = name != null || price != null || quantity != null
}

/** The typed price: a finite number above 0. Anything else is null. */
internal fun parseBarPrice(text: String): Double? {
    val v = text.trim().toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite() || v <= 0.0) null else v
}

/** The typed quantity: a whole number of 1 or more. Anything else is null. */
internal fun parseBarQuantity(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    val v = clean.toIntOrNull() ?: return null
    return if (v < 1) null else v
}

internal fun validateBarManualItem(name: String, priceText: String, quantityText: String): BarManualErrors = BarManualErrors(
    name = if (name.isBlank()) MSG_BAR_MANUAL_NAME else null,
    price = if (parseBarPrice(priceText) == null) MSG_BAR_MANUAL_PRICE else null,
    quantity = if (parseBarQuantity(quantityText) == null) MSG_BAR_MANUAL_QUANTITY else null
)

/** Keeps digits and the first dot while the person types a price. */
internal fun filterBarPriceInput(text: String): String {
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

internal fun filterBarQuantityInput(text: String): String = text.filter { it in '0'..'9' }.take(6)

/** A new manual line. Id: `manual-{millis}-{random6}`. */
internal fun manualBarLine(name: String, price: Double, quantity: Int, millis: Long, random6: String): BarCartLine =
    BarCartLine("manual-$millis-$random6", name.trim(), price, quantity, true)

/** Six random lowercase letters and digits for a manual id. */
internal fun barRandom6(random: java.util.Random = java.util.Random()): String =
    buildString { repeat(6) { append(BAR_ID_ALPHABET[random.nextInt(BAR_ID_ALPHABET.length)]) } }

// ── the new bar sale document ──

private fun String.orNullIfBlank(): String? = trim().ifEmpty { null }

/**
 * The new `bar_orders/{id}` document, exactly as 2.5 describes it: operational `status` and revenue `approvalStatus` both start as
 * "pending", the approval fields are null, blank guest/room/table/notes are null, and `createdAt` is the server time.
 */
internal fun buildBarSalePayload(cart: List<BarCartLine>, form: BarSaleForm, attendantId: String, attendantName: String): Map<String, Any?> = mapOf(
    "barAttendantId" to attendantId,
    "barAttendantName" to attendantName,
    "customerName" to form.guestName.orNullIfBlank(),
    "roomNumber" to form.roomNumber.orNullIfBlank(),
    "tableNumber" to form.tableNumber.orNullIfBlank(),
    "items" to cart.map {
        mapOf(
            "id" to it.id,
            "name" to it.name,
            "price" to it.price,
            "quantity" to it.quantity,
            "subtotal" to barLineSubtotal(it),
            "isManual" to it.isManual
        )
    },
    "total" to barCartTotal(cart),
    "paymentMethod" to form.payment.key,
    "notes" to form.notes.orNullIfBlank(),
    "hasManualItems" to cart.any { it.isManual },
    "status" to BarSaleStatus.PENDING.key,
    "approvalStatus" to "pending",
    "approvedBy" to null,
    "approvedByName" to null,
    "approvedAt" to null,
    "rejectedReason" to null,
    "createdAt" to BarServerTime,
    "isDeleted" to false
)
