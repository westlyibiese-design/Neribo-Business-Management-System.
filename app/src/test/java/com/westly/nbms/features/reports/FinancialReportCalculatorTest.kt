package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.finance.ApprovalStatus
import com.westly.nbms.features.finance.RevenueCategory
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.SourceCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class FinancialReportCalculatorTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")      // UTC+1, no daylight saving
    private val october = YearMonth.of(2026, 10)

    private fun txn(
        id: String,
        amount: Double,
        category: RevenueCategory = RevenueCategory.ROOM,
        status: ApprovalStatus = ApprovalStatus.APPROVED,
        date: String? = "2026-10-15T10:00:00Z"
    ) = RevenueTransaction(
        id = id, source = SourceCollection.PAYMENTS, category = category, typeLabel = "Payment", guestName = "Guest",
        amount = amount, paymentMethod = "cash", date = date?.let { Instant.parse(it) }, recordedBy = null,
        recordedByName = "Staff", approvalStatus = status, approvedBy = null, approvedByName = null,
        approvedAt = null, rejectedReason = null
    )

    private fun expense(
        id: String,
        amount: Double,
        category: String = "other",
        date: String? = "2026-10-15T10:00:00Z",
        isDeleted: Boolean = false
    ) = Expense(
        id = id, title = "Item $id", amount = amount, category = category,
        date = date?.let { Timestamp(Instant.parse(it).epochSecond, 0) }, isDeleted = isDeleted
    )

    private fun build(transactions: List<RevenueTransaction>, expenses: List<Expense>) =
        FinancialReportCalculator.build(transactions, expenses, october, lagos, "Westly Hotel", "₦")

    // ---- revenue ----

    @Test fun revenue_countsApprovedOnly() {
        val report = build(
            listOf(
                txn("a", 1000.0),
                txn("b", 500.0, status = ApprovalStatus.PENDING),
                txn("c", 700.0, status = ApprovalStatus.REJECTED)
            ),
            emptyList()
        )
        assertEquals(1000.0, report.totalRevenue, 0.0)
        assertEquals(1, report.approvedCount)
    }

    @Test fun revenue_sumsPerCategory() {
        val report = build(
            listOf(
                txn("a", 1000.0, RevenueCategory.ROOM),
                txn("b", 250.0, RevenueCategory.ROOM),
                txn("c", 300.0, RevenueCategory.BAR),
                txn("d", 40.0, RevenueCategory.OTHER)
            ),
            emptyList()
        )
        assertEquals(1250.0, report.revenueOf(RevenueCategory.ROOM), 0.0)
        assertEquals(300.0, report.revenueOf(RevenueCategory.BAR), 0.0)
        assertEquals(40.0, report.revenueOf(RevenueCategory.OTHER), 0.0)
        assertEquals(0.0, report.revenueOf(RevenueCategory.LAUNDRY), 0.0)
        assertEquals(1590.0, report.totalRevenue, 0.0)
    }

    @Test fun revenue_onlyTheChosenMonthInTheBusinessZone() {
        // 2026-09-30T23:30Z is already 00:30 on 1 October in Lagos; 2026-10-31T23:30Z is 00:30 on 1 November in Lagos.
        val report = build(
            listOf(
                txn("in-start", 100.0, date = "2026-09-30T23:30:00Z"),
                txn("in-mid", 200.0, date = "2026-10-15T12:00:00Z"),
                txn("out-end", 400.0, date = "2026-10-31T23:30:00Z"),
                txn("out-sept", 800.0, date = "2026-09-15T12:00:00Z"),
                txn("no-date", 1600.0, date = null)
            ),
            emptyList()
        )
        assertEquals(300.0, report.totalRevenue, 0.0)
        assertEquals(2, report.approvedCount)
    }

    // ---- expenses ----

    @Test fun expenses_onlyTheChosenMonth_andNotDeleted() {
        val report = build(
            emptyList(),
            listOf(
                expense("a", 100.0),
                expense("b", 50.0, date = "2026-09-15T10:00:00Z"),
                expense("c", 75.0, isDeleted = true),
                expense("d", 25.0, date = null)
            )
        )
        assertEquals(100.0, report.totalExpenses, 0.0)
    }

    @Test fun expenses_groupedByCategory_biggestFirst_missingCategoryIsOther() {
        val report = build(
            emptyList(),
            listOf(
                expense("a", 100.0, "utilities"),
                expense("b", 300.0, "payroll"),
                expense("c", 20.0, ""),
                expense("d", 30.0, "no-such-category"),
                expense("e", 10.0, "other")
            )
        )
        assertEquals(
            listOf(ExpenseCategory.PAYROLL to 300.0, ExpenseCategory.UTILITIES to 100.0, ExpenseCategory.OTHER to 60.0),
            report.expensesByCategory
        )
        assertEquals(460.0, report.totalExpenses, 0.0)
    }

    // ---- profit and margin ----

    @Test fun netProfit_isRevenueMinusExpenses() {
        val report = build(listOf(txn("a", 1000.0)), listOf(expense("e", 400.0)))
        assertEquals(600.0, report.netProfit, 0.0)
        assertEquals(60, report.margin)
    }

    @Test fun netProfit_canBeNegative_andMarginToo() {
        val report = build(listOf(txn("a", 1000.0)), listOf(expense("e", 1500.0)))
        assertEquals(-500.0, report.netProfit, 0.0)
        assertEquals(-50, report.margin)
    }

    @Test fun margin_isRoundedToAWholePercent() {
        // net 1000 of 3000 revenue = 33.33...% -> 33 ; net 2000 of 3000 = 66.66...% -> 67
        assertEquals(33, build(listOf(txn("a", 3000.0)), listOf(expense("e", 2000.0))).margin)
        assertEquals(67, build(listOf(txn("a", 3000.0)), listOf(expense("e", 1000.0))).margin)
        // exactly 12.5% rounds up to 13
        assertEquals(13, build(listOf(txn("a", 800.0)), listOf(expense("e", 700.0))).margin)
    }

    @Test fun margin_isZeroWhenThereIsNoRevenue() {
        val report = build(emptyList(), listOf(expense("e", 400.0)))
        assertEquals(0, report.margin)
        assertEquals(-400.0, report.netProfit, 0.0)
    }

    @Test fun emptyMonth_isAllZero() {
        val report = build(emptyList(), emptyList())
        assertEquals(0.0, report.totalRevenue, 0.0)
        assertEquals(0.0, report.totalExpenses, 0.0)
        assertEquals(0.0, report.netProfit, 0.0)
        assertEquals(0, report.margin)
        assertEquals(0, report.approvedCount)
        assertTrue(report.expensesByCategory.isEmpty())
        assertTrue(report.revenueBreakdown().isEmpty())
    }

    // ---- breakdown, labels ----

    @Test fun revenueBreakdown_usesTheWestlyOrderAndDropsZeroSlices() {
        val report = build(
            listOf(
                txn("a", 10.0, RevenueCategory.LAUNDRY),
                txn("b", 20.0, RevenueCategory.ROOM),
                txn("c", 30.0, RevenueCategory.RESTAURANT),
                txn("d", 40.0, RevenueCategory.SALES)
            ),
            emptyList()
        )
        assertEquals(listOf("Room Revenue", "Sales", "Restaurant", "Laundry"), report.revenueBreakdown().map { it.second })
    }

    @Test fun labels_andFileName() {
        assertEquals("October 2026", financialMonthLabel(october))
        assertEquals("financial-report-2026-10.pdf", financialReportFileName(october))
        assertEquals("October 2026 · 1 approved transaction", financialSubtitle(october, 1))
        assertEquals("October 2026 · 0 approved transactions", financialSubtitle(october, 0))
        assertEquals("October 2026 · 5 approved transactions", financialSubtitle(october, 5))
    }

    // ---- page state ----

    @Test fun view_isLoadingUntilBothAnswers_andErrorWinsOverLoading() {
        fun view(r: Resource<List<RevenueTransaction>>, e: Resource<List<Expense>>) =
            financialReportViewOf(r, e, october, lagos, "Westly Hotel", "₦")

        assertEquals(FinancialReportsView.Loading, view(Resource.Loading, Resource.Success(emptyList())))
        assertEquals(FinancialReportsView.Loading, view(Resource.Success(emptyList()), Resource.Loading))
        assertEquals(
            FinancialReportsView.Error("We couldn't load financial data."),
            view(Resource.Loading, Resource.Error("boom"))
        )
        assertTrue(view(Resource.Success(emptyList()), Resource.Success(emptyList())) is FinancialReportsView.Ready)
    }
}
