package com.westly.nbms.features.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class BarRulesTest {

    private fun drink(
        id: String, name: String = "Drink $id", price: Double = 1000.0,
        category: DrinkCategory = DrinkCategory.BEER, available: Boolean = true
    ) = DrinkItem(id, name, "", "", price, category, available)

    // ── labels and mapping ──

    @Test fun categoryLabelsAreTheFriendlyOnes() {
        assertEquals("Soft Drinks", drinkCategoryLabel("soft_drinks"))
        assertEquals("Beer", drinkCategoryLabel("beer"))
        assertEquals("Mocktail", drinkCategoryLabel("mocktail"))
        assertEquals("Other", drinkCategoryLabel(null)); assertEquals("Other", drinkCategoryLabel("  "))
    }

    @Test fun paymentMethodKeysMapToTheStoredValues() {
        assertEquals(
            listOf("cash", "card", "bank_transfer", "pos", "room_charge"),
            BarPaymentMethod.entries.map { it.key }
        )
        assertEquals(BarPaymentMethod.ROOM_CHARGE, barPaymentMethodFromKey("room_charge"))
        assertEquals(BarPaymentMethod.POS, barPaymentMethodFromKey("pos"))
        assertNull(barPaymentMethodFromKey("bitcoin")); assertNull(barPaymentMethodFromKey(null))
        assertEquals("POS Terminal", barPaymentLabel("pos")); assertEquals("Charge to Room", barPaymentLabel("room_charge"))
        assertEquals("—", barPaymentLabel(null)); assertEquals("Voucher", barPaymentLabel("voucher"))
    }

    @Test fun theSaleFormStartsOnCash() {
        assertEquals(BarPaymentMethod.CASH, BarSaleForm().payment)
        assertEquals("cash", BarSaleForm().payment.key)
    }

    @Test fun theCategoryPillsAreAllThenTheSixCategories() {
        assertEquals(
            listOf("All", "Beer", "Wine", "Spirits", "Cocktails", "Soft Drinks", "Other"),
            barCategoryPills().map { it.second }
        )
        assertEquals("all", barCategoryPills().first().first)
    }

    // ── filters and counts ──

    private val menu = listOf(
        drink("a", category = DrinkCategory.BEER),
        drink("b", category = DrinkCategory.WINE, available = false),
        drink("c", category = DrinkCategory.BEER, available = false),
        drink("d", category = DrinkCategory.COCKTAILS)
    )

    @Test fun countsHaveAllFirstAndOneEntryPerCategory() {
        val counts = drinkCategoryCounts(menu)
        assertEquals(listOf("all", "beer", "wine", "spirits", "cocktails", "soft_drinks", "other"), counts.keys.toList())
        assertEquals(4, counts["all"]); assertEquals(2, counts["beer"]); assertEquals(1, counts["wine"])
        assertEquals(0, counts["spirits"]); assertEquals(1, counts["cocktails"])
    }

    @Test fun filteringByCategoryKeepsMenuOrder() {
        assertEquals(listOf("a", "c"), filterDrinks(menu, "beer").map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), filterDrinks(menu, "all").map { it.id })
        assertTrue(filterDrinks(menu, "spirits").isEmpty())
    }

    @Test fun newSaleShowsOnlyAvailableDrinks() {
        assertEquals(listOf("a", "d"), availableDrinksFor(menu, "all").map { it.id })
        assertEquals(listOf("a"), availableDrinksFor(menu, "beer").map { it.id })
        assertTrue(availableDrinksFor(menu, "wine").isEmpty())
    }

    @Test fun emptyMessagesNameTheCategory() {
        assertEquals("No drinks yet. Add your first one above.", drinksEmptyMessage("all"))
        assertEquals("No drinks in Soft Drinks yet. Add your first one above.", drinksEmptyMessage("soft_drinks"))
    }

    // ── reading the menu document ──

    @Test fun aMissingOrNonListDataFieldIsAnEmptyMenu() {
        assertTrue(parseDrinksDocument(null).isEmpty())
        assertTrue(parseDrinksDocument("nope").isEmpty())
        assertTrue(parseDrinksDocument(mapOf("id" to "x")).isEmpty())
    }

    @Test fun malformedEntriesAreSkippedAndTheRestLoad() {
        val items = parseDrinksDocument(
            listOf(
                "junk", 5, null,
                mapOf("name" to "No id"),
                mapOf("id" to "  "),
                mapOf("id" to "a", "name" to "Heineken 60cl", "price" to 1500, "category" to "beer", "available" to false, "image" to "u", "description" to "d")
            )
        )
        assertEquals(listOf(DrinkItem("a", "Heineken 60cl", "u", "d", 1500.0, DrinkCategory.BEER, false)), items)
    }

    @Test fun missingFieldsGetDefaults() {
        val item = parseDrinkEntry(mapOf("id" to "z"))!!
        assertEquals(DrinkItem("z", "", "", "", 0.0, DrinkCategory.OTHER, true), item)
    }

    @Test fun anUnknownCategoryIsOtherAndAPriceMayBeText() {
        val item = parseDrinkEntry(mapOf("id" to "z", "category" to "mocktail", "price" to "2,5"))!!
        assertEquals(DrinkCategory.OTHER, item.category); assertEquals(0.0, item.price, 0.0)
        assertEquals(2500.0, parseDrinkEntry(mapOf("id" to "z", "price" to "2500"))!!.price, 0.0)
        assertEquals(0.0, parseDrinkEntry(mapOf("id" to "z", "price" to Double.NaN))!!.price, 0.0)
    }

    // ── live-array edits ──

    private fun raw(id: String, name: String = "Drink $id", available: Boolean = true): Map<String, Any?> =
        mapOf("id" to id, "name" to name, "image" to "", "description" to "", "price" to 1000.0, "category" to "beer", "available" to available)

    @Test fun addAppendsToTheLiveArrayAndKeepsOtherEntriesUntouched() {
        val odd = mapOf("id" to "odd", "extra" to "field")
        val live = listOf<Any?>(raw("a"), odd, "junk")
        val next = applyDrinkChange(live, DrinkChange.Add(drink("n", name = "Star")))
        assertEquals(4, next.size)
        assertSame(live[0], next[0]); assertSame(odd, next[1]); assertEquals("junk", next[2])
        assertEquals(drinkToMap(drink("n", name = "Star")), next[3])
    }

    @Test fun addWithAnExistingIdReplacesInsteadOfDuplicating() {
        val next = applyDrinkChange(listOf(raw("a"), raw("b")), DrinkChange.Add(drink("a", name = "Again")))
        assertEquals(2, next.size)
        assertEquals("Again", (next[0] as Map<*, *>)["name"])
    }

    @Test fun updateReplacesOnlyThatEntry() {
        val b = raw("b")
        val next = applyDrinkChange(listOf(raw("a"), b), DrinkChange.Replace(drink("a", name = "Renamed", price = 2000.0, category = DrinkCategory.WINE)))
        assertEquals(
            mapOf("id" to "a", "name" to "Renamed", "image" to "", "description" to "", "price" to 2000.0, "category" to "wine", "available" to true),
            next[0]
        )
        assertSame(b, next[1])
    }

    @Test fun updateOfAMissingDrinkIsRefusedWithAClearMessage() {
        try { applyDrinkChange(listOf(raw("a")), DrinkChange.Replace(drink("zzz"))); fail("expected an error") } catch (e: DrinksMenuException) {
            assertEquals("This drink no longer exists. Someone may have deleted it.", e.message)
        }
    }

    @Test fun deleteRemovesOnlyThatEntryAndAMissingIdChangesNothing() {
        val live = listOf<Any?>(raw("a"), raw("b"))
        assertEquals(listOf(live[1]), applyDrinkChange(live, DrinkChange.Remove("a")))
        assertEquals(live, applyDrinkChange(live, DrinkChange.Remove("gone")))
    }

    @Test fun toggleChangesOnlyTheAvailableFlagAndKeepsUnknownFields() {
        val withExtra = raw("a") + ("sortOrder" to 3)
        val next = applyDrinkChange(listOf(withExtra, raw("b")), DrinkChange.SetAvailable("a", false))
        assertEquals(withExtra + ("available" to false), next[0])
        assertEquals(raw("b"), next[1])
        try { applyDrinkChange(listOf(raw("a")), DrinkChange.SetAvailable("nope", true)); fail("expected an error") } catch (e: DrinksMenuException) {
            assertEquals("This drink no longer exists. Someone may have deleted it.", e.message)
        }
    }

    @Test fun theChangeIsAppliedToTheLiveArrayNotToAnOldCopy() {
        val onScreen = listOf<Any?>(raw("a"))
        val liveNow = listOf<Any?>(raw("a"), raw("added-by-someone-else"))
        val fromScreen = applyDrinkChange(onScreen, DrinkChange.Add(drink("mine")))
        val fromLive = applyDrinkChange(liveNow, DrinkChange.Add(drink("mine")))
        assertEquals(2, fromScreen.size)
        assertEquals(listOf("a", "added-by-someone-else", "mine"), fromLive.map { (it as Map<*, *>)["id"] })
    }

    @Test fun thePayloadHoldsTheArrayAndTheServerTimeOnly() {
        val payload = buildDrinksPayload(listOf(raw("a")))
        assertEquals(setOf("data", "updatedAt"), payload.keys)
        assertEquals(listOf(raw("a")), payload["data"])
        assertSame(BarServerTime, payload["updatedAt"])
    }

    @Test fun newDrinkIdsAreEightLowercaseLettersAndDigits() {
        repeat(20) { assertTrue(Regex("[a-z0-9]{8}").matches(generateDrinkId(Random(it)))) }
        assertTrue(Regex("[a-z0-9]{8}").matches(generateDrinkId()))
    }

    @Test fun aDrinkNeedsANameAndANonNegativePrice() {
        assertTrue(isValidDrink(drink("a", price = 0.0)))
        assertFalse(isValidDrink(drink("a", name = "  ")))
        assertFalse(isValidDrink(drink("a", price = -1.0)))
        assertFalse(isValidDrink(drink("a", price = Double.NaN)))
    }

    // ── the drink form ──

    @Test fun aNewFormStartsAtBeerPriceZeroAvailable() {
        val f = DrinkFormState()
        assertEquals("beer", f.categoryKey); assertEquals("0", f.priceText); assertTrue(f.available)
    }

    @Test fun formValidationAndConversion() {
        assertTrue(validateDrinkForm(DrinkFormState(name = "")).name != null)
        assertEquals("Enter a price of 0 or more.", validateDrinkForm(DrinkFormState(name = "x", priceText = "-5")).price)
        assertFalse(canSaveDrinkForm(DrinkFormState(name = "x"), saving = true))
        assertTrue(canSaveDrinkForm(DrinkFormState(name = "x")))
        val d = drinkFromForm(DrinkFormState(name = " Star ", categoryKey = "spirits", priceText = "1500.5", available = false), "id1")
        assertEquals(DrinkItem("id1", "Star", "", "", 1500.5, DrinkCategory.SPIRITS, false), d)
        assertEquals(DrinkFormState("Star", "spirits", "", "", "1500.5", false), drinkFormOf(d))
        assertEquals("1500", drinkPriceText(1500.0))
        assertEquals(0.0, parseDrinkPrice("")!!, 0.0); assertNull(parseDrinkPrice("abc"))
        assertEquals("12.56", filterDrinkPriceInput("1a2.5.6"))
    }

    // ── cart ──

    @Test fun tappingTheSameDrinkTwiceMakesOneLineOfTwo() {
        val a = drink("a", price = 1500.0)
        val cart = addDrinkToCart(addDrinkToCart(emptyList(), a), a)
        assertEquals(listOf(BarCartLine("a", "Drink a", 1500.0, 2, false)), cart)
    }

    @Test fun plusAndMinusChangeTheQuantityAndMinusToZeroRemovesTheLine() {
        var cart = listOf(BarCartLine("a", "A", 100.0, 1), BarCartLine("b", "B", 50.0, 2))
        cart = incrementBarLine(cart, "a"); assertEquals(2, cart[0].quantity)
        cart = decrementBarLine(cart, "b"); assertEquals(1, cart[1].quantity)
        cart = decrementBarLine(cart, "b"); assertEquals(listOf("a"), cart.map { it.id })
        assertTrue(decrementBarLine(decrementBarLine(cart, "a"), "a").isEmpty())
    }

    @Test fun aMenuTapDoesNotMergeIntoAManualLineWithTheSameId() {
        val manual = BarCartLine("a", "Manual a", 10.0, 1, true)
        val cart = addDrinkToCart(listOf(manual), drink("a"))
        assertEquals(2, cart.size)
    }

    @Test fun lineSubtotalAndTotalAreRoundedToKobo() {
        assertEquals(4500.0, barLineSubtotal(BarCartLine("a", "A", 1500.0, 3)), 0.0)
        assertEquals(0.3, barCartTotal(listOf(BarCartLine("a", "A", 0.1, 1), BarCartLine("b", "B", 0.2, 1))), 0.0)
        assertEquals(0.0, barCartTotal(emptyList()), 0.0)
        assertEquals(6000.0, barCartTotal(listOf(BarCartLine("a", "A", 1500.0, 2), BarCartLine("m", "M", 3000.0, 1, true))), 0.0)
    }

    // ── manual entry ──

    @Test fun manualValidationUsesThePhase19Messages() {
        val e = validateBarManualItem("", "", "0")
        assertEquals("Enter the item name or description.", e.name)
        assertEquals("Enter a valid price greater than 0.", e.price)
        assertEquals("Quantity must be at least 1.", e.quantity)
        assertTrue(e.any)
        assertEquals("Enter a valid price greater than 0.", validateBarManualItem("x", "0", "1").price)
        assertEquals("Enter a valid price greater than 0.", validateBarManualItem("x", "-3", "1").price)
        assertEquals("Quantity must be at least 1.", validateBarManualItem("x", "5", "1.5").quantity)
        assertEquals("Quantity must be at least 1.", validateBarManualItem("x", "5", "").quantity)
        assertFalse(validateBarManualItem("Special cocktail", "3000", "1").any)
        assertEquals(3000.0, parseBarPrice(" 3000 ")!!, 0.0); assertEquals(2, parseBarQuantity("2"))
    }

    @Test fun inputFiltersKeepOnlyDigitsAndOneDot() {
        assertEquals("12.50", filterBarPriceInput("1x2.5.0"))
        assertEquals("123456", filterBarQuantityInput("12-34567a"))
    }

    @Test fun manualIdsLookLikeManualMillisRandomSix() {
        val line = manualBarLine(" Special cocktail ", 3000.0, 1, 1_700_000_000_123L, "abc123")
        assertEquals(BarCartLine("manual-1700000000123-abc123", "Special cocktail", 3000.0, 1, true), line)
        repeat(10) { assertTrue(Regex("manual-\\d+-[a-z0-9]{6}").matches(manualBarLine("x", 1.0, 1, 5L, barRandom6()).id)) }
    }

    // ── the bar_orders payload ──

    @Test fun thePayloadHasEveryFieldOf25() {
        val cart = listOf(BarCartLine("a", "Heineken 60cl", 1500.0, 2, false), BarCartLine("manual-1-abc123", "Special cocktail", 3000.0, 1, true))
        val form = BarSaleForm("201", "B-03", "Ada", "Ice", BarPaymentMethod.ROOM_CHARGE)
        val p = buildBarSalePayload(cart, form, "u1", "Wale")
        assertEquals(
            setOf(
                "barAttendantId", "barAttendantName", "customerName", "roomNumber", "tableNumber", "items", "total", "paymentMethod", "notes",
                "hasManualItems", "status", "approvalStatus", "approvedBy", "approvedByName", "approvedAt", "rejectedReason", "createdAt", "isDeleted"
            ),
            p.keys
        )
        assertEquals("u1", p["barAttendantId"]); assertEquals("Wale", p["barAttendantName"])
        assertEquals("Ada", p["customerName"]); assertEquals("201", p["roomNumber"]); assertEquals("B-03", p["tableNumber"]); assertEquals("Ice", p["notes"])
        assertEquals(6000.0, p["total"]); assertEquals("room_charge", p["paymentMethod"]); assertEquals(true, p["hasManualItems"])
        assertEquals("pending", p["status"]); assertEquals("pending", p["approvalStatus"])
        listOf("approvedBy", "approvedByName", "approvedAt", "rejectedReason").forEach { assertNull(it, p[it]); assertTrue(it, p.containsKey(it)) }
        assertSame(BarServerTime, p["createdAt"]); assertEquals(false, p["isDeleted"])
        assertEquals(
            listOf(
                mapOf("id" to "a", "name" to "Heineken 60cl", "price" to 1500.0, "quantity" to 2, "subtotal" to 3000.0, "isManual" to false),
                mapOf("id" to "manual-1-abc123", "name" to "Special cocktail", "price" to 3000.0, "quantity" to 1, "subtotal" to 3000.0, "isManual" to true)
            ),
            p["items"]
        )
    }

    @Test fun blankGuestRoomTableAndNotesBecomeNull() {
        val p = buildBarSalePayload(listOf(BarCartLine("a", "A", 100.0, 1)), BarSaleForm("  ", "", "   ", "\n"), "u1", "Wale")
        listOf("customerName", "roomNumber", "tableNumber", "notes").forEach { assertNull(it, p[it]); assertTrue(it, p.containsKey(it)) }
        assertEquals("cash", p["paymentMethod"]); assertEquals(false, p["hasManualItems"])
    }

    @Test fun typedTextIsTrimmedInThePayload() {
        val p = buildBarSalePayload(listOf(BarCartLine("a", "A", 100.0, 1)), BarSaleForm(" 201 ", " B-03 ", " Ada ", " Ice "), "u1", "Wale")
        assertEquals("201", p["roomNumber"]); assertEquals("B-03", p["tableNumber"]); assertEquals("Ada", p["customerName"]); assertEquals("Ice", p["notes"])
    }

    @Test fun everyPaymentMethodIsStoredUnderItsKey() {
        val expected = mapOf(
            BarPaymentMethod.CASH to "cash", BarPaymentMethod.CARD to "card", BarPaymentMethod.BANK_TRANSFER to "bank_transfer",
            BarPaymentMethod.POS to "pos", BarPaymentMethod.ROOM_CHARGE to "room_charge"
        )
        expected.forEach { (method, key) ->
            val p = buildBarSalePayload(listOf(BarCartLine("a", "A", 1.0, 1)), BarSaleForm(payment = method), "u", "n")
            assertEquals(key, p["paymentMethod"])
        }
        assertNotNull(barPaymentMethodFromKey("card"))
    }
}
