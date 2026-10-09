package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LaundryRepositoryTest {

    private class Rig(signedIn: Boolean = true) {
        val store = Laundry22aFakeStore()
        val session = Laundry22aFakeSession(Role.LAUNDRY_VALET, signedIn = signedIn)
        val audit = Laundry22aFakeAudit()
        val notifier = Laundry22aFakeNotifier()
        val repo = LaundryRepository(store, session, audit, notifier)
    }

    @Test fun observeLeavesOutDeletedRequests() = runTest {
        val rig = Rig()
        rig.store.requests.value = Resource.Success(listOf(laundry22aRequestOf("a"), laundry22aRequestOf("b", deleted = true)))
        val r = rig.repo.observe().first() as Resource.Success
        assertEquals(listOf("a"), r.data.map { it.id })
    }

    @Test fun observePassesLoadingAndErrorsThrough() = runTest {
        val rig = Rig()
        rig.store.requests.value = Resource.Loading
        assertTrue(rig.repo.observe().first() is Resource.Loading)
        rig.store.requests.value = Resource.Error("boom")
        assertTrue(rig.repo.observe().first() is Resource.Error)
    }

    @Test fun createWritesThePayloadThenAuditsAndNotifies() = runTest {
        val rig = Rig()
        val id = rig.repo.create(LaundryRequestForm(guestName = "Mr Okoro", roomNumber = "201", items = "3 shirts", itemCount = "5", charge = "4000"))
        assertEquals("req1", id)
        assertEquals(1, rig.store.created.size)
        assertEquals("u1", rig.store.created[0]["laundryValetId"])
        assertEquals("Ada", rig.store.created[0]["laundryValetName"])
        assertEquals("pending", rig.store.created[0]["approvalStatus"])

        assertEquals(1, rig.audit.entries.size)
        val a = rig.audit.entries[0]
        assertEquals("laundry_request_created", a.action)
        assertEquals("laundry_requests", a.collection)
        assertEquals("req1", a.id)
        assertNull(a.previous)
        assertEquals(mapOf("guestName" to "Mr Okoro", "roomNumber" to "201"), a.new)

        assertEquals(1, rig.notifier.calls.size)
        assertEquals("New Laundry Request", rig.notifier.calls[0].title)
        assertTrue(rig.notifier.calls[0].message.contains("Ada"))
        assertTrue(rig.notifier.calls[0].message.contains("5 item(s)"))
        assertTrue(rig.notifier.calls[0].message.contains("Mr Okoro"))
    }

    @Test fun theAlertNamesTheRoomWhenThereIsNoGuestName() = runTest {
        val rig = Rig()
        rig.repo.create(LaundryRequestForm(roomNumber = "201"))
        assertTrue(rig.notifier.calls[0].message.contains("Room 201"))
    }

    @Test fun aFailingAuditOrAlertNeverFailsASavedRequest() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.notifier.fail = true
        assertEquals("req1", rig.repo.create(LaundryRequestForm(guestName = "A")))
        assertEquals(1, rig.store.created.size)
    }

    @Test fun aFailedWriteThrowsALaundryExceptionAndSavesNothing() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("offline")
        try {
            rig.repo.create(LaundryRequestForm(guestName = "A"))
            fail("expected a LaundryException")
        } catch (e: LaundryException) {
            assertEquals("offline", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun signedOutCannotCreate() = runTest {
        val rig = Rig(signedIn = false)
        try {
            rig.repo.create(LaundryRequestForm(guestName = "A"))
            fail("expected a LaundryException")
        } catch (e: LaundryException) {
            assertEquals(MSG_LAUNDRY_NOT_SIGNED_IN, e.message)
        }
        assertTrue(rig.store.created.isEmpty())
    }

    @Test fun advanceMovesToTheNextStepAndAudits() = runTest {
        val rig = Rig()
        val next = rig.repo.advanceStatus(laundry22aRequestOf("r1", status = LaundryStatus.RECEIVED))
        assertEquals(LaundryStatus.WASHING, next)
        assertEquals("r1", rig.store.updates[0].first)
        assertEquals("washing", rig.store.updates[0].second["status"])
        assertEquals("u1", rig.store.updates[0].second["updatedBy"])
        val a = rig.audit.entries.single()
        assertEquals("laundry_status_updated", a.action)
        assertEquals(mapOf("status" to "received"), a.previous)
        assertEquals(mapOf("status" to "washing"), a.new)
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun markingReadySendsTheLaundryReadyAlert() = runTest {
        val rig = Rig()
        rig.repo.advanceStatus(laundry22aRequestOf("r1", status = LaundryStatus.IRONING, guest = null, room = "201"))
        assertEquals(1, rig.notifier.calls.size)
        assertEquals("Laundry Ready for Collection", rig.notifier.calls[0].title)
        assertTrue(rig.notifier.calls[0].message.contains("Room 201"))
    }

    @Test fun markingDeliveredWritesDeliveredAt() = runTest {
        val rig = Rig()
        val next = rig.repo.advanceStatus(laundry22aRequestOf("r1", status = LaundryStatus.READY))
        assertEquals(LaundryStatus.DELIVERED, next)
        assertTrue(rig.store.updates[0].second["deliveredAt"] === LaundryServerTime)
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun aDeliveredRequestCannotAdvance() = runTest {
        val rig = Rig()
        try {
            rig.repo.advanceStatus(laundry22aRequestOf("r1", status = LaundryStatus.DELIVERED))
            fail("expected a LaundryException")
        } catch (e: LaundryException) {
            // expected
        }
        assertTrue(rig.store.updates.isEmpty())
    }

    @Test fun aFailedAdvanceWritesNoAudit() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("offline")
        try {
            rig.repo.advanceStatus(laundry22aRequestOf("r1"))
            fail("expected a LaundryException")
        } catch (e: LaundryException) {
            assertEquals("offline", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun updateChargeWritesAndAuditsOldAndNew() = runTest {
        val rig = Rig()
        rig.repo.updateCharge(laundry22aRequestOf("r1", charge = 4000.0), 5500.0)
        assertEquals(5500.0, rig.store.updates[0].second["charge"])
        val a = rig.audit.entries.single()
        assertEquals("laundry_charge_updated", a.action)
        assertEquals(mapOf("charge" to 4000.0), a.previous)
        assertEquals(mapOf("charge" to 5500.0), a.new)
    }

    @Test fun togglePaidFlipsAndTouchesNothingElse() = runTest {
        val rig = Rig()
        assertEquals(PaymentStatus.PAID, rig.repo.togglePaid(laundry22aRequestOf("r1", payment = PaymentStatus.UNPAID)))
        assertEquals(PaymentStatus.UNPAID, rig.repo.togglePaid(laundry22aRequestOf("r2", payment = PaymentStatus.PAID)))
        assertEquals("paid", rig.store.updates[0].second["paymentStatus"])
        assertEquals("unpaid", rig.store.updates[1].second["paymentStatus"])
        assertEquals(setOf("paymentStatus", "updatedAt"), rig.store.updates[0].second.keys)
        assertTrue(rig.audit.entries.isEmpty())
    }
}
