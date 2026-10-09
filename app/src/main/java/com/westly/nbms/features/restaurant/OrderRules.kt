package com.westly.nbms.features.restaurant

import com.westly.nbms.core.rbac.Role
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// Pure rules for restaurant orders. Nothing here touches Android or the database, so the unit tests run them directly.

internal const val ORDER_FILTER_ALL = "all"

internal const val MSG_ORDER_MANUAL_NAME = "Enter the item name or description."
internal const val MSG_ORDER_MANUAL_PRICE = "Enter a valid price greater than 0."
internal const val MSG_ORDER_MANUAL_QUANTITY = "Quantity must be at least 1."
internal const val MSG_ORDERS_LOAD_FAILED = "We couldn't load orders."
internal const val MSG_ORDER_MENU_LOAD_FAILED = "We couldn't load the menu."
internal const val MSG_ORDER_EMPTY = "The order is empty."
internal const val MSG_ORDER_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_ORDER_TIMEOUT = "The order took too long to save. Check your connection and try again."
internal const val MSG_ORDER_GENERIC = "Something went wrong. Please try again."
internal const val MSG_ORDER_STATUS_NOT_ALLOWED = "This order can't be changed to that status."
internal const val MSG_ORDER_UPDATE_GENERIC = "The order could not be updated. Please try again."

/** Stands for the server time inside a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object OrderServerTime

/** Rounds to 2 decimals (kobo) so stored money never carries floating-point noise. */
internal fun orderMoney(value: Double): Double = Math.round(value * 100.0) / 100.0

// ── menu side of New Order ──

/** The category pills: "All" first, then the five menu categories in menu order. Pair = (filter key, label). */
internal fun orderCategoryPills(): List<Pair<String, String>> =
    listOf(ORDER_FILTER_ALL to "All") + MenuCategory.entries.map { it.key to it.label }

/** "Lunch" for a known key; an unknown key gets a capital first letter; blank is "Other". */
internal fun orderCategoryLabel(key: String?): String {
    MenuCategory.fromKey(key)?.let { return it.label }
    val clean = key?.trim().orEmpty()
    return if (clean.isEmpty()) "Other" else clean.replaceFirstChar { it.uppercase() }
}

/** The cards New Order shows: only AVAILABLE items, in the chosen category ("all" = every category). Menu order is kept. */
internal fun orderMenuFor(items: List<MenuItem>, categoryKey: String): List<MenuItem> =
    items.filter { it.available && (categoryKey == ORDER_FILTER_ALL || it.category == categoryKey) }

// ── cart ──

/** Tap on a menu card: quantity + 1 when the item is already in the order, else a new line of 1. There is no stock limit. */
internal fun addMenuItemToOrder(cart: List<OrderCartLine>, item: MenuItem): List<OrderCartLine> =
    if (cart.any { it.id == item.id && !it.isManual }) {
        cart.map { if (it.id == item.id && !it.isManual) it.copy(quantity = it.quantity + 1) else it }
    } else {
        cart + OrderCartLine(item.id, item.name, item.price, 1, false)
    }

/** The + button. */
internal fun incrementOrderLine(cart: List<OrderCartLine>, id: String): List<OrderCartLine> =
    cart.map { if (it.id == id) it.copy(quantity = it.quantity + 1) else it }

/** The − button: quantity − 1; reaching 0 removes the line. */
internal fun decrementOrderLine(cart: List<OrderCartLine>, id: String): List<OrderCartLine> =
    cart.mapNotNull { if (it.id != id) it else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1) }

internal fun orderLineTotal(line: OrderCartLine): Double = orderMoney(line.price * line.quantity)

internal fun orderTotal(cart: List<OrderCartLine>): Double = orderMoney(cart.sumOf { orderLineTotal(it) })

// ── manual entry ──

data class OrderManualErrors(val name: String? = null, val price: String? = null, val quantity: String? = null) {
    val any: Boolean get() = name != null || price != null || quantity != null
}

/** The typed price: a finite number above 0. Anything else is null. */
internal fun parseOrderPrice(text: String): Double? {
    val v = text.trim().toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite() || v <= 0.0) null else v
}

/** The typed quantity: a whole number of 1 or more. Anything else is null. */
internal fun parseOrderQuantity(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    val v = clean.toIntOrNull() ?: return null
    return if (v < 1) null else v
}

internal fun validateOrderManualItem(name: String, priceText: String, quantityText: String): OrderManualErrors = OrderManualErrors(
    name = if (name.isBlank()) MSG_ORDER_MANUAL_NAME else null,
    price = if (parseOrderPrice(priceText) == null) MSG_ORDER_MANUAL_PRICE else null,
    quantity = if (parseOrderQuantity(quantityText) == null) MSG_ORDER_MANUAL_QUANTITY else null
)

/** Keeps digits and the first dot while the person types a price. */
internal fun filterOrderPriceInput(text: String): String {
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

internal fun filterOrderQuantityInput(text: String): String = text.filter { it in '0'..'9' }.take(6)

/** A new manual line. Id: `manual-{millis}-{random6}`. */
internal fun manualOrderLine(name: String, price: Double, quantity: Int, millis: Long, random6: String): OrderCartLine =
    OrderCartLine("manual-$millis-$random6", name.trim(), price, quantity, true)

private const val ORDER_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

internal fun orderRandom6(random: java.util.Random = java.util.Random()): String =
    buildString { repeat(6) { append(ORDER_ID_CHARS[random.nextInt(ORDER_ID_CHARS.length)]) } }

// ── the new order document ──

private fun String.orNullIfBlank(): String? = trim().ifEmpty { null }

/**
 * The new `orders/{id}` document, exactly as 2.5 describes it: kitchen `status` and revenue `approvalStatus` both start as
 * "pending", the approval fields are null, and `createdAt` is the server time.
 */
internal fun buildOrderPayload(cart: List<OrderCartLine>, form: OrderForm, waiterId: String, waiterName: String): Map<String, Any?> = mapOf(
    "waiterId" to waiterId,
    "waiterName" to waiterName,
    "customerName" to form.guestName.orNullIfBlank(),
    "roomNumber" to form.roomNumber.orNullIfBlank(),
    "tableNumber" to form.tableNumber.orNullIfBlank(),
    "items" to cart.map {
        mapOf(
            "id" to it.id,
            "name" to it.name,
            "price" to it.price,
            "quantity" to it.quantity,
            "subtotal" to orderLineTotal(it),
            "isManual" to it.isManual
        )
    },
    "total" to orderTotal(cart),
    "paymentMethod" to form.payment.key,
    "notes" to form.notes.orNullIfBlank(),
    "hasManualItems" to cart.any { it.isManual },
    "status" to OrderStatus.PENDING.key,
    "approvalStatus" to "pending",
    "approvedBy" to null,
    "approvedByName" to null,
    "approvedAt" to null,
    "rejectedReason" to null,
    "createdAt" to OrderServerTime,
    "isDeleted" to false
)

// ── status handling ──

/** The statuses an order may move to next: pending → preparing or served; preparing → served; served and cancelled → none. */
internal fun allowedNextStatuses(current: String?): List<OrderStatus> = when (OrderStatus.fromKey(current)) {
    OrderStatus.PENDING -> listOf(OrderStatus.PREPARING, OrderStatus.SERVED)
    OrderStatus.PREPARING -> listOf(OrderStatus.SERVED)
    OrderStatus.SERVED, OrderStatus.CANCELLED, null -> emptyList()
}

internal fun canMoveOrder(from: String?, to: OrderStatus): Boolean = to in allowedNextStatuses(from)

/** Super Admin, Waiter and Manager change kitchen status; Accountant and Operations Manager only read. */
internal fun canChangeOrderStatus(role: Role): Boolean =
    role == Role.SUPER_ADMIN || role == Role.WAITER || role == Role.MANAGER

/** What one status change writes to `orders/{id}`: the status, the server time and who did it. */
internal fun buildOrderStatusUpdate(to: OrderStatus, uid: String): Map<String, Any?> = mapOf(
    "status" to to.key,
    "updatedAt" to OrderServerTime,
    "updatedBy" to uid
)

/** The toast text after a change: "Status → preparing". */
internal fun orderStatusToast(to: OrderStatus): String = "Status → ${to.key}"

// ── history ──

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun orderCurrentMonth(zone: ZoneId, now: Instant = Instant.now()): String = YearMonth.from(now.atZone(zone)).toString()

internal fun orderMonthLabel(month: String): String = try {
    val ym = YearMonth.parse(month)
    "${ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${ym.year}"
} catch (e: Exception) {
    month
}

internal fun orderZoneOf(timezone: String?): ZoneId = try {
    ZoneId.of(timezone ?: "Africa/Lagos")
} catch (e: Exception) {
    ZoneId.of("Africa/Lagos")
}

private fun orderInstant(o: Order): Instant? = o.createdAt?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

/**
 * The rows History shows: deleted removed; month "yyyy-MM" (blank = all months, judged in [zone]); status key ("all" = every
 * status); search over waiter name, guest name and room number (trimmed, case-insensitive); newest first, undated rows last.
 */
internal fun filterOrders(orders: List<Order>, search: String, statusKey: String, month: String, zone: ZoneId): List<Order> {
    val ym = month.trim().takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    val needle = search.trim()
    return orders
        .filter { !it.isDeleted }
        .filter { o -> ym == null || orderInstant(o)?.let { YearMonth.from(it.atZone(zone)) == ym } == true }
        .filter { o -> statusKey == ORDER_FILTER_ALL || o.status == statusKey }
        .filter { o ->
            needle.isEmpty() ||
                o.waiterName.contains(needle, ignoreCase = true) ||
                (o.customerName?.contains(needle, ignoreCase = true) == true) ||
                (o.roomNumber?.contains(needle, ignoreCase = true) == true)
        }
        .sortedByDescending { orderInstant(it)?.toEpochMilli() ?: Long.MIN_VALUE }
}

/** The money total of the rows; cancelled orders are not counted. */
internal fun ordersTotal(rows: List<Order>): Double =
    orderMoney(rows.filter { it.status != OrderStatus.CANCELLED.key }.sumOf { it.total })

/** "12 orders · ₦45,000" */
internal fun orderHistorySubtitle(rows: List<Order>, symbol: String): String =
    "${rows.size} orders · ${com.westly.nbms.core.util.Format.currency(ordersTotal(rows), symbol)}"

/** "pending" -> "Pending"; an unknown key gets a capital first letter; blank is "—". */
internal fun orderStatusText(key: String?): String {
    OrderStatus.fromKey(key)?.let { return it.label }
    val clean = key?.trim().orEmpty()
    return if (clean.isEmpty()) "—" else clean.replaceFirstChar { it.uppercase() }
}

/** The guest name, or "—" when none was given. */
internal fun orderGuestText(o: Order): String = o.customerName?.trim()?.takeIf { it.isNotEmpty() } ?: "—"

/** "Room 201" / "Table T-05" lines under the guest name (a blank number gives no line). */
internal fun orderPlaceLines(o: Order): List<String> = buildList {
    o.roomNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Room $it") }
    o.tableNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Table $it") }
}

/** The first two item chips ("Jollof Rice ×2") and how many more there are. */
internal fun orderItemChips(items: List<OrderLine>): Pair<List<String>, Int> =
    items.take(2).map { "${it.name} ×${it.quantity}" } to maxOf(0, items.size - 2)
