package com.westly.nbms.features.sales

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SalesRulesTest {

    // ── pricing ──
    @Test fun sellingPriceIsCostTimesOnePointThree() {
        assertEquals(260.0, sellingPrice(200.0), 0.0)
        assertEquals(195.0, sellingPrice(150.0), 0.0)
        assertEquals(0.0, sellingPrice(0.0), 0.0)
    }

    // ── cart ──
    private fun cartWith(i: com.westly.nbms.features.inventory.InventoryItem, qty: Int) =
        listOf(CartItem(i.id, i.name, sellingPrice(i.costPerUnit), qty, i.quantity))

    @Test fun addingNewItemCreatesLineOfOneAtSellingPrice() {
        val r = addCatalogItem(emptyList(), item()) as CartAdd.Added
        assertEquals(listOf(CartItem("i1", "Bottled Water", 260.0, 1, 23, false)), r.cart)
    }

    @Test fun tappingTwiceGivesQuantityTwoAndTotalFiveTwenty() {
        var cart = (addCatalogItem(emptyList(), item()) as CartAdd.Added).cart
        cart = (addCatalogItem(cart, item()) as CartAdd.Added).cart
        assertEquals(2, cart.single().quantity)
        assertEquals(520.0, cartTotal(cart), 0.0)
    }

    @Test fun stockLimitWhenCartAlreadyHoldsAllStock() {
        val i = item(quantity = 2)
        assertEquals(CartAdd.StockLimit, addCatalogItem(cartWith(i, 2), i))
    }

    @Test fun itemWithNoStockCannotBeAdded() {
        assertEquals(CartAdd.StockLimit, addCatalogItem(emptyList(), item(quantity = 0)))
    }

    @Test fun plusStopsAtAvailableButManualHasNoCeiling() {
        val cart = listOf(CartItem("i1", "A", 10.0, 3, 3), CartItem("manual-1-abc123", "M", 5.0, 50, UNLIMITED_STOCK, true))
        val after = incrementLine(cart, "i1")
        assertEquals(3, after[0].quantity)
        assertEquals(51, incrementLine(after, "manual-1-abc123")[1].quantity)
    }

    @Test fun minusReducesAndRemovesAtZero() {
        val cart = listOf(CartItem("i1", "A", 10.0, 2, 5))
        val once = decrementLine(cart, "i1")
        assertEquals(1, once.single().quantity)
        assertTrue(decrementLine(once, "i1").isEmpty())
    }

    @Test fun totalCombinesCatalogAndManualLines() {
        val cart = listOf(CartItem("i1", "Water", 260.0, 2, 23), CartItem("m", "Gift basket", 5000.0, 1, UNLIMITED_STOCK, true))
        assertEquals(5520.0, cartTotal(cart), 0.0)
    }

    // ── catalog ──
    @Test fun catalogHidesEmptyDeletedAndOtherCategories() {
        val items = listOf(
            item("a", category = "drinks"), item("b", category = "food", quantity = 0),
            item("c", category = "food"), item("d", category = "drinks", deleted = true)
        )
        assertEquals(listOf("a", "c"), catalogFor(items, ALL_CATEGORIES).map { it.id })
        assertEquals(listOf("c"), catalogFor(items, "food").map { it.id })
        assertEquals(listOf("all", "drinks", "food"), categoriesOf(items))
        assertEquals("Cleaning Supplies", salesWords("cleaning_supplies"))
    }

    // ── manual validation ──
    @Test fun manualMessagesAreExact() {
        val e = validateManualItem("  ", "0", "0")
        assertEquals("Enter the item name or description.", e.name)
        assertEquals("Enter a valid price greater than 0.", e.price)
        assertEquals("Quantity must be at least 1.", e.quantity)
        assertEquals("Enter a valid price greater than 0.", validateManualItem("x", "abc", "1").price)
        assertEquals("Quantity must be at least 1.", validateManualItem("x", "1", "2.5").quantity)
        assertEquals("Quantity must be at least 1.", validateManualItem("x", "1", "").quantity)
    }

    @Test fun validManualInputPasses() {
        assertFalse(validateManualItem("Gift basket", "5000", "1").any)
        assertEquals(5000.0, parseManualPrice(" 5000 ")!!, 0.0)
        assertNull(parseManualPrice("-3"))
        assertEquals(1, parseManualQuantity("1"))
    }

    @Test fun manualLineHasManualIdUnlimitedStockAndFlag() {
        val l = manualCartItem("  Gift basket ", 5000.0, 1, 1234L, "abc123")
        assertEquals("manual-1234-abc123", l.id)
        assertEquals("Gift basket", l.name)
        assertTrue(l.isManual)
        assertEquals(UNLIMITED_STOCK, l.available)
    }

    @Test fun inputFiltersKeepNumbersOnly() {
        assertEquals("12.50", filterPriceInput("1a2..5.0"))
        assertEquals("123", filterQuantityInput("1x2-3"))
    }

    // ── history ──
    private val lagos = ZoneId.of("Africa/Lagos")
    // 2026-10-15 12:00 UTC, 2026-09-30 23:30 UTC (= 2026-10-01 00:30 in Lagos), 2026-09-10
    private val oct = 1_792_065_600L
    private val sepEdge = 1_790_811_000L
    private val sep = 1_789_000_000L

    @Test fun filtersByMonthInBusinessTimezoneNewestFirst() {
        val rows = listOf(sale("a", seconds = sep), sale("b", seconds = oct), sale("c", seconds = sepEdge), sale("u"))
        assertEquals(listOf("b", "c"), filterSales(rows, "", "2026-10", lagos).map { it.id })
    }

    @Test fun blankMonthShowsAllNewestFirstUndatedLast() {
        val rows = listOf(sale("a", seconds = sep), sale("u"), sale("b", seconds = oct))
        assertEquals(listOf("b", "a", "u"), filterSales(rows, "", "", lagos).map { it.id })
    }

    @Test fun searchMatchesStaffOrCustomerCaseInsensitiveTrimmed() {
        val rows = listOf(sale("a", staff = "Sam Ade", seconds = oct), sale("b", staff = "Zed", customer = "SAMUEL", seconds = oct), sale("c", staff = "Zed", seconds = oct))
        assertEquals(listOf("a", "b"), filterSales(rows, "  sam ", "", lagos).map { it.id }.sorted())
    }

    @Test fun deletedSalesAreHiddenAndTotalsMatchRows() {
        val rows = filterSales(listOf(sale("a", total = 100.0, seconds = oct), sale("b", total = 50.5, seconds = oct, deleted = true)), "", "", lagos)
        assertEquals(1, rows.size)
        assertEquals("1 sales · ₦100 total", historySubtitle(rows, "₦"))
        assertEquals(100.0, salesTotal(rows), 0.0)
    }

    @Test fun rowTextHelpers() {
        assertEquals("Walk-in", customerText(sale("a")))
        assertEquals("Bank Transfer", paymentText("bank_transfer"))
        val lines = listOf(SaleLine("1", "A", 1.0, 2, 2.0), SaleLine("2", "B", 1.0, 1, 1.0), SaleLine("3", "C", 1.0, 1, 1.0))
        val (chips, more) = itemChips(lines)
        assertEquals(listOf("A ×2", "B ×1"), chips)
        assertEquals(1, more)
    }
}
