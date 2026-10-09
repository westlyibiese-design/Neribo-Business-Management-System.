package com.westly.nbms.features.bar

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BarSalesHistoryRepositoryTest {

    private class Rig(role: Role = Role.BAR_ATTENDANT, signedIn: Boolean = true) {
        val store = BarHistoryFakeStore()
        val repo = BarSalesHistoryRepository(store, BarHistoryFakeSession(role, "u1", signedIn))
    }

    private suspend fun failureOf(block: suspend () -> Unit): String? =
        try {
            block()
            null
        } catch (e: BarStockException) {
            e.message
        }

    @Test fun observePassesTheAttendantScopeToTheStore() = runTest {
        val r = Rig()
        r.store.sales.value = Resource.Success(listOf(barHistorySaleOf("a")))
        r.repo.observe("u1").first()
        r.repo.observe(null).first()
        assertEquals(listOf<String?>("u1", null), r.store.observedWith)
    }

    @Test fun observeHandsBackTheStoresSales() = runTest {
        val r = Rig()
        r.store.sales.value = Resource.Success(listOf(barHistorySaleOf("a"), barHistorySaleOf("b")))
        val result = r.repo.observe(null).first() as Resource.Success
        assertEquals(listOf("a", "b"), result.data.map { it.id })
    }

    @Test fun markServedWritesStatusUpdatedAtAndUpdatedByOnThatSale() = runTest {
        val r = Rig()
        r.repo.markServed(barHistorySaleOf("sale1", status = BarSaleStatus.PENDING))
        assertEquals(1, r.store.updates.size)
        val (id, fields) = r.store.updates[0]
        assertEquals("sale1", id)
        assertEquals(setOf("status", "updatedAt", "updatedBy"), fields.keys)
        assertEquals("served", fields["status"])
        assertEquals("u1", fields["updatedBy"])
        assertSame(BarHistoryServerTime, fields["updatedAt"])
    }

    @Test fun aManagerAndASuperAdminMayMarkServed() = runTest {
        listOf(Role.MANAGER, Role.SUPER_ADMIN).forEach { role ->
            val r = Rig(role)
            r.repo.markServed(barHistorySaleOf("s"))
            assertEquals(1, r.store.updates.size)
        }
    }

    @Test fun anAccountantAndAnOperationsManagerAreReadOnly() = runTest {
        listOf(Role.ACCOUNTANT, Role.OPERATIONS_MANAGER).forEach { role ->
            val r = Rig(role)
            assertEquals("You are not allowed to update bar sales.", failureOf { r.repo.markServed(barHistorySaleOf("s")) })
            assertTrue(r.store.updates.isEmpty())
        }
    }

    @Test fun onlyPendingSalesCanBeMarkedServed() = runTest {
        val r = Rig()
        listOf(BarSaleStatus.SERVED, BarSaleStatus.CANCELLED).forEach { status ->
            assertEquals("Only pending sales can be marked as served.", failureOf { r.repo.markServed(barHistorySaleOf("s", status = status)) })
        }
        assertTrue(r.store.updates.isEmpty())
    }

    @Test fun signedOutWritesNothing() = runTest {
        val r = Rig(signedIn = false)
        assertEquals("Not signed in", failureOf { r.repo.markServed(barHistorySaleOf("s")) })
        assertTrue(r.store.updates.isEmpty())
    }

    @Test fun aDatabaseFailureReachesThePersonUnchanged() = runTest {
        val r = Rig()
        r.store.failWith = IllegalStateException("PERMISSION_DENIED")
        try {
            r.repo.markServed(barHistorySaleOf("s"))
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertEquals("PERMISSION_DENIED", e.message)
        }
    }

    @Test fun anUpdateThatNeverAnswersTimesOutWithAClearMessage() = runTest {
        val r = Rig()
        r.store.hang = true
        assertEquals("The update took too long. Check your connection and try again.", failureOf { r.repo.markServed(barHistorySaleOf("s")) })
    }
}
