package com.westly.nbms.features.restaurant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class RestaurantRulesTest {

    private fun item(
        id: String,
        name: String = "Item $id",
        category: String = "lunch",
        price: Double = 1000.0,
        available: Boolean = true,
        image: String = "",
        description: String = ""
    ) = MenuItem(id, name, image, description, price, category, available)

    private fun raw(vararg items: MenuItem): List<Any?> = items.map { menuItemToMap(it) }

    // ── categories ──

    @Test fun categoriesHaveWestlyKeysAndLabelsInOrder() {
        assertEquals(
            listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "drinks" to "Drinks", "desserts" to "Desserts"),
            MenuCategory.entries.map { it.key to it.label }
        )
    }

    @Test fun fromKeyFindsKnownKeysAndNothingElse() {
        assertEquals(MenuCategory.DRINKS, MenuCategory.fromKey("drinks"))
        assertNull(MenuCategory.fromKey("Drinks"))
        assertNull(MenuCategory.fromKey("brunch"))
        assertNull(MenuCategory.fromKey(null))
    }

    @Test fun categoryLabelFallsBackForUnknownKeys() {
        assertEquals("Dinner", menuCategoryLabel("dinner"))
        assertEquals("Brunch", menuCategoryLabel("brunch"))
        assertEquals("Other", menuCategoryLabel(""))
        assertEquals("Other", menuCategoryLabel(null))
    }

    // ── counts and filters ──

    private val sample = listOf(
        item("a", category = "breakfast"),
        item("b", category = "lunch"),
        item("c", category = "lunch", available = false),
        item("d", category = "drinks"),
        item("e", category = "mystery")
    )

    @Test fun countsHaveAllFirstThenEveryCategory() {
        val counts = menuCategoryCounts(sample)
        assertEquals(listOf("all", "breakfast", "lunch", "dinner", "drinks", "desserts"), counts.keys.toList())
        assertEquals(listOf(5, 1, 2, 0, 1, 0), counts.values.toList())
    }

    @Test fun anEmptyMenuCountsZeroEverywhere() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0), menuCategoryCounts(emptyList()).values.toList())
    }

    @Test fun allShowsEverythingIncludingUnavailableAndUnknownCategories() {
        assertEquals(listOf("a", "b", "c", "d", "e"), filterMenu(sample, "all").map { it.id })
    }

    @Test fun aCategoryChipKeepsOnlyThatCategoryInListOrderAndKeepsUnavailableItems() {
        assertEquals(listOf("b", "c"), filterMenu(sample, "lunch").map { it.id })
        assertEquals(emptyList<String>(), filterMenu(sample, "desserts").map { it.id })
    }

    @Test fun emptyMessageNamesTheCategory() {
        assertEquals("No menu items yet. Add your first one above.", menuEmptyMessage("all"))
        assertEquals("No menu items in Breakfast yet. Add your first one above.", menuEmptyMessage("breakfast"))
        assertEquals("No menu items in Desserts yet. Add your first one above.", menuEmptyMessage("desserts"))
    }

    // ── tolerant reading ──

    @Test fun aMissingOrNonListDataIsAnEmptyMenu() {
        assertEquals(emptyList<MenuItem>(), parseMenuDocument(null))
        assertEquals(emptyList<MenuItem>(), parseMenuDocument("oops"))
        assertEquals(emptyList<MenuItem>(), parseMenuDocument(mapOf("id" to "x")))
        assertEquals(emptyList<MenuItem>(), parseMenuDocument(42L))
        assertEquals(emptyList<MenuItem>(), parseMenuDocument(emptyList<Any?>()))
    }

    @Test fun aWellFormedEntryIsReadFieldForField() {
        val parsed = parseMenuDocument(
            listOf(
                mapOf(
                    "id" to "ab12cd34", "name" to "Jollof Rice", "image" to "https://x/y.jpg", "description" to "Smoky",
                    "price" to 3500L, "category" to "lunch", "available" to false
                )
            )
        )
        assertEquals(listOf(MenuItem("ab12cd34", "Jollof Rice", "https://x/y.jpg", "Smoky", 3500.0, "lunch", false)), parsed)
    }

    @Test fun missingFieldsGetTheNewItemDefaults() {
        val parsed = parseMenuEntry(mapOf("id" to "z1"))!!
        assertEquals(MenuItem(id = "z1", name = "", image = "", description = "", price = 0.0, category = "breakfast", available = true), parsed)
    }

    @Test fun badEntriesAreSkippedAndGoodOnesKept() {
        val parsed = parseMenuDocument(
            listOf(
                "text", null, 7, listOf("x"),
                mapOf("name" to "No id"),
                mapOf("id" to "  ", "name" to "Blank id"),
                mapOf("id" to "ok1", "name" to "Fine")
            )
        )
        assertEquals(listOf("ok1"), parsed.map { it.id })
    }

    @Test fun aPriceCanBeAnyNumberTypeOrNumericTextAndNothingElseBecomesZero() {
        assertEquals(12.5, parseMenuEntry(mapOf("id" to "a", "price" to 12.5))!!.price, 0.0)
        assertEquals(10.0, parseMenuEntry(mapOf("id" to "a", "price" to 10))!!.price, 0.0)
        assertEquals(99.0, parseMenuEntry(mapOf("id" to "a", "price" to " 99 "))!!.price, 0.0)
        assertEquals(0.0, parseMenuEntry(mapOf("id" to "a", "price" to "free"))!!.price, 0.0)
        assertEquals(0.0, parseMenuEntry(mapOf("id" to "a", "price" to Double.NaN))!!.price, 0.0)
        assertEquals(0.0, parseMenuEntry(mapOf("id" to "a", "price" to listOf(1)))!!.price, 0.0)
    }

    @Test fun wrongTypedFieldsFallBackInsteadOfCrashing() {
        val parsed = parseMenuEntry(mapOf("id" to "a", "name" to 5, "image" to true, "category" to 3, "available" to "yes"))!!
        assertEquals("", parsed.name)
        assertEquals("", parsed.image)
        assertEquals("breakfast", parsed.category)
        assertTrue(parsed.available)
    }

    // ── storing an item ──

    @Test fun anItemIsStoredWithExactlyWestlysSevenFields() {
        assertEquals(
            mapOf<String, Any?>(
                "id" to "ab12cd34", "name" to "Jollof Rice", "image" to "", "description" to "Smoky",
                "price" to 3500.0, "category" to "lunch", "available" to true
            ),
            menuItemToMap(MenuItem("ab12cd34", "Jollof Rice", "", "Smoky", 3500.0, "lunch", true))
        )
    }

    // ── applying one change to the LIVE array ──

    @Test fun addAppendsToTheEndOfTheLiveArray() {
        val live = raw(item("a"), item("b"))
        val next = applyMenuChange(live, MenuChange.Add(item("c", name = "New")))
        assertEquals(3, next.size)
        assertEquals(live, next.take(2))
        assertEquals(menuItemToMap(item("c", name = "New")), next[2])
    }

    @Test fun addToAnEmptyArrayStartsTheMenu() {
        assertEquals(listOf(menuItemToMap(item("a"))), applyMenuChange(emptyList(), MenuChange.Add(item("a"))))
    }

    @Test fun addingAnIdThatIsAlreadyThereReplacesItInsteadOfDuplicating() {
        val live = raw(item("a", name = "Old"), item("b"))
        val next = applyMenuChange(live, MenuChange.Add(item("a", name = "Again")))
        assertEquals(2, next.size)
        assertEquals("Again", (next[0] as Map<*, *>)["name"])
    }

    @Test fun replaceSwapsOnlyTheItemWithThatIdAndKeepsPosition() {
        val live = raw(item("a"), item("b", price = 100.0), item("c"))
        val next = applyMenuChange(live, MenuChange.Replace(item("b", name = "Edited", price = 250.0)))
        assertEquals(3, next.size)
        assertEquals(live[0], next[0])
        assertEquals(live[2], next[2])
        assertEquals("Edited", (next[1] as Map<*, *>)["name"])
        assertEquals(250.0, (next[1] as Map<*, *>)["price"])
    }

    @Test fun replacingAnItemSomeoneElseDeletedStopsWithAClearMessage() {
        val live = raw(item("a"))
        try {
            applyMenuChange(live, MenuChange.Replace(item("gone")))
            fail("expected MenuException")
        } catch (e: MenuException) {
            assertEquals(MSG_MENU_ITEM_MISSING, e.message)
        }
    }

    @Test fun removeDropsOnlyThatItem() {
        val live = raw(item("a"), item("b"), item("c"))
        assertEquals(live.filterIndexed { i, _ -> i != 1 }, applyMenuChange(live, MenuChange.Remove("b")))
    }

    @Test fun removingAnItemThatIsAlreadyGoneChangesNothing() {
        val live = raw(item("a"))
        assertEquals(live, applyMenuChange(live, MenuChange.Remove("gone")))
    }

    @Test fun setAvailableFlipsOnlyThatFlagAndKeepsTheRest() {
        val live = raw(item("a", name = "Soup", price = 800.0, description = "Hot"), item("b"))
        val next = applyMenuChange(live, MenuChange.SetAvailable("a", false))
        val a = next[0] as Map<*, *>
        assertEquals(false, a["available"])
        assertEquals("Soup", a["name"])
        assertEquals(800.0, a["price"])
        assertEquals("Hot", a["description"])
        assertEquals(live[1], next[1])
        // flipping back
        assertEquals(true, (applyMenuChange(next, MenuChange.SetAvailable("a", true))[0] as Map<*, *>)["available"])
    }

    @Test fun setAvailableOnAMissingItemStopsWithAClearMessage() {
        try {
            applyMenuChange(raw(item("a")), MenuChange.SetAvailable("gone", true))
            fail("expected MenuException")
        } catch (e: MenuException) {
            assertEquals(MSG_MENU_ITEM_MISSING, e.message)
        }
    }

    @Test fun entriesThisAppCannotReadSurviveEveryChangeUntouched() {
        val odd = mapOf("id" to "odd", "mystery" to "kept", "price" to "free")
        val junk = "not-a-map"
        val live = listOf<Any?>(menuItemToMap(item("a")), odd, junk, null)
        val added = applyMenuChange(live, MenuChange.Add(item("n")))
        assertSame(odd, added[1])
        assertSame(junk, added[2])
        assertNull(added[3])
        val removed = applyMenuChange(live, MenuChange.Remove("a"))
        assertEquals(listOf<Any?>(odd, junk, null), removed)
        val toggled = applyMenuChange(live, MenuChange.SetAvailable("odd", false))
        assertEquals("kept", (toggled[1] as Map<*, *>)["mystery"])
        assertEquals(false, (toggled[1] as Map<*, *>)["available"])
    }

    @Test fun anEditMadeFromAStaleScreenKeepsTheOtherDevicesChange() {
        // This phone's screen still shows [a, b]; meanwhile another device added "c" and removed "b".
        val staleScreen = listOf(item("a", price = 100.0), item("b"))
        val liveNow = raw(item("a", price = 100.0), item("c", name = "From other device"))
        val next = applyMenuChange(liveNow, MenuChange.Replace(staleScreen[0].copy(price = 500.0)))
        assertEquals(listOf("a", "c"), next.map { (it as Map<*, *>)["id"] })
        assertEquals(500.0, (next[0] as Map<*, *>)["price"])
        assertEquals("From other device", (next[1] as Map<*, *>)["name"])
    }

    @Test fun theLiveArrayItselfIsNeverModified() {
        val live = raw(item("a"), item("b"))
        val copy = live.toList()
        applyMenuChange(live, MenuChange.SetAvailable("a", false))
        applyMenuChange(live, MenuChange.Remove("b"))
        applyMenuChange(live, MenuChange.Add(item("c")))
        assertEquals(copy, live)
    }

    // ── the payload one save writes ──

    @Test fun thePayloadIsTheNewArrayPlusServerTimeAndNothingElse() {
        val array = raw(item("a"))
        val payload = buildMenuPayload(array)
        assertEquals(setOf("data", "updatedAt"), payload.keys)
        assertSame(array, payload["data"])
        assertSame(MenuServerTime, payload["updatedAt"])
    }

    // ── new ids ──

    @Test fun anIdIsEightLowercaseLettersOrDigits() {
        repeat(200) {
            val id = generateMenuItemId()
            assertEquals(8, id.length)
            assertTrue(id.all { it in 'a'..'z' || it in '0'..'9' })
        }
    }

    @Test fun idsFromTheSameSeedMatchAndDifferentSeedsDiffer() {
        assertEquals(generateMenuItemId(Random(7)), generateMenuItemId(Random(7)))
        assertFalse(generateMenuItemId(Random(7)) == generateMenuItemId(Random(8)))
    }

    // ── the form ──

    @Test fun aNewFormStartsAtWestlysDefaults() {
        val f = MenuForm()
        assertEquals("", f.name)
        assertEquals("breakfast", f.categoryKey)
        assertEquals("0", f.priceText)
        assertTrue(f.available)
    }

    @Test fun saveNeedsAName() {
        assertFalse(canSaveMenuForm(MenuForm(name = "")))
        assertFalse(canSaveMenuForm(MenuForm(name = "   ")))
        assertTrue(canSaveMenuForm(MenuForm(name = "Grilled Salmon")))
    }

    @Test fun aNegativePriceBlocksSaveButZeroAndBlankDoNot() {
        assertFalse(canSaveMenuForm(MenuForm(name = "X", priceText = "-1")))
        assertFalse(canSaveMenuForm(MenuForm(name = "X", priceText = "-0.5")))
        assertTrue(canSaveMenuForm(MenuForm(name = "X", priceText = "0")))
        assertTrue(canSaveMenuForm(MenuForm(name = "X", priceText = "")))
        assertTrue(canSaveMenuForm(MenuForm(name = "X", priceText = "3500.50")))
    }

    @Test fun textThatIsNotANumberBlocksSave() {
        assertFalse(canSaveMenuForm(MenuForm(name = "X", priceText = "abc")))
        assertFalse(canSaveMenuForm(MenuForm(name = "X", priceText = "1.2.3")))
        assertFalse(canSaveMenuForm(MenuForm(name = "X", priceText = "-")))
    }

    @Test fun saveIsBlockedWhileASaveIsRunning() {
        assertFalse(canSaveMenuForm(MenuForm(name = "X"), saving = true))
    }

    @Test fun errorsNameTheProblem() {
        val e = validateMenuForm(MenuForm(name = "", priceText = "-3"))
        assertEquals("Name is required.", e.name)
        assertEquals("Enter a price of 0 or more.", e.price)
        assertTrue(e.any)
        assertFalse(validateMenuForm(MenuForm(name = "Ok")).any)
    }

    @Test fun parsePriceHandlesBlankDecimalsAndBadText() {
        assertEquals(0.0, parseMenuPrice("")!!, 0.0)
        assertEquals(0.0, parseMenuPrice("  ")!!, 0.0)
        assertEquals(3500.0, parseMenuPrice("3500")!!, 0.0)
        assertEquals(99.5, parseMenuPrice(" 99.5 ")!!, 0.0)
        assertNull(parseMenuPrice("-1"))
        assertNull(parseMenuPrice("NaN"))
        assertNull(parseMenuPrice("Infinity"))
        assertNull(parseMenuPrice("12abc"))
    }

    @Test fun priceTypingKeepsDigitsOneDotAndALeadingMinus() {
        assertEquals("3500", filterMenuPriceInput("3,500"))
        assertEquals("12.5", filterMenuPriceInput("12.5"))
        assertEquals("12.55", filterMenuPriceInput("12.5.5"))
        assertEquals("-4", filterMenuPriceInput("-4"))
        assertEquals("4", filterMenuPriceInput("4-"))
        assertEquals("", filterMenuPriceInput("abc"))
    }

    @Test fun priceTextShowsWholeNumbersWithoutDecimals() {
        assertEquals("3500", menuPriceText(3500.0))
        assertEquals("99.5", menuPriceText(99.5))
        assertEquals("0", menuPriceText(0.0))
    }

    @Test fun editingStartsFromTheItemsOwnValues() {
        val form = menuFormOf(item("a", name = "Soup", category = "dinner", price = 800.0, available = false, image = "u", description = "Hot"))
        assertEquals(MenuForm("Soup", "dinner", "u", "Hot", "800", false), form)
    }

    @Test fun theItemFromAFormIsTrimmedAndCarriesTheGivenId() {
        val built = menuItemFromForm(
            MenuForm(name = "  Jollof Rice ", categoryKey = "lunch", image = " https://x/y.jpg ", description = " Smoky ", priceText = "3500", available = false),
            id = "ab12cd34"
        )
        assertEquals(MenuItem("ab12cd34", "Jollof Rice", "https://x/y.jpg", "Smoky", 3500.0, "lunch", false), built)
    }

    @Test fun anUnknownCategoryKeyFallsBackToBreakfast() {
        assertEquals("breakfast", menuItemFromForm(MenuForm(name = "X", categoryKey = "brunch"), "id").category)
    }

    @Test fun validItemsNeedANameAndAFiniteNonNegativePrice() {
        assertTrue(isValidMenuItem(item("a", price = 0.0)))
        assertFalse(isValidMenuItem(item("a", name = " ")))
        assertFalse(isValidMenuItem(item("a", price = -1.0)))
        assertFalse(isValidMenuItem(item("a", price = Double.NaN)))
        assertFalse(isValidMenuItem(item("a", price = Double.POSITIVE_INFINITY)))
    }
}
