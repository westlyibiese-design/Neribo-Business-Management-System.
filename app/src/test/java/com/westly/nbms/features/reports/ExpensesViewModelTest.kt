package com.westly.nbms.features.reports

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModelTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")
    private val october = ExpensesFilters(month = "2026-10", search = "")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ready(list: List<ExpenseDoc>, filters: ExpensesFilters = october): ExpensesView.Ready =
        expensesViewOf(Resource.Success(list), filters, lagos) as ExpensesView.Ready

    private fun ids(view: ExpensesView.Ready) = view.rows.map { it.id }

    // ── month filter ──

    @Test fun monthFilterKeepsOnlyThatMonthInTheBusinessZone() {
        val list = listOf(
            expenseDoc("oct", date = "2026-10-07T10:00:00Z"),
            expenseDoc("sep", date = "2026-09-15T10:00:00Z"),
            // 23:30 UTC on 30 Sep is 00:30 on 1 Oct in Lagos, so it belongs to October.
            expenseDoc("edge", date = "2026-09-30T23:30:00Z")
        )
        assertEquals(listOf("oct", "edge"), ids(ready(list)))
        assertEquals(listOf("sep"), ids(ready(list, ExpensesFilters("2026-09", ""))))
    }

    @Test fun clearingTheMonthShowsEveryMonthNewestFirst() {
        val list = listOf(
            expenseDoc("sep", date = "2026-09-15T10:00:00Z"),
            expenseDoc("oct", date = "2026-10-07T10:00:00Z"),
            expenseDoc("aug", date = "2026-08-01T10:00:00Z")
        )
        assertEquals(listOf("oct", "sep", "aug"), ids(ready(list, ExpensesFilters("", ""))))
        assertEquals(listOf("oct", "sep", "aug"), ids(ready(list, ExpensesFilters("  ", ""))))
    }

    @Test fun deletedExpensesNeverShow() {
        val list = listOf(expenseDoc("a"), expenseDoc("b", deleted = true))
        assertEquals(listOf("a"), ids(ready(list)))
    }

    // ── search ──

    @Test fun searchMatchesTheTitleOrTheCategoryKeyIgnoringCase() {
        val list = listOf(
            expenseDoc("a", title = "Electricity bill", category = "utilities"),
            expenseDoc("b", title = "Soap", category = "supplies"),
            expenseDoc("c", title = "Kitchen restock", category = "food_beverage")
        )
        assertEquals(listOf("a"), ids(ready(list, ExpensesFilters("2026-10", "ELECTRIC"))))
        assertEquals(listOf("b"), ids(ready(list, ExpensesFilters("2026-10", "soap"))))
        assertEquals(listOf("c"), ids(ready(list, ExpensesFilters("2026-10", "food_bev"))))
        assertTrue(ready(list, ExpensesFilters("2026-10", "zzz")).rows.isEmpty())
    }

    // ── total (subtitle) over the filtered rows only ──

    @Test fun theTotalIsTheSumOfTheFilteredRowsOnly() {
        val list = listOf(
            expenseDoc("elec", title = "Electricity", amount = 25_000.0, category = "utilities"),
            expenseDoc("soap", title = "Soap", amount = 500.0, category = "supplies"),
            expenseDoc("old", title = "Old", amount = 9_000.0, date = "2026-08-01T10:00:00Z"),
            expenseDoc("gone", title = "Deleted", amount = 7_000.0, deleted = true)
        )
        assertEquals(25_500.0, ready(list).total, 0.0001)
        assertEquals(500.0, ready(list, ExpensesFilters("2026-10", "soap")).total, 0.0001)
        assertEquals(34_500.0, ready(list, ExpensesFilters("", "")).total, 0.0001)
        assertEquals(0.0, ready(list, ExpensesFilters("2026-10", "zzz")).total, 0.0001)
    }

    // ── top-four category cards ──

    @Test fun cardsAreTheTopFourCategoriesBiggestFirst() {
        val list = listOf(
            expenseDoc("a", amount = 100.0, category = "utilities"),
            expenseDoc("b", amount = 5_000.0, category = "payroll"),
            expenseDoc("c", amount = 300.0, category = "utilities"),
            expenseDoc("d", amount = 2_000.0, category = "maintenance"),
            expenseDoc("e", amount = 50.0, category = "supplies"),
            expenseDoc("f", amount = 700.0, category = "marketing")
        )
        assertEquals(
            listOf(
                ExpenseCategoryCard(ExpenseCategory.PAYROLL, 5_000.0),
                ExpenseCategoryCard(ExpenseCategory.MAINTENANCE, 2_000.0),
                ExpenseCategoryCard(ExpenseCategory.MARKETING, 700.0),
                ExpenseCategoryCard(ExpenseCategory.UTILITIES, 400.0)
            ),
            ready(list).cards
        )
    }

    @Test fun cardsOnlyCountTheFilteredRowsAndUnknownCategoriesAreOther() {
        val list = listOf(
            expenseDoc("a", title = "Soap", amount = 500.0, category = "supplies"),
            expenseDoc("b", title = "Mystery", amount = 800.0, category = "weird_key"),
            expenseDoc("c", title = "Soap old", amount = 4_000.0, category = "supplies", date = "2026-08-01T10:00:00Z")
        )
        assertEquals(
            listOf(ExpenseCategoryCard(ExpenseCategory.OTHER, 800.0), ExpenseCategoryCard(ExpenseCategory.SUPPLIES, 500.0)),
            ready(list).cards
        )
        assertEquals(
            listOf(ExpenseCategoryCard(ExpenseCategory.SUPPLIES, 500.0)),
            ready(list, ExpensesFilters("2026-10", "soap")).cards
        )
    }

    @Test fun noRowsMeansNoCards() {
        assertTrue(ready(emptyList()).cards.isEmpty())
    }

    // ── states ──

    @Test fun loadingAndErrorStates() {
        assertEquals(ExpensesView.Loading, expensesViewOf(Resource.Loading, october, lagos))
        assertEquals(
            ExpensesView.Error("We couldn't load expenses."),
            expensesViewOf(Resource.Error("boom"), october, lagos)
        )
    }

    // ── small helpers ──

    @Test fun wordsCapitaliseAndDropUnderscores() {
        assertEquals("Food Beverage", expensesWords("food_beverage"))
        assertEquals("Bank Transfer", expensesWords("bank_transfer"))
        assertEquals("Cash", expensesWords("CASH"))
        assertEquals("—", expensesWords(""))
        assertEquals("—", expensesWords(null))
    }

    @Test fun monthHelpers() {
        assertEquals("2026-10", expensesCurrentMonth(lagos, Instant.parse("2026-10-09T12:00:00Z")))
        // 23:30 UTC on 31 Oct is already November in Lagos
        assertEquals("2026-11", expensesCurrentMonth(lagos, Instant.parse("2026-10-31T23:30:00Z")))
        assertEquals("October 2026", expensesMonthLabel("2026-10"))
        assertEquals("nope", expensesMonthLabel("nope"))
        assertEquals(ZoneId.of("Africa/Lagos"), expensesZoneOf("Not/AZone"))
        assertEquals(ZoneId.of("Europe/London"), expensesZoneOf("Europe/London"))
    }

    // ── through the view model ──

    @Test fun theViewModelShowsLiveDataAndAppliesTheFilters() = runTest {
        val firestore = ExpenseFakeFirestore()
        firestore.docs.value = Resource.Success(
            listOf(
                expenseDoc("elec", title = "Electricity", amount = 25_000.0, category = "utilities"),
                expenseDoc("soap", title = "Soap", amount = 500.0, category = "supplies")
            )
        )
        val vm = ExpensesViewModel(firestore, ExpenseFakeAudit(), ExpenseFakeNotifier(), ExpenseFakeSession(), ToastController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
        vm.setMonth("2026-10")

        var current = vm.view.value as ExpensesView.Ready
        assertEquals(listOf("elec", "soap").sorted(), current.rows.map { it.id }.sorted())
        assertEquals(25_500.0, current.total, 0.0001)

        vm.setSearch("soap")
        current = vm.view.value as ExpensesView.Ready
        assertEquals(listOf("soap"), current.rows.map { it.id })
        assertEquals(500.0, current.total, 0.0001)

        // a new expense arrives live
        firestore.docs.value = Resource.Success(
            listOf(expenseDoc("soap", title = "Soap", amount = 500.0), expenseDoc("soap2", title = "Soap again", amount = 250.0))
        )
        current = vm.view.value as ExpensesView.Ready
        assertEquals(750.0, current.total, 0.0001)
    }

    @Test fun aLoadErrorShowsTheMessage() = runTest {
        val firestore = ExpenseFakeFirestore()
        firestore.docs.value = Resource.Error("denied")
        val vm = ExpensesViewModel(firestore, ExpenseFakeAudit(), ExpenseFakeNotifier(), ExpenseFakeSession(), ToastController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
        assertEquals(ExpensesView.Error(MSG_EXPENSES_LOAD_FAILED), vm.view.value)
    }

    @Test fun theMonthStartsOnTheCurrentMonthAndSearchIsEmpty() {
        val vm = ExpensesViewModel(ExpenseFakeFirestore(), ExpenseFakeAudit(), ExpenseFakeNotifier(), ExpenseFakeSession(Role.MANAGER), ToastController())
        assertEquals(expensesCurrentMonth(lagos), vm.filters.value.month)
        assertEquals("", vm.filters.value.search)
    }
}
