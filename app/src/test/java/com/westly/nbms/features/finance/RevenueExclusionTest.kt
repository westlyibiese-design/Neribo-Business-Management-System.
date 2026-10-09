package com.westly.nbms.features.finance

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueExclusionTest {

    @Test fun deletedRecordsAreExcludedEverywhere() {
        assertFalse(RawPayment(id = "1", isDeleted = true).isIncluded())
        assertFalse(RawSale(id = "1", isDeleted = true).isIncluded())
        assertFalse(RawOrder(id = "1", isDeleted = true).isIncluded())
        assertFalse(RawBarOrder(id = "1", isDeleted = true).isIncluded())
        assertFalse(RawLaundry(id = "1", charge = 500.0, isDeleted = true).isIncluded())
    }

    @Test fun normalRecordsAreIncluded() {
        assertTrue(RawPayment(id = "1").isIncluded())
        assertTrue(RawSale(id = "1").isIncluded())
        assertTrue(RawOrder(id = "1", status = "served").isIncluded())
        assertTrue(RawBarOrder(id = "1", status = "pending").isIncluded())
        assertTrue(RawLaundry(id = "1", status = "washing", charge = 500.0).isIncluded())
    }

    @Test fun cancelledOrdersAndBarOrdersAreExcluded() {
        assertFalse(RawOrder(id = "1", status = "cancelled").isIncluded())
        assertFalse(RawBarOrder(id = "1", status = "cancelled").isIncluded())
    }

    @Test fun cancelledOrUnpricedLaundryIsExcluded() {
        assertFalse(RawLaundry(id = "1", status = "cancelled", charge = 500.0).isIncluded())
        assertFalse(RawLaundry(id = "1", charge = 0.0).isIncluded())
        assertFalse(RawLaundry(id = "1", charge = -10.0).isIncluded())
    }

    @Test fun cancelledPaymentsAndSalesAreNotExcludedByStatus() {
        // Only isDeleted removes a payment or a retail sale.
        assertTrue(RawPayment(id = "1", approvalStatus = "rejected").isIncluded())
        assertTrue(RawSale(id = "1", approvalStatus = "rejected").isIncluded())
    }

    @Test fun theLedgerAppliesTheExclusionsAndKeepsRejectedHistory() = runTest {
        val store = FinanceFakeStore()
        store.paymentsFlow.value = Resource.Success(
            listOf(RawPayment(id = "p-ok", amount = 100.0), RawPayment(id = "p-del", isDeleted = true), RawPayment(id = "p-rej", approvalStatus = "rejected"))
        )
        store.ordersFlow.value = Resource.Success(listOf(RawOrder(id = "o-ok", total = 50.0), RawOrder(id = "o-cancel", status = "cancelled")))
        store.barOrdersFlow.value = Resource.Success(listOf(RawBarOrder(id = "b-cancel", status = "cancelled")))
        store.laundryFlow.value = Resource.Success(
            listOf(RawLaundry(id = "l-ok", charge = 20.0), RawLaundry(id = "l-free", charge = 0.0), RawLaundry(id = "l-cancel", charge = 9.0, status = "cancelled"))
        )
        val ledger = RevenueLedgerImpl(store, FinanceFakeAudit(), FinanceFakeNotifier(), FinanceFakeSession(Role.ACCOUNTANT))
        val result = ledger.observe().first() as Resource.Success
        assertEquals(setOf("p-ok", "p-rej", "o-ok", "l-ok"), result.data.map { it.id }.toSet())
    }

    @Test fun datesAreSortedNewestFirstAcrossSources() = runTest {
        val store = FinanceFakeStore()
        store.paymentsFlow.value = Resource.Success(listOf(RawPayment(id = "old", createdAt = Timestamp(1_000, 0)), RawPayment(id = "undated")))
        store.salesFlow.value = Resource.Success(listOf(RawSale(id = "new", createdAt = Timestamp(3_000, 0))))
        store.ordersFlow.value = Resource.Success(listOf(RawOrder(id = "mid", createdAt = Timestamp(2_000, 0))))
        val ledger = RevenueLedgerImpl(store, FinanceFakeAudit(), FinanceFakeNotifier(), FinanceFakeSession(Role.ACCOUNTANT))
        val result = ledger.observe().first() as Resource.Success
        assertEquals(listOf("new", "mid", "old", "undated"), result.data.map { it.id })
    }

    @Test fun loadingOrErrorInAnySourceWins() = runTest {
        val store = FinanceFakeStore()
        val ledger = RevenueLedgerImpl(store, FinanceFakeAudit(), FinanceFakeNotifier(), FinanceFakeSession(Role.ACCOUNTANT))
        store.salesFlow.value = Resource.Loading
        assertTrue(ledger.observe().first() is Resource.Loading)
        store.barOrdersFlow.value = Resource.Error("No access")
        assertEquals("No access", (ledger.observe().first() as Resource.Error).message)
    }
}
