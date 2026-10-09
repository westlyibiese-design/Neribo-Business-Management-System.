package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class ExpenseRulesTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")      // UTC+1, no daylight saving
    private val october = YearMonth.of(2026, 10)

    private fun ts(iso: String) = Timestamp(Instant.parse(iso).epochSecond, 0)

    private fun expense(
        id: String,
        amount: Double,
        category: String = "other",
        date: String? = "2026-10-15T10:00:00Z",
        title: String = "Item $id",
        isDeleted: Boolean = false
    ) = Expense(id = id, title = title, amount = amount, category = category, date = date?.let { ts(it) }, isDeleted = isDeleted)

    private fun ids(list: List<Expense>) = list.map { it.id }

    // ---- inMonth ----

    @Test fun inMonth_keepsOnlyThatMonth() {
        val list = listOf(
            expense("a", 1.0, date = "2026-10-15T10:00:00Z"),
            expense("b", 1.0, date = "2026-09-15T10:00:00Z"),
            expense("c", 1.0, date = "2026-11-15T10:00:00Z")
        )
        assertEquals(listOf("a"), ids(ExpenseRules.inMonth(list, october, lagos)))
    }

    @Test fun inMonth_startOfMonthInBusinessZoneCountsEvenWhenUtcIsPreviousMonth() {
        // 30 Sep 23:30 UTC is 1 Oct 00:30 in Lagos
        val e = expense("a", 1.0, date = "2026-09-30T23:30:00Z")
        assertEquals(listOf("a"), ids(ExpenseRules.inMonth(listOf(e), october, lagos)))
        assertTrue(ExpenseRules.inMonth(listOf(e), october, ZoneId.of("UTC")).isEmpty())
    }

    @Test fun inMonth_endOfMonthInBusinessZoneRollsIntoNextMonth() {
        // 31 Oct 23:30 UTC is 1 Nov 00:30 in Lagos
        val e = expense("a", 1.0, date = "2026-10-31T23:30:00Z")
        assertTrue(ExpenseRules.inMonth(listOf(e), october, lagos).isEmpty())
        assertEquals(listOf("a"), ids(ExpenseRules.inMonth(listOf(e), YearMonth.of(2026, 11), lagos)))
    }

    @Test fun inMonth_lastSecondAndFirstSecondOfMonth() {
        val last = expense("last", 1.0, date = "2026-10-31T22:59:59Z")     // 31 Oct 23:59:59 Lagos
        val first = expense("first", 1.0, date = "2026-10-31T23:00:00Z")   // 1 Nov 00:00:00 Lagos
        assertEquals(listOf("last"), ids(ExpenseRules.inMonth(listOf(last, first), october, lagos)))
    }

    @Test fun inMonth_nullDateIsExcluded() {
        val list = listOf(expense("a", 1.0, date = null), expense("b", 1.0))
        assertEquals(listOf("b"), ids(ExpenseRules.inMonth(list, october, lagos)))
    }

    // ---- total ----

    @Test fun total_sumsAmounts() {
        assertEquals(0.0, ExpenseRules.total(emptyList()), 0.0001)
        assertEquals(3_250.5, ExpenseRules.total(listOf(expense("a", 1_000.0), expense("b", 2_250.5))), 0.0001)
    }

    // ---- byCategory ----

    @Test fun byCategory_sumsSortsDescendingAndOmitsZero() {
        val list = listOf(
            expense("a", 500.0, "utilities"),
            expense("b", 1_500.0, "payroll"),
            expense("c", 700.0, "utilities"),
            expense("d", 0.0, "marketing"),
            expense("e", 100.0, "supplies")
        )
        assertEquals(
            listOf(
                ExpenseCategory.PAYROLL to 1_500.0,
                ExpenseCategory.UTILITIES to 1_200.0,
                ExpenseCategory.SUPPLIES to 100.0
            ),
            ExpenseRules.byCategory(list)
        )
    }

    @Test fun byCategory_unknownOrBlankCategoryCountsAsOther() {
        val list = listOf(
            expense("a", 100.0, "other"),
            expense("b", 200.0, "something_new"),
            expense("c", 300.0, "")
        )
        assertEquals(listOf(ExpenseCategory.OTHER to 600.0), ExpenseRules.byCategory(list))
    }

    @Test fun byCategory_emptyList() {
        assertTrue(ExpenseRules.byCategory(emptyList()).isEmpty())
    }

    @Test fun byCategory_ties_followCategoryOrder() {
        val list = listOf(expense("a", 50.0, "payroll"), expense("b", 50.0, "utilities"))
        assertEquals(listOf(ExpenseCategory.UTILITIES, ExpenseCategory.PAYROLL), ExpenseRules.byCategory(list).map { it.first })
    }

    @Test fun categoryAndPaymentMethod_fromKey() {
        assertEquals(ExpenseCategory.FOOD_BEVERAGE, ExpenseCategory.fromKey("food_beverage"))
        assertEquals(ExpenseCategory.OTHER, ExpenseCategory.fromKey(null))
        assertEquals(ExpenseCategory.OTHER, ExpenseCategory.fromKey("nope"))
        assertEquals(ExpensePaymentMethod.BANK_TRANSFER, ExpensePaymentMethod.fromKey("bank_transfer"))
        assertEquals(ExpensePaymentMethod.CASH, ExpensePaymentMethod.fromKey(null))
        assertEquals(ExpensePaymentMethod.CASH, ExpensePaymentMethod.fromKey("crypto"))
    }

    // ---- filtered ----

    @Test fun filtered_dropsDeletedAndSortsNewestFirstWithNullDatesLast() {
        val list = listOf(
            expense("old", 1.0, date = "2026-10-02T10:00:00Z"),
            expense("none", 1.0, date = null),
            expense("new", 1.0, date = "2026-10-20T10:00:00Z"),
            expense("gone", 1.0, date = "2026-10-25T10:00:00Z", isDeleted = true)
        )
        assertEquals(listOf("new", "old", "none"), ids(ExpenseRules.filtered(list, null, "", lagos)))
    }

    @Test fun filtered_monthNullMeansEveryMonth_andMonthLimitsWhenSet() {
        val list = listOf(
            expense("oct", 1.0, date = "2026-10-10T10:00:00Z"),
            expense("sep", 1.0, date = "2026-09-10T10:00:00Z"),
            expense("none", 1.0, date = null)
        )
        assertEquals(listOf("oct", "sep", "none"), ids(ExpenseRules.filtered(list, null, "", lagos)))
        assertEquals(listOf("oct"), ids(ExpenseRules.filtered(list, october, "", lagos)))
    }

    @Test fun filtered_searchMatchesTitleOrRawCategoryKey_caseInsensitiveAndTrimmed() {
        val list = listOf(
            expense("a", 1.0, "utilities", title = "Electricity bill"),
            expense("b", 1.0, "supplies", title = "Printer paper"),
            expense("c", 1.0, "food_beverage", title = "Kitchen restock")
        )
        assertEquals(listOf("a"), ids(ExpenseRules.filtered(list, null, "  ELECTRIC ", lagos)))
        assertEquals(listOf("c"), ids(ExpenseRules.filtered(list, null, "food_bev", lagos)))
        assertEquals(listOf("a", "b", "c"), ids(ExpenseRules.filtered(list, null, "   ", lagos)).sorted())
        assertTrue(ExpenseRules.filtered(list, null, "zzz", lagos).isEmpty())
        // the label is not searched, only the raw key: "Food Beverage" has a space, the key has an underscore
        assertTrue(ExpenseRules.filtered(list, null, "food beverage", lagos).isEmpty())
    }

    @Test fun filtered_monthAndSearchTogether() {
        val list = listOf(
            expense("a", 1.0, "utilities", date = "2026-10-05T10:00:00Z", title = "Power"),
            expense("b", 1.0, "utilities", date = "2026-09-05T10:00:00Z", title = "Power"),
            expense("c", 1.0, "payroll", date = "2026-10-06T10:00:00Z", title = "Power staff")
        )
        assertEquals(listOf("c", "a"), ids(ExpenseRules.filtered(list, october, "power", lagos)))
        assertEquals(listOf("a"), ids(ExpenseRules.filtered(list, october, "utilities", lagos)))
    }

    @Test fun filtered_usesBusinessZoneForMonth() {
        val e = expense("a", 1.0, date = "2026-09-30T23:30:00Z")           // 1 Oct in Lagos
        assertEquals(listOf("a"), ids(ExpenseRules.filtered(listOf(e), october, "", lagos)))
        assertTrue(ExpenseRules.filtered(listOf(e), october, "", ZoneId.of("UTC")).isEmpty())
    }

    // ---- large expense threshold ----

    @Test fun isLarge_thresholdIsInclusiveAt1000() {
        assertEquals(1000.0, LARGE_EXPENSE_THRESHOLD, 0.0)
        assertFalse(ExpenseRules.isLarge(999.99))
        assertTrue(ExpenseRules.isLarge(1000.0))
        assertTrue(ExpenseRules.isLarge(25_000.0))
        assertFalse(ExpenseRules.isLarge(0.0))
    }
}
