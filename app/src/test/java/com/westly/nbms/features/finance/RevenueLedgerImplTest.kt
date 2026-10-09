package com.westly.nbms.features.finance

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class RevenueLedgerImplTest {

    private val store = FinanceFakeStore()
    private val audit = FinanceFakeAudit()
    private val notifier = FinanceFakeNotifier()

    @After fun restoreZone() { RevenueMath.zone = ZoneId.of("Africa/Lagos") }

    private fun ledger(role: Role? = Role.ACCOUNTANT, timezone: String = "Africa/Lagos") =
        RevenueLedgerImpl(store, audit, notifier, FinanceFakeSession(role, timezone))

    private val approvalKeys = setOf("approvalStatus", "approvedBy", "approvedByName", "approvedAt", "rejectedReason")

    // ── approve ──

    @Test fun approveWritesOnlyTheApprovalFieldsToTheSourceDocument() = runTest {
        ledger().approve(financeTxn(id = "s9", source = SourceCollection.SALES, amount = 2500.0))
        val update = store.updates.single()
        assertEquals(SourceCollection.SALES, update.source)
        assertEquals("s9", update.id)
        assertEquals(approvalKeys, update.fields.keys)
        assertEquals("approved", update.fields["approvalStatus"])
        assertEquals("u1", update.fields["approvedBy"])
        assertEquals("Ada Accounts", update.fields["approvedByName"])
        assertTrue(update.fields["approvedAt"] === ApprovalServerTime)
        assertNull(update.fields["rejectedReason"])
    }

    @Test fun approveAuditsWithPreviousStatusAndAmount() = runTest {
        ledger().approve(financeTxn(id = "p1", source = SourceCollection.PAYMENTS, amount = 45000.0, status = ApprovalStatus.PENDING))
        val e = audit.entries.single()
        assertEquals("payment_approved", e.action)
        assertEquals("payments", e.collection)
        assertEquals("p1", e.id)
        assertEquals(mapOf("approvalStatus" to "pending"), e.previous)
        assertEquals(mapOf("approvalStatus" to "approved", "amount" to 45000.0), e.new)
    }

    @Test fun approveSendsThePaymentApprovedAlert() = runTest {
        ledger().approve(financeTxn(amount = 45000.0, guestName = "Ada Obi"))
        val call = notifier.calls.single()
        assertEquals("payment_approved", call.type)
        assertTrue(call.message.contains("Ada Obi"))
        assertTrue(call.message.contains("Ada Accounts"))
    }

    @Test fun aFailedAlertDoesNotFailTheApproval() = runTest {
        notifier.fail = true
        ledger().approve(financeTxn())
        assertEquals(1, store.updates.size)
        assertEquals(1, audit.entries.size)
    }

    @Test fun aFailedAuditFailsTheApproval() = runTest {
        audit.fail = IllegalStateException("audit down")
        try {
            ledger().approve(financeTxn())
            fail("expected the audit failure to surface")
        } catch (e: IllegalStateException) {
            assertEquals("audit down", e.message)
        }
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun aFailedWriteStopsBeforeAuditAndAlert() = runTest {
        store.failUpdate = IllegalStateException("write failed")
        try {
            ledger().approve(financeTxn())
            fail("expected the write failure to surface")
        } catch (e: IllegalStateException) {
            assertEquals("write failed", e.message)
        }
        assertTrue(audit.entries.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun aPreviouslyRejectedTransactionCanBeApproved() = runTest {
        ledger().approve(financeTxn(status = ApprovalStatus.REJECTED).copy(rejectedReason = "Wrong amount"))
        val update = store.updates.single()
        assertEquals("approved", update.fields["approvalStatus"])
        assertNull(update.fields["rejectedReason"])
        assertEquals(mapOf("approvalStatus" to "rejected"), audit.entries.single().previous)
    }

    @Test fun superAdminMayApprove() = runTest {
        ledger(Role.SUPER_ADMIN).approve(financeTxn())
        assertEquals(1, store.updates.size)
    }

    // ── reject ──

    @Test fun rejectWritesOnlyTheApprovalFieldsWithTheReason() = runTest {
        ledger().reject(financeTxn(id = "o3", source = SourceCollection.ORDERS), "Duplicate entry")
        val update = store.updates.single()
        assertEquals(SourceCollection.ORDERS, update.source)
        assertEquals("o3", update.id)
        assertEquals(approvalKeys, update.fields.keys)
        assertEquals("rejected", update.fields["approvalStatus"])
        assertEquals("Duplicate entry", update.fields["rejectedReason"])
        assertEquals("u1", update.fields["approvedBy"])
    }

    @Test fun rejectAuditsWithReasonAndAmount() = runTest {
        ledger().reject(financeTxn(id = "b2", source = SourceCollection.BAR_ORDERS, amount = 6000.0), "Not received")
        val e = audit.entries.single()
        assertEquals("payment_rejected", e.action)
        assertEquals("bar_orders", e.collection)
        assertEquals("b2", e.id)
        assertEquals(mapOf("approvalStatus" to "rejected", "reason" to "Not received", "amount" to 6000.0), e.new)
    }

    @Test fun aBlankReasonIsStoredAsNull() = runTest {
        ledger().reject(financeTxn(), "   ")
        assertNull(store.updates.single().fields["rejectedReason"])
        ledger().reject(financeTxn(), null)
        assertNull(store.updates.last().fields["rejectedReason"])
    }

    @Test fun rejectSendsNoApprovedAlert() = runTest {
        ledger().reject(financeTxn(), "No")
        assertTrue(notifier.calls.isEmpty())
    }

    // ── permission guard ──

    @Test fun onlySuperAdminAndAccountantMayApproveOrReject() = runTest {
        for (role in Role.entries.filter { it != Role.SUPER_ADMIN && it != Role.ACCOUNTANT }) {
            val l = ledger(role)
            try {
                l.approve(financeTxn())
                fail("$role must not approve")
            } catch (e: IllegalStateException) {
                assertEquals("You don't have permission to approve payments.", e.message)
            }
            try {
                l.reject(financeTxn(), "x")
                fail("$role must not reject")
            } catch (e: IllegalStateException) {
                assertEquals("You don't have permission to approve payments.", e.message)
            }
        }
        assertTrue(store.updates.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun signedOutCannotApprove() = runTest {
        try {
            ledger(role = null).approve(financeTxn())
            fail("expected Not signed in")
        } catch (e: IllegalStateException) {
            assertEquals("Not signed in", e.message)
        }
        assertFalse(store.updates.isNotEmpty())
    }

    // ── observe and time zone ──

    @Test fun signedOutObserveStaysLoading() = runTest {
        assertTrue(ledger(role = null).observe().first() is Resource.Loading)
    }

    @Test fun observeAppliesTheBusinessTimeZone() = runTest {
        ledger(timezone = "America/New_York").observe().first()
        assertEquals(ZoneId.of("America/New_York"), RevenueMath.zone)
    }

    @Test fun anUnknownTimeZoneFallsBackToLagos() = runTest {
        ledger(timezone = "Not/AZone").observe().first()
        assertEquals(ZoneId.of("Africa/Lagos"), RevenueMath.zone)
    }

    @Test fun missingCollectionsAreJustEmpty() = runTest {
        val result = ledger().observe().first() as Resource.Success
        assertTrue(result.data.isEmpty())
    }
}
