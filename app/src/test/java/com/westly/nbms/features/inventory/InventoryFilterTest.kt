package com.westly.nbms.features.inventory

import com.westly.nbms.core.data.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryFilterTest {

    private fun ids(list: List<InventoryItem>) = list.map { it.id }

    private val sample = listOf(
        invItem("water", name = "Bottled Water", category = "drinks", quantity = 3, minStock = 5, unit = "bottles"),
        invItem("soap", name = "Hand Soap", category = "toiletries", quantity = 20, minStock = 5),
        invItem("cola", name = "Cola", category = "drinks", quantity = 8, minStock = 5),
        invItem("bleach", name = "Bleach", category = "cleaning_supplies", quantity = 5, minStock = 5)
    )

    // ── search ──

    @Test fun searchMatchesTheNameIgnoringCase() {
        assertEquals(listOf("water"), ids(filterInventory(sample, "WATER", "")))
        assertEquals(listOf("water"), ids(filterInventory(sample, "bottled", "")))
        assertEquals(listOf("soap"), ids(filterInventory(sample, "soap", "")))
    }

    @Test fun searchIgnoresSpacesAtTheEnds() {
        assertEquals(listOf("water"), ids(filterInventory(sample, "  water ", "")))
    }

    @Test fun searchOnlyLooksAtTheName() {
        // "drinks" is a category key, not part of any name.
        assertTrue(filterInventory(sample, "drinks", "").isEmpty())
    }

    @Test fun blankSearchAndAllCategoriesKeepEverythingInOrder() {
        assertEquals(listOf("water", "soap", "cola", "bleach"), ids(filterInventory(sample, "", "")))
        assertEquals(listOf("water", "soap", "cola", "bleach"), ids(filterInventory(sample, "   ", "")))
    }

    // ── category ──

    @Test fun categoryFilterKeepsOnlyThatCategory() {
        assertEquals(listOf("water", "cola"), ids(filterInventory(sample, "", "drinks")))
        assertEquals(listOf("bleach"), ids(filterInventory(sample, "", "cleaning_supplies")))
        assertTrue(filterInventory(sample, "", "food").isEmpty())
    }

    @Test fun searchAndCategoryWorkTogether() {
        assertEquals(listOf("water"), ids(filterInventory(sample, "water", "drinks")))
        assertTrue(filterInventory(sample, "water", "food").isEmpty())
    }

    @Test fun theSevenCategoryKeysAndLabelsAreWestlys() {
        assertEquals(
            listOf("drinks", "toiletries", "cleaning_supplies", "hotel_supplies", "food", "equipment", "other"),
            InventoryCategory.entries.map { it.key }
        )
        assertEquals(
            listOf("Drinks", "Toiletries", "Cleaning Supplies", "Hotel Supplies", "Food", "Equipment", "Other"),
            InventoryCategory.entries.map { it.label }
        )
        assertEquals(INVENTORY_CATEGORY_OPTIONS.map { it.key }, InventoryCategory.entries.map { it.key })
    }

    @Test fun categoryWordsHaveSpacesAndCapitals() {
        assertEquals("Cleaning Supplies", inventoryWords("cleaning_supplies"))
        assertEquals("Drinks", inventoryWords("drinks"))
        assertEquals("—", inventoryWords(""))
        assertEquals("—", inventoryWords(null))
    }

    // ── what the page shows ──

    @Test fun theSubtitleCountIsEveryNonDeletedItemBeforeTheFilters() {
        val view = inventoryViewOf(
            Resource.Success(sample + invItem("gone", deleted = true)),
            InventoryFilters(search = "water", categoryKey = "drinks")
        ) as InventoryView.Ready
        assertEquals(4, view.totalCount)
        assertEquals(listOf("water"), ids(view.rows))
    }

    @Test fun lowStockUsesEveryItemNotTheFilteredOnes() {
        val view = inventoryViewOf(Resource.Success(sample), InventoryFilters(search = "soap")) as InventoryView.Ready
        // Bottled Water (3/5) and Bleach (5/5, equal counts as low) are low even though the search shows only Soap.
        assertEquals(listOf("water", "bleach"), ids(view.lowItems))
        assertEquals(listOf("soap"), ids(view.rows))
    }

    @Test fun aDeletedItemNeverShowsOrCounts() {
        val view = inventoryViewOf(
            Resource.Success(listOf(invItem("a", quantity = 0), invItem("b", quantity = 0, deleted = true))),
            InventoryFilters()
        ) as InventoryView.Ready
        assertEquals(listOf("a"), ids(view.rows))
        assertEquals(listOf("a"), ids(view.lowItems))
        assertEquals(1, view.totalCount)
    }

    @Test fun noLowItemsMeansNoBanner() {
        val view = inventoryViewOf(Resource.Success(listOf(invItem("a", quantity = 10, minStock = 5))), InventoryFilters()) as InventoryView.Ready
        assertTrue(view.lowItems.isEmpty())
    }

    @Test fun anEmptyListIsReadyWithZeroItems() {
        val view = inventoryViewOf(Resource.Success(emptyList()), InventoryFilters()) as InventoryView.Ready
        assertEquals(0, view.totalCount)
        assertTrue(view.rows.isEmpty())
    }

    @Test fun loadingAndErrorStates() {
        assertEquals(InventoryView.Loading, inventoryViewOf(Resource.Loading, InventoryFilters()))
        assertEquals(
            InventoryView.Error("We couldn't load inventory."),
            inventoryViewOf(Resource.Error("permission denied"), InventoryFilters())
        )
    }

    @Test fun bannerTextsMatchTheSpec() {
        assertEquals("Low Stock Alert (1 items)", lowStockHeading(1))
        assertEquals("Low Stock Alert (3 items)", lowStockHeading(3))
        assertEquals("Bottled Water: 3/5 bottles", lowStockChipText(sample[0]))
    }

    @Test fun restockingAboveTheMinimumClearsTheAlert() {
        val before = inventoryViewOf(Resource.Success(listOf(invItem("w", quantity = 3, minStock = 5))), InventoryFilters()) as InventoryView.Ready
        val after = inventoryViewOf(Resource.Success(listOf(invItem("w", quantity = 23, minStock = 5))), InventoryFilters()) as InventoryView.Ready
        assertEquals(1, before.lowItems.size)
        assertTrue(after.lowItems.isEmpty())
    }
}
