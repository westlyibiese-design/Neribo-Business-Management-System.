package com.westly.nbms.features.restaurant

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OrderRulesTest {

    private val lagos = ZoneId.of("Africa/Lagos")
    private fun secs(iso: String) = Instant.parse(iso).epochSecond

    private val jollof = MenuItem("m1", "Jollof Rice", "", "", 3500.0, "lunch", true)
    private val tea = MenuItem("m2", "Tea", "", "", 800.0, "drinks", true)
    private val sold = MenuItem("m3", "Pancakes", "", "", 2000.0, "breakfast", false)

    // ── menu side ──

    @Test fun categoryPillsAreAllThenTheFiveCategories() {
        assertEquals(
            listOf("all" to "All", "breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "drinks" to "Drinks", "desserts" to "Desserts"),
            orderCategoryPills()
        )
    }

    @Test fun menuShowsOnlyAvailableItemsOfTheChosenCategory() {
        val items = listOf(jollof, tea, sold)
        assertEquals(listOf(jollof, tea), orderMenuFor(items, "all"))
        assertEquals(listOf(jollof), orderMenuFor(items, "lunch"))
        assertTrue(orderMenuFor(items, "breakfast").isEmpty()) // the only breakfast item is unavailable
    }

    @Test fun categoryLabels() {
        assertEquals("Lunch", orderCategoryLabel("lunch"))
        assertEquals("Brunch", orderCategoryLabel("brunch"))
        assertEquals("Other", orderCategoryLabel(" "))
    }

    // ── cart ──

    @Test fun tappingTheSameItemTwiceGivesOneLineOfTwo() {
        var cart = addMenuItemToOrder(emptyList(), jollof)
        assertEquals(listOf(OrderCartLine("m1", "Jollof Rice", 3500.0, 1, false)), cart)
        cart = addMenuItemToOrder(cart, jollof)
        assertEquals(1, cart.size)
        assertEquals(2, cart.single().quantity)
        assertEquals(7000.0, orderTotal(cart), 0.0)
    }

    @Test fun differentItemsMakeDifferentLines() {
        val cart = addMenuItemToOrder(addMenuItemToOrder(emptyList(), jollof), tea)
        assertEquals(listOf("m1", "m2"), cart.map { it.id })
        assertEquals(4300.0, orderTotal(cart), 0.0)
    }

    @Test fun minusRemovesTheLineAtZeroAndPlusHasNoCeiling() {
        var cart = listOf(OrderCartLine("m1", "Jollof Rice", 3500.0, 2))
        cart = decrementOrderLine(cart, "m1")
        assertEquals(1, cart.single().quantity)
        cart = decrementOrderLine(cart, "m1")
        assertTrue(cart.isEmpty())
        cart = listOf(OrderCartLine("m1", "Jollof Rice", 3500.0, 500))
        assertEquals(501, incrementOrderLine(cart, "m1").single().quantity)
    }

    @Test fun totalsAreRoundedToKobo() {
        val cart = listOf(OrderCartLine("a", "A", 0.1, 3), OrderCartLine("b", "B", 0.2, 1))
        assertEquals(0.3, orderLineTotal(cart[0]), 0.0)
        assertEquals(0.5, orderTotal(cart), 0.0)
        assertEquals(0.0, orderTotal(emptyList()), 0.0)
    }

    // ── manual entry ──

    @Test fun manualValidationMessages() {
        val e = validateOrderManualItem("  ", "", "")
        assertEquals("Enter the item name or description.", e.name)
        assertEquals("Enter a valid price greater than 0.", e.price)
        assertEquals("Quantity must be at least 1.", e.quantity)
        assertTrue(e.any)
        assertEquals("Enter a valid price greater than 0.", validateOrderManualItem("Soup", "0", "1").price)
        assertEquals("Enter a valid price greater than 0.", validateOrderManualItem("Soup", "-5", "1").price)
        assertEquals("Quantity must be at least 1.", validateOrderManualItem("Soup", "10", "0").quantity)
        assertEquals("Quantity must be at least 1.", validateOrderManualItem("Soup", "10", "1.5").quantity)
        assertFalse(validateOrderManualItem("Soup", "1200", "1").any)
    }

    @Test fun manualParsing() {
        assertEquals(1200.5, parseOrderPrice(" 1200.5 ")!!, 0.0)
        assertNull(parseOrderPrice("abc")); assertNull(parseOrderPrice("NaN")); assertNull(parseOrderPrice("Infinity"))
        assertEquals(3, parseOrderQuantity(" 3 "))
        assertNull(parseOrderQuantity("")); assertNull(parseOrderQuantity("-1")); assertNull(parseOrderQuantity("99999999999"))
    }

    @Test fun inputFilters() {
        assertEquals("12.50", filterOrderPriceInput("1a2.5.0"))
        assertEquals("123456", filterOrderQuantityInput("12x3456789"))
    }

    @Test fun manualLineGetsAManualIdAndTheManualFlag() {
        val line = manualOrderLine("  Off-menu soup ", 1200.0, 2, 1760000000000L, "ab12cd")
        assertEquals("manual-1760000000000-ab12cd", line.id)
        assertEquals("Off-menu soup", line.name)
        assertTrue(line.isManual)
        assertEquals(2400.0, orderLineTotal(line), 0.0)
        assertTrue(Regex("[a-z0-9]{6}").matches(orderRandom6()))
    }

    // ── the order document ──

    @Test fun payloadHasEveryFieldOfTheOrderDocument() {
        val cart = listOf(
            OrderCartLine("m1", "Jollof Rice", 3500.0, 2, false),
            OrderCartLine("manual-1-abc123", "Off-menu soup", 1200.0, 1, true)
        )
        val form = OrderForm(roomNumber = " 201 ", tableNumber = "T-05", guestName = " Mrs Ade ", notes = " no pepper ", payment = OrderPaymentMethod.ROOM_CHARGE)
        val p = buildOrderPayload(cart, form, "u1", "Wale")

        assertEquals("u1", p["waiterId"]); assertEquals("Wale", p["waiterName"])
        assertEquals("Mrs Ade", p["customerName"]); assertEquals("201", p["roomNumber"]); assertEquals("T-05", p["tableNumber"])
        assertEquals(8200.0, p["total"]); assertEquals("room_charge", p["paymentMethod"]); assertEquals("no pepper", p["notes"])
        assertEquals(true, p["hasManualItems"])
        assertEquals("pending", p["status"]); assertEquals("pending", p["approvalStatus"])
        assertTrue(p.containsKey("approvedBy") && p["approvedBy"] == null)
        assertTrue(p.containsKey("approvedByName") && p["approvedByName"] == null)
        assertTrue(p.containsKey("approvedAt") && p["approvedAt"] == null)
        assertTrue(p.containsKey("rejectedReason") && p["rejectedReason"] == null)
        assertTrue(p["createdAt"] === OrderServerTime)
        assertEquals(false, p["isDeleted"])
        @Suppress("UNCHECKED_CAST") val items = p["items"] as List<Map<String, Any?>>
        assertEquals(mapOf("id" to "m1", "name" to "Jollof Rice", "price" to 3500.0, "quantity" to 2, "subtotal" to 7000.0, "isManual" to false), items[0])
        assertEquals(mapOf("id" to "manual-1-abc123", "name" to "Off-menu soup", "price" to 1200.0, "quantity" to 1, "subtotal" to 1200.0, "isManual" to true), items[1])
        assertEquals(
            setOf(
                "waiterId", "waiterName", "customerName", "roomNumber", "tableNumber", "items", "total", "paymentMethod", "notes",
                "hasManualItems", "status", "approvalStatus", "approvedBy", "approvedByName", "approvedAt", "rejectedReason", "createdAt", "isDeleted"
            ),
            p.keys
        )
    }

    @Test fun blankOptionalFieldsAreStoredAsNullAndMenuOnlyOrdersHaveNoManualItems() {
        val p = buildOrderPayload(listOf(OrderCartLine("m1", "Jollof Rice", 3500.0, 1)), OrderForm(roomNumber = "  ", notes = ""), "u1", "Wale")
        assertNull(p["customerName"]); assertNull(p["roomNumber"]); assertNull(p["tableNumber"]); assertNull(p["notes"])
        assertEquals("cash", p["paymentMethod"])
        assertEquals(false, p["hasManualItems"])
    }

    @Test fun paymentKeys() {
        assertEquals(listOf("cash", "card", "bank_transfer", "pos", "room_charge"), OrderPaymentMethod.entries.map { it.key })
        assertEquals(OrderPaymentMethod.POS, OrderPaymentMethod.fromKey("pos"))
        assertNull(OrderPaymentMethod.fromKey("cheque"))
    }

    // ── status transitions ──

    @Test fun allowedStatusTransitions() {
        assertEquals(listOf(OrderStatus.PREPARING, OrderStatus.SERVED), allowedNextStatuses("pending"))
        assertEquals(listOf(OrderStatus.SERVED), allowedNextStatuses("preparing"))
        assertTrue(allowedNextStatuses("served").isEmpty())
        assertTrue(allowedNextStatuses("cancelled").isEmpty())
        assertTrue(allowedNextStatuses("something-else").isEmpty())
        assertTrue(allowedNextStatuses(null).isEmpty())
        assertTrue(canMoveOrder("pending", OrderStatus.PREPARING))
        assertTrue(canMoveOrder("pending", OrderStatus.SERVED))
        assertTrue(canMoveOrder("preparing", OrderStatus.SERVED))
        assertFalse(canMoveOrder("preparing", OrderStatus.PREPARING))
        assertFalse(canMoveOrder("preparing", OrderStatus.PENDING))
        assertFalse(canMoveOrder("served", OrderStatus.PREPARING))
        assertFalse(canMoveOrder("pending", OrderStatus.CANCELLED))
    }

    @Test fun onlySuperAdminWaiterAndManagerChangeStatus() {
        assertTrue(canChangeOrderStatus(Role.SUPER_ADMIN))
        assertTrue(canChangeOrderStatus(Role.WAITER))
        assertTrue(canChangeOrderStatus(Role.MANAGER))
        assertFalse(canChangeOrderStatus(Role.ACCOUNTANT))
        assertFalse(canChangeOrderStatus(Role.OPERATIONS_MANAGER))
        assertFalse(canChangeOrderStatus(Role.RECEPTIONIST))
    }

    @Test fun statusUpdateWritesStatusServerTimeAndWho() {
        val u = buildOrderStatusUpdate(OrderStatus.PREPARING, "u1")
        assertEquals(setOf("status", "updatedAt", "updatedBy"), u.keys)
        assertEquals("preparing", u["status"]); assertEquals("u1", u["updatedBy"]); assertTrue(u["updatedAt"] === OrderServerTime)
        assertEquals("Status → served", orderStatusToast(OrderStatus.SERVED))
    }

    // ── history ──

    private val oct = secs("2026-10-15T12:00:00Z")
    private val sep = secs("2026-09-15T12:00:00Z")

    @Test fun historyDropsDeletedAndSortsNewestFirstWithUndatedLast() {
        val rows = filterOrders(
            listOf(
                testOrder("old", seconds = secs("2026-10-01T09:00:00Z")),
                testOrder("undated", seconds = null),
                testOrder("gone", seconds = oct, deleted = true),
                testOrder("new", seconds = oct)
            ),
            "", "all", "", lagos
        )
        assertEquals(listOf("new", "old", "undated"), rows.map { it.id })
    }

    @Test fun historyMonthFilterUsesTheBusinessTimezone() {
        // 23:30 UTC on 30 Sep is 00:30 on 1 Oct in Lagos (UTC+1).
        val edge = testOrder("edge", seconds = secs("2026-09-30T23:30:00Z"))
        val octRow = testOrder("oct", seconds = oct)
        val sepRow = testOrder("sep", seconds = sep)
        val all = listOf(edge, octRow, sepRow)
        assertEquals(setOf("edge", "oct"), filterOrders(all, "", "all", "2026-10", lagos).map { it.id }.toSet())
        assertEquals(listOf("sep"), filterOrders(all, "", "all", "2026-09", lagos).map { it.id })
        assertEquals(3, filterOrders(all, "", "all", "", lagos).size) // cleared month = every month
    }

    @Test fun historySearchMatchesWaiterGuestAndRoom() {
        val all = listOf(
            testOrder("a", waiter = "Wale", customer = "Mrs Ade", room = "201", seconds = oct),
            testOrder("b", waiter = "Bisi", customer = null, room = "305", seconds = oct),
            testOrder("c", waiter = "Tunde", customer = "Mr Obi", room = null, seconds = oct)
        )
        assertEquals(listOf("a"), filterOrders(all, " ade ", "all", "", lagos).map { it.id })
        assertEquals(listOf("b"), filterOrders(all, "BISI", "all", "", lagos).map { it.id })
        assertEquals(listOf("b"), filterOrders(all, "305", "all", "", lagos).map { it.id })
        assertEquals(listOf("c"), filterOrders(all, "obi", "all", "", lagos).map { it.id })
        assertEquals(3, filterOrders(all, "   ", "all", "", lagos).size)
    }

    @Test fun historyStatusFilter() {
        val all = listOf(
            testOrder("p", status = "pending", seconds = oct), testOrder("s", status = "served", seconds = oct),
            testOrder("c", status = "cancelled", seconds = oct)
        )
        assertEquals(listOf("s"), filterOrders(all, "", "served", "", lagos).map { it.id })
        assertEquals(3, filterOrders(all, "", "all", "", lagos).size)
    }

    @Test fun cancelledOrdersAreListedButNotCounted() {
        val rows = filterOrders(
            listOf(
                testOrder("a", total = 7000.0, seconds = oct), testOrder("b", total = 1200.0, status = "served", seconds = oct),
                testOrder("c", total = 999.0, status = "cancelled", seconds = oct)
            ),
            "", "all", "", lagos
        )
        assertEquals(3, rows.size)
        assertEquals(8200.0, ordersTotal(rows), 0.0)
        assertEquals("3 orders · ₦8,200", orderHistorySubtitle(rows, "₦"))
        assertEquals("0 orders · ₦0", orderHistorySubtitle(emptyList(), "₦"))
    }

    @Test fun rowTextHelpers() {
        assertEquals("Pending", orderStatusText("pending")); assertEquals("Served", orderStatusText("served"))
        assertEquals("Odd", orderStatusText("odd")); assertEquals("—", orderStatusText(null))
        assertEquals("—", orderGuestText(testOrder("x"))); assertEquals("Mrs Ade", orderGuestText(testOrder("x", customer = " Mrs Ade ")))
        assertEquals(listOf("Room 201", "Table T-05"), orderPlaceLines(testOrder("x", room = "201", table = "T-05")))
        assertTrue(orderPlaceLines(testOrder("x", room = " ")).isEmpty())
        val items = listOf(OrderLine("a", "Jollof Rice", 1.0, 2), OrderLine("b", "Tea", 1.0, 1), OrderLine("c", "Cake", 1.0, 1))
        assertEquals(listOf("Jollof Rice ×2", "Tea ×1") to 1, orderItemChips(items))
        assertEquals(emptyList<String>() to 0, orderItemChips(emptyList()))
    }

    @Test fun orderStatusKeys() {
        assertEquals(listOf("pending", "preparing", "served", "cancelled"), OrderStatus.entries.map { it.key })
        assertNotNull(OrderStatus.fromKey("served")); assertNull(OrderStatus.fromKey("nope"))
        assertEquals("2026-10", orderCurrentMonth(lagos, Instant.parse("2026-10-15T12:00:00Z")))
        assertEquals("October 2026", orderMonthLabel("2026-10"))
    }
}
