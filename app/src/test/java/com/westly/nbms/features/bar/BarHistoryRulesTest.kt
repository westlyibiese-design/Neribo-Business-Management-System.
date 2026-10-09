package com.westly.nbms.features.bar

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BarHistoryRulesTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")
    private fun at(text: String): Instant = Instant.parse(text)
    private fun filters(month: String = "", search: String = "", status: String = "") = BarHistoryFilters(month, search, status)

    private val october = barHistorySaleOf("a", attendant = "Wale", customer = "Mr Ade", room = "204", at = at("2026-10-05T10:00:00Z"), status = BarSaleStatus.PENDING, total = 1000.0)
    private val september = barHistorySaleOf("b", attendant = "Chika", customer = null, table = "B-03", at = at("2026-09-28T10:00:00Z"), status = BarSaleStatus.SERVED, total = 2500.5)
    private val cancelled = barHistorySaleOf("c", attendant = "Wale", customer = "Mrs Bello", room = "310", at = at("2026-10-07T08:00:00Z"), status = BarSaleStatus.CANCELLED, total = 4000.0)
    private val all = listOf(september, october, cancelled)

    // ── month ──

    @Test fun monthFilterKeepsOnlyThatMonth() {
        assertEquals(listOf("c", "a"), filterBarHistory(all, filters(month = "2026-10"), lagos).map { it.id })
        assertEquals(listOf("b"), filterBarHistory(all, filters(month = "2026-09"), lagos).map { it.id })
    }

    @Test fun monthIsJudgedInTheBusinessTimeZone() {
        // 23:30 UTC on 30 September is 00:30 on 1 October in Lagos (UTC+1).
        val edge = barHistorySaleOf("edge", at = at("2026-09-30T23:30:00Z"))
        assertEquals(listOf("edge"), filterBarHistory(listOf(edge), filters(month = "2026-10"), lagos).map { it.id })
        assertTrue(filterBarHistory(listOf(edge), filters(month = "2026-09"), lagos).isEmpty())
    }

    @Test fun blankMonthShowsEveryMonthAndUndatedSalesOnlyThen() {
        val undated = barHistorySaleOf("u", at = null)
        assertEquals(4, filterBarHistory(all + undated, filters(), lagos).size)
        assertTrue(filterBarHistory(listOf(undated), filters(month = "2026-10"), lagos).isEmpty())
    }

    @Test fun defaultMonthIsTheCurrentMonthInTheBusinessZone() {
        assertEquals("2026-10", barHistoryCurrentMonth(lagos, at("2026-10-09T10:00:00Z")))
        assertEquals("2026-10", barHistoryCurrentMonth(lagos, at("2026-09-30T23:30:00Z")))
        assertEquals("2026-09", barHistoryCurrentMonth(ZoneId.of("UTC"), at("2026-09-30T23:30:00Z")))
    }

    @Test fun monthOptionsStartWithAllMonthsThenTheCurrentMonth() {
        val options = barHistoryMonthOptions(lagos, at("2026-10-09T10:00:00Z"))
        assertEquals("", options[0])
        assertEquals("2026-10", options[1])
        assertEquals("2026-09", options[2])
        assertEquals(25, options.size)
        assertEquals("October 2026", barHistoryMonthLabel("2026-10"))
    }

    // ── search ──

    @Test fun searchMatchesAttendantGuestAndRoom() {
        assertEquals(listOf("c", "a"), filterBarHistory(all, filters(search = "wale"), lagos).map { it.id })
        assertEquals(listOf("a"), filterBarHistory(all, filters(search = "ADE"), lagos).map { it.id })
        assertEquals(listOf("c"), filterBarHistory(all, filters(search = "310"), lagos).map { it.id })
        assertEquals(listOf("a"), filterBarHistory(all, filters(search = "  204 "), lagos).map { it.id })
    }

    @Test fun searchDoesNotLookAtTheTableNumber() {
        assertTrue(filterBarHistory(all, filters(search = "B-03"), lagos).isEmpty())
    }

    @Test fun blankSearchKeepsEverything() {
        assertEquals(3, filterBarHistory(all, filters(search = "   "), lagos).size)
    }

    // ── status ──

    @Test fun eachStatusFilterKeepsOnlyThatStatus() {
        assertEquals(listOf("a"), filterBarHistory(all, filters(status = "pending"), lagos).map { it.id })
        assertEquals(listOf("b"), filterBarHistory(all, filters(status = "served"), lagos).map { it.id })
        assertEquals(listOf("c"), filterBarHistory(all, filters(status = "cancelled"), lagos).map { it.id })
        assertEquals(3, filterBarHistory(all, filters(status = ""), lagos).size)
    }

    @Test fun statusOptionsAreAllStatusPendingServedCancelled() {
        assertEquals(listOf("All Status", "Pending", "Served", "Cancelled"), barHistoryStatusOptions().map { it.second })
        assertEquals(listOf("", "pending", "served", "cancelled"), barHistoryStatusOptions().map { it.first })
    }

    @Test fun filtersCombine() {
        assertEquals(listOf("c"), filterBarHistory(all, filters(month = "2026-10", search = "wale", status = "cancelled"), lagos).map { it.id })
        assertTrue(filterBarHistory(all, filters(month = "2026-09", search = "wale"), lagos).isEmpty())
    }

    @Test fun deletedSalesNeverShow() {
        val gone = barHistorySaleOf("gone", deleted = true)
        assertFalse(filterBarHistory(listOf(gone, october), filters(), lagos).any { it.id == "gone" })
    }

    @Test fun newestSaleComesFirstAndUndatedLast() {
        val undated = barHistorySaleOf("u", at = null)
        assertEquals(listOf("c", "a", "b", "u"), filterBarHistory(listOf(undated, september, october, cancelled), filters(), lagos).map { it.id })
    }

    // ── figures ──

    @Test fun subtitleCountsAndTotalsLeaveOutCancelledSales() {
        assertEquals(2, barHistoryCount(all))
        assertEquals(3500.5, barHistoryTotal(all), 0.0)
        assertEquals("2 sales · ₦3,500.50", barHistorySubtitle(all, "₦"))
    }

    @Test fun subtitleForOnlyCancelledSalesIsZero() {
        assertEquals("0 sales · ₦0", barHistorySubtitle(listOf(cancelled), "₦"))
        assertEquals("0 sales · ₦0", barHistorySubtitle(emptyList(), "₦"))
    }

    @Test fun totalIsRoundedToKobo() {
        val a = barHistorySaleOf("x", total = 0.1)
        val b = barHistorySaleOf("y", total = 0.2)
        assertEquals(0.3, barHistoryTotal(listOf(a, b)), 0.0)
    }

    // ── row text ──

    @Test fun guestIsTheNameOrWalkIn() {
        assertEquals("Mr Ade", barHistoryGuestText(october))
        assertEquals("Walk-in", barHistoryGuestText(september))
        assertEquals("Walk-in", barHistoryGuestText(barHistorySaleOf("z", customer = "   ")))
    }

    @Test fun locationIsRoomOrTableOrNothing() {
        assertEquals("Room 204", barHistoryLocationText(october))
        assertEquals("Table B-03", barHistoryLocationText(september))
        assertEquals("Room 5", barHistoryLocationText(barHistorySaleOf("z", room = "5", table = "T1")))
        assertNull(barHistoryLocationText(barHistorySaleOf("z")))
    }

    @Test fun upToTwoItemChipsThenPlusN() {
        val three = listOf(barHistoryLine("Heineken", 2), barHistoryLine("Guinness"), barHistoryLine("Fanta"))
        val (chips, more) = barHistoryChips(three)
        assertEquals(listOf("Heineken ×2", "Guinness ×1"), chips)
        assertEquals(1, more)
        assertEquals(0, barHistoryChips(three.take(2)).second)
        assertEquals(0, barHistoryChips(emptyList()).first.size)
    }

    // ── scope, permissions, update payload ──

    @Test fun onlyTheBarAttendantIsScopedToTheirOwnSales() {
        assertEquals("u9", barHistoryScopeFor(Role.BAR_ATTENDANT, "u9"))
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.OPERATIONS_MANAGER).forEach {
            assertNull(barHistoryScopeFor(it, "u9"))
        }
    }

    @Test fun markServedIsForSuperAdminBarAttendantAndManagerOnly() {
        assertTrue(canMarkBarSaleServed(Role.SUPER_ADMIN))
        assertTrue(canMarkBarSaleServed(Role.BAR_ATTENDANT))
        assertTrue(canMarkBarSaleServed(Role.MANAGER))
        assertFalse(canMarkBarSaleServed(Role.ACCOUNTANT))
        assertFalse(canMarkBarSaleServed(Role.OPERATIONS_MANAGER))
        assertFalse(canMarkBarSaleServed(null))
    }

    @Test fun markServedShowsOnlyOnPendingSales() {
        assertTrue(showMarkServed(Role.MANAGER, october))
        assertFalse(showMarkServed(Role.MANAGER, september))
        assertFalse(showMarkServed(Role.MANAGER, cancelled))
        assertFalse(showMarkServed(Role.ACCOUNTANT, october))
    }

    @Test fun addItemIsForSuperAdminAndManagerAndRestockForEveryoneOnThePage() {
        assertTrue(canAddBarStock(Role.SUPER_ADMIN))
        assertTrue(canAddBarStock(Role.MANAGER))
        assertFalse(canAddBarStock(Role.ACCOUNTANT))
        assertFalse(canAddBarStock(Role.BAR_ATTENDANT))
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.ACCOUNTANT, Role.BAR_ATTENDANT).forEach { assertTrue(canRestockBarStock(it)) }
        assertFalse(canRestockBarStock(Role.OPERATIONS_MANAGER))
        assertFalse(canRestockBarStock(Role.RECEPTIONIST))
    }

    @Test fun markServedPayloadIsStatusUpdatedAtAndUpdatedBy() {
        val fields = buildMarkServedFields("u7")
        assertEquals(setOf("status", "updatedAt", "updatedBy"), fields.keys)
        assertEquals("served", fields["status"])
        assertEquals("u7", fields["updatedBy"])
        assertSame(BarHistoryServerTime, fields["updatedAt"])
    }

    // ── view state ──

    @Test fun viewStatesFollowTheResource() {
        assertSame(BarHistoryView.Loading, barHistoryViewOf(Resource.Loading, filters(), lagos))
        assertEquals(BarHistoryView.Error("We couldn't load bar sales."), barHistoryViewOf(Resource.Error("x"), filters(), lagos))
        val ready = barHistoryViewOf(Resource.Success(all), filters(month = "2026-10"), lagos) as BarHistoryView.Ready
        assertEquals(listOf("c", "a"), ready.rows.map { it.id })
    }

    // ── drinks stock ──

    @Test fun drinksStockKeepsOnlyDrinksAndDropsDeletedOnes() {
        val items = listOf(
            barHistoryItemOf("1", "Wine"), barHistoryItemOf("2", "Soap", category = "toiletries"),
            barHistoryItemOf("3", "Old beer", deleted = true), barHistoryItemOf("4", "Beer")
        )
        assertEquals(listOf("4", "1"), drinksStockOf(items).map { it.id })
    }

    @Test fun lowStockUsesTheSharedPhase18Rule() {
        assertTrue(isBarStockLow(barHistoryItemOf("1", quantity = 5, minStock = 5)))
        assertTrue(isBarStockLow(barHistoryItemOf("1", quantity = 0, minStock = 0)))
        assertFalse(isBarStockLow(barHistoryItemOf("1", quantity = 6, minStock = 5)))
        assertEquals(listOf("low"), lowBarStock(listOf(barHistoryItemOf("ok", quantity = 9), barHistoryItemOf("low", quantity = 2))).map { it.id })
    }

    @Test fun stockSearchMatchesTheNameIgnoringCase() {
        val items = listOf(barHistoryItemOf("1", "Heineken 60cl"), barHistoryItemOf("2", "Star Lager"))
        assertEquals(listOf("1"), filterBarStock(items, " heinek ").map { it.id })
        assertEquals(2, filterBarStock(items, "").size)
        assertTrue(filterBarStock(items, "zzz").isEmpty())
    }

    @Test fun stockTexts() {
        assertEquals("3 drink stock items", barStockSubtitle(3))
        assertEquals("Low Stock Alert (2 items)", barStockLowHeading(2))
        assertEquals("Heineken 60cl: 3/5 bottles", barStockLowChip(barHistoryItemOf("1", quantity = 3, minStock = 5)))
    }

    @Test fun stockViewStatesFollowTheResource() {
        assertSame(BarStockView.Loading, barStockViewOf(Resource.Loading, ""))
        assertEquals(BarStockView.Error("We couldn't load bar inventory."), barStockViewOf(Resource.Error("x"), ""))
        val items = listOf(barHistoryItemOf("1", "Wine", quantity = 1, minStock = 5), barHistoryItemOf("2", "Beer", quantity = 20), barHistoryItemOf("3", "Soap", category = "food"))
        val ready = barStockViewOf(Resource.Success(items), "wine") as BarStockView.Ready
        assertEquals(listOf("2", "1"), ready.all.map { it.id })
        assertEquals(listOf("1"), ready.lowItems.map { it.id })
        assertEquals(listOf("1"), ready.rows.map { it.id })
    }

    // ── restock math ──

    @Test fun restockAddsTheAmount() {
        assertEquals(36, barRestockedQuantity(24, 12))
        assertEquals(1, barRestockedQuantity(0, 1))
    }

    @Test fun restockAmountMustBeAtLeastOne() {
        listOf(0, -3).forEach {
            val e = runCatching { barRestockedQuantity(5, it) }.exceptionOrNull()
            assertTrue(e is BarStockException)
            assertEquals("Enter a whole number of 1 or more.", e?.message)
        }
        assertNull(parseBarRestockAmount("0"))
        assertNull(parseBarRestockAmount(""))
        assertNull(parseBarRestockAmount("1.5"))
        assertNull(parseBarRestockAmount("abc"))
        assertEquals(12, parseBarRestockAmount(" 12 "))
    }

    @Test fun restockThatWouldOverflowIsRefused() {
        val e = runCatching { barRestockedQuantity(Int.MAX_VALUE, 1) }.exceptionOrNull()
        assertEquals("That quantity is too large.", e?.message)
    }

    @Test fun restockWritesQuantityAndUpdatedAtOnly() {
        val fields = buildBarRestockFields(36)
        assertEquals(setOf("quantity", "updatedAt"), fields.keys)
        assertEquals(36, fields["quantity"])
        assertSame(BarStockServerTime, fields["updatedAt"])
    }

    // ── add-item form and document ──

    @Test fun theFormStartsWithBottlesAsTheUnit() {
        assertEquals("bottles", BarStockForm().unit)
    }

    @Test fun addItemDocumentIsADrinksItemWithTheSameFieldsPhase18Writes() {
        val form = BarStockForm("  Heineken 60cl ", "24", " crates ", "850.5", "6")
        val doc = buildBarStockPayload(form)
        assertEquals(
            setOf("name", "category", "quantity", "minStock", "unit", "costPerUnit", "supplier", "isDeleted", "lastRestocked", "createdAt"),
            doc.keys
        )
        assertEquals("Heineken 60cl", doc["name"])
        assertEquals("drinks", doc["category"])
        assertEquals(24, doc["quantity"])
        assertEquals(6, doc["minStock"])
        assertEquals("crates", doc["unit"])
        assertEquals(850.5, doc["costPerUnit"])
        assertNull(doc["supplier"])
        assertEquals(false, doc["isDeleted"])
        assertSame(BarStockServerTime, doc["lastRestocked"])
        assertSame(BarStockServerTime, doc["createdAt"])
    }

    @Test fun blankUnitFallsBackToBottlesAndBlankCostToZero() {
        val doc = buildBarStockPayload(BarStockForm("Wine", "1", "  ", "", "0"))
        assertEquals("bottles", doc["unit"])
        assertEquals(0.0, doc["costPerUnit"])
    }

    @Test fun formChecks() {
        assertFalse(validateBarStockForm(BarStockForm("Wine", "0", "bottles", "", "0")).any)
        val bad = validateBarStockForm(BarStockForm("  ", "", "bottles", "-1", "x"))
        assertEquals("Item name is required.", bad.name)
        assertEquals("Enter a whole number of 0 or more.", bad.quantity)
        assertEquals("Enter an amount of 0 or more.", bad.cost)
        assertEquals("Enter a whole number of 0 or more.", bad.threshold)
        assertEquals("Item name is required.", bad.first)
    }

    @Test fun typingFiltersKeepOnlyNumbers() {
        assertEquals("123", filterBarWholeInput("1a2.3"))
        assertEquals(9, filterBarWholeInput("1234567890123").length)
        assertEquals("12.59", filterBarCostInput("1x2.5.9"))
        assertEquals(1000.0, parseBarCost("1000")!!, 0.0)
        assertNull(parseBarCost("1.2.3"))
    }
}
