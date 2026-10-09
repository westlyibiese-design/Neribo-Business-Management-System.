package com.westly.nbms.features.restaurant

import kotlin.random.Random

// Pure rules for the restaurant menu. Nothing here touches Android or the database, so the unit tests run them directly.

internal const val MENU_COLLECTION = "cms_content"
internal const val MENU_DOC_ID = "restaurant_menu"

internal const val MSG_MENU_LOAD_FAILED = "The menu failed to load. Reload before adding or editing items."
internal const val MSG_MENU_ITEM_MISSING = "This item no longer exists. Someone may have deleted it."
internal const val MSG_MENU_CHECK_ITEM = "Please check the item: a name is needed and the price cannot be negative."
internal const val MSG_MENU_SAVE_FAILED = "The menu could not be saved. Please try again."
internal const val TITLE_CANT_SAVE = "Can't save yet"
internal const val MSG_CANT_SAVE =
    "The menu failed to load, so saving now could overwrite it with incomplete data. Reload the page first."

/** A problem with a clear sentence for the person (shown in the "Error" toast). */
class MenuException(message: String) : Exception(message)

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object MenuServerTime

// ── categories, counts and filters ──

/** Filter-chip key for "All". */
internal const val FILTER_ALL = "all"

/** "Breakfast" for a known key; an unknown key is shown with a capital first letter; blank is "Other". */
internal fun menuCategoryLabel(key: String?): String {
    MenuCategory.fromKey(key)?.let { return it.label }
    val clean = key?.trim().orEmpty()
    return if (clean.isEmpty()) "Other" else clean.replaceFirstChar { it.uppercase() }
}

/** "all" first, then one entry per category in menu order. Items with an unknown category count only under "all". */
internal fun menuCategoryCounts(items: List<MenuItem>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    counts[FILTER_ALL] = items.size
    MenuCategory.entries.forEach { c -> counts[c.key] = items.count { it.category == c.key } }
    return counts
}

/** The items of one chip: [FILTER_ALL] or a category key. List order is kept. */
internal fun filterMenu(items: List<MenuItem>, filterKey: String): List<MenuItem> =
    if (filterKey == FILTER_ALL) items else items.filter { it.category == filterKey }

/** "No menu items yet. Add your first one above." or, inside a category, "No menu items in Lunch yet. …". */
internal fun menuEmptyMessage(filterKey: String): String {
    val category = MenuCategory.fromKey(filterKey)
    val where = if (category != null) "in ${category.label} " else ""
    return "No menu items ${where}yet. Add your first one above."
}

// ── reading the document (tolerant) ──

/**
 * One entry of the `data` array as a [MenuItem], or null when it cannot be one (not a map, or no id).
 * Missing fields get the same defaults a new item has; a price that is a number or numeric text is accepted.
 */
internal fun parseMenuEntry(raw: Any?): MenuItem? {
    val map = raw as? Map<*, *> ?: return null
    val id = (map["id"] as? String)?.trim().orEmpty()
    if (id.isEmpty()) return null
    val price = when (val p = map["price"]) {
        is Number -> p.toDouble()
        is String -> p.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }.let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
    return MenuItem(
        id = id,
        name = (map["name"] as? String).orEmpty(),
        image = (map["image"] as? String).orEmpty(),
        description = (map["description"] as? String).orEmpty(),
        price = price,
        category = (map["category"] as? String) ?: MenuCategory.BREAKFAST.key,
        available = map["available"] as? Boolean ?: true
    )
}

/** The whole `data` field as items: a missing field or anything that is not a list is an empty menu; bad entries are skipped. */
internal fun parseMenuDocument(data: Any?): List<MenuItem> =
    (data as? List<*>)?.mapNotNull { parseMenuEntry(it) } ?: emptyList()

// ── writing the document ──

/** One item as it is stored in the array: exactly Westly's seven fields. */
internal fun menuItemToMap(item: MenuItem): Map<String, Any?> = mapOf(
    "id" to item.id,
    "name" to item.name,
    "image" to item.image,
    "description" to item.description,
    "price" to item.price,
    "category" to item.category,
    "available" to item.available
)

private fun entryId(entry: Any?): String? = (entry as? Map<*, *>)?.get("id") as? String

/**
 * Applies ONE change to the LIVE array (the raw `data` list the transaction just read) and returns the new array.
 * Entries the change does not touch are returned exactly as they were (even ones this app cannot read), so two people
 * editing different items never overwrite each other.
 *
 * - [MenuChange.Add]: appended; if an entry with that id is already there it is replaced instead (never a duplicate).
 * - [MenuChange.Replace] / [MenuChange.SetAvailable]: the entry with that id; a missing id throws [MenuException].
 * - [MenuChange.Remove]: the entry with that id is dropped; a missing id changes nothing (already gone).
 */
internal fun applyMenuChange(live: List<Any?>, change: MenuChange): List<Any?> = when (change) {
    is MenuChange.Add -> {
        val map = menuItemToMap(change.item)
        if (live.any { entryId(it) == change.item.id }) live.map { if (entryId(it) == change.item.id) map else it }
        else live + map
    }
    is MenuChange.Replace -> {
        if (live.none { entryId(it) == change.item.id }) throw MenuException(MSG_MENU_ITEM_MISSING)
        val map = menuItemToMap(change.item)
        live.map { if (entryId(it) == change.item.id) map else it }
    }
    is MenuChange.Remove -> live.filterNot { entryId(it) == change.id }
    is MenuChange.SetAvailable -> {
        if (live.none { entryId(it) == change.id }) throw MenuException(MSG_MENU_ITEM_MISSING)
        live.map { entry ->
            if (entryId(entry) != change.id) entry
            else {
                val copy = LinkedHashMap<Any?, Any?>((entry as Map<*, *>))
                copy["available"] = change.available
                copy
            }
        }
    }
}

/** What one save writes (merge) to `cms_content/restaurant_menu`: the new array and the server time. */
internal fun buildMenuPayload(newArray: List<Any?>): Map<String, Any?> = mapOf(
    "data" to newArray,
    "updatedAt" to MenuServerTime
)

private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** A new item id: 8 random lowercase letters and digits. */
internal fun generateMenuItemId(random: Random = Random.Default): String =
    buildString { repeat(8) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

/** An item the database may store: a name and a price of 0 or more (a NaN or infinite price is not valid). */
internal fun isValidMenuItem(item: MenuItem): Boolean =
    item.name.isNotBlank() && !item.price.isNaN() && !item.price.isInfinite() && item.price >= 0.0

// ── the Add / Edit form ──

/** What the person typed in the Add / Edit form. [categoryKey] holds the stored key; the price is typed text. */
data class MenuForm(
    val name: String = "",
    val categoryKey: String = MenuCategory.BREAKFAST.key,
    val image: String = "",
    val description: String = "",
    val priceText: String = "0",
    val available: Boolean = true
)

/** A form that starts out holding an existing item (editing). */
internal fun menuFormOf(item: MenuItem): MenuForm = MenuForm(
    name = item.name,
    categoryKey = item.category,
    image = item.image,
    description = item.description,
    priceText = menuPriceText(item.price),
    available = item.available
)

/** 3500.0 -> "3500", 99.5 -> "99.5": the number as it is typed in the price box. */
internal fun menuPriceText(price: Double): String =
    if (price == Math.floor(price) && price < 1e15) price.toLong().toString() else price.toString()

/** The typed price. Blank means 0 (the default); text that is not a finite number, or is negative, is null. */
internal fun parseMenuPrice(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return 0.0
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps digits, at most one dot and a leading minus while the person types (a minus is kept so a negative price can be flagged). */
internal fun filterMenuPriceInput(text: String): String {
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

internal data class MenuFormErrors(val name: String? = null, val price: String? = null) {
    val any: Boolean get() = name != null || price != null
}

internal fun validateMenuForm(form: MenuForm): MenuFormErrors = MenuFormErrors(
    name = if (form.name.isBlank()) "Name is required." else null,
    price = if (parseMenuPrice(form.priceText) == null) "Enter a price of 0 or more." else null
)

/** Save is allowed only with a name and a price of 0 or more. */
internal fun canSaveMenuForm(form: MenuForm, saving: Boolean = false): Boolean = !saving && !validateMenuForm(form).any

/** The item a form describes, with the given [id] (the id of the item being edited, or a new one). */
internal fun menuItemFromForm(form: MenuForm, id: String): MenuItem = MenuItem(
    id = id,
    name = form.name.trim(),
    image = form.image.trim(),
    description = form.description.trim(),
    price = parseMenuPrice(form.priceText) ?: 0.0,
    category = (MenuCategory.fromKey(form.categoryKey) ?: MenuCategory.BREAKFAST).key,
    available = form.available
)
