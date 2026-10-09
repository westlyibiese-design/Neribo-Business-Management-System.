package com.westly.nbms.features.restaurant

import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrdersRepositoryTest {

    private class Rig(signedIn: Boolean = true, role: Role = Role.WAITER) {
        val store = FakeOrdersStore()
        val audit = FakeOrderAudit()
        val notifier = FakeOrderNotifier()
        val repo = OrdersRepository(store, FakeOrderSession(signedIn, role), audit, notifier)
    }

    private val jollof = OrderCartLine("m1", "Jollof Rice", 3500.0, 2, false)
    private val soup = OrderCartLine("manual-1-abc123", "Off-menu soup", 1200.0, 1, true)

    // ── placing ──

    @Test fun placeSavesOnePendingOrderInOneTransaction() = runTest {
        val r = Rig()
        val form = OrderForm(roomNumber = "201", payment = OrderPaymentMethod.ROOM_CHARGE)
        val result = r.repo.place(listOf(jollof, soup), form)

        assertEquals(1, r.store.createCalls)
        assertEquals(8200.0, result.total, 0.0)
        assertEquals(2, result.itemCount)
        val doc = r.store.created.getValue(result.orderId)
        assertEquals("u1", doc["waiterId"]); assertEquals("Wale", doc["waiterName"])
        assertEquals("pending", doc["status"]); assertEquals("pending", doc["approvalStatus"])
        assertEquals("201", doc["roomNumber"]); assertEquals("room_charge", doc["paymentMethod"])
        assertEquals(8200.0, doc["total"]); assertEquals(true, doc["hasManualItems"]); assertEquals(false, doc["isDeleted"])
        assertTrue(doc["createdAt"] === OrderServerTime)
    }

    @Test fun placeLogsTheAuditEntryAndSendsTheAlertToTheOrdersPage() = runTest {
        val r = Rig()
        val result = r.repo.place(listOf(jollof), OrderForm())
        assertEquals(listOf(listOf("new_order", "orders", result.orderId, mapOf("total" to 7000.0))), r.audit.entries)
        val call = r.notifier.calls.single()
        assertEquals("new_sale", call.type)
        assertTrue(call.message.contains("Wale")); assertTrue(call.message.contains("restaurant"))
        assertEquals("/admin/orders/history", call.link)
    }

    @Test fun aFailingAuditOrAlertNeverTurnsASavedOrderIntoAnError() = runTest {
        val r = Rig()
        r.audit.fail = true; r.notifier.fail = true
        val result = r.repo.place(listOf(jollof), OrderForm())
        assertTrue(r.store.created.containsKey(result.orderId))
    }

    @Test fun anEmptyOrderIsRefusedAndNothingIsSaved() = runTest {
        val r = Rig()
        try { r.repo.place(emptyList(), OrderForm()); fail("expected an error") } catch (e: OrderException) {
            assertEquals("The order is empty.", e.message)
        }
        assertEquals(0, r.store.createCalls)
    }

    @Test fun signedOutIsRefusedAndNothingIsSaved() = runTest {
        val r = Rig(signedIn = false)
        try { r.repo.place(listOf(jollof), OrderForm()); fail("expected an error") } catch (e: OrderException) {
            assertEquals("Not signed in", e.message)
        }
        assertEquals(0, r.store.createCalls)
    }

    @Test fun aFailedSaveGivesItsMessageAndSkipsTheExtras() = runTest {
        val r = Rig()
        r.store.failCreateWith = IllegalStateException("You don't have access to this data.")
        try { r.repo.place(listOf(jollof), OrderForm()); fail("expected an error") } catch (e: IllegalStateException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        assertTrue(r.store.created.isEmpty()); assertTrue(r.audit.entries.isEmpty()); assertTrue(r.notifier.calls.isEmpty())
    }

    @Test fun aSaveThatNeverFinishesEndsWithAClearTimeoutMessage() = runTest {
        val r = Rig()
        r.store.hang = true
        try { r.repo.place(listOf(jollof), OrderForm()); fail("expected a timeout") } catch (e: OrderException) {
            assertEquals("The order took too long to save. Check your connection and try again.", e.message)
        }
        assertTrue(r.audit.entries.isEmpty())
    }

    // ── status ──

    @Test fun updateStatusWritesStatusServerTimeAndWho() = runTest {
        val r = Rig()
        r.repo.updateStatus("o1", "pending", OrderStatus.PREPARING)
        val (id, fields) = r.store.updates.single()
        assertEquals("o1", id)
        assertEquals(setOf("status", "updatedAt", "updatedBy"), fields.keys)
        assertEquals("preparing", fields["status"]); assertEquals("u1", fields["updatedBy"])
        assertTrue(fields["updatedAt"] === OrderServerTime)
    }

    @Test fun pendingCanGoStraightToServedAndPreparingToServed() = runTest {
        val r = Rig()
        r.repo.updateStatus("o1", "pending", OrderStatus.SERVED)
        r.repo.updateStatus("o2", "preparing", OrderStatus.SERVED)
        assertEquals(listOf("o1", "o2"), r.store.updates.map { it.first })
    }

    @Test fun aMoveTheRulesDoNotAllowWritesNothing() = runTest {
        val r = Rig()
        for ((from, to) in listOf("served" to OrderStatus.PREPARING, "cancelled" to OrderStatus.SERVED, "preparing" to OrderStatus.PREPARING, "pending" to OrderStatus.CANCELLED)) {
            try { r.repo.updateStatus("o1", from, to); fail("expected a refusal for $from -> ${to.key}") } catch (e: OrderException) {
                assertEquals("This order can't be changed to that status.", e.message)
            }
        }
        assertTrue(r.store.updates.isEmpty())
    }

    @Test fun aFailedStatusUpdateGivesAMessage() = runTest {
        val r = Rig()
        r.store.failUpdateWith = IllegalStateException("You don't have access to this data.")
        try { r.repo.updateStatus("o1", "pending", OrderStatus.PREPARING); fail("expected an error") } catch (e: OrderException) {
            assertEquals("You don't have access to this data.", e.message)
        }
    }

    @Test fun updateStatusSignedOutIsRefused() = runTest {
        val r = Rig(signedIn = false)
        try { r.repo.updateStatus("o1", "pending", OrderStatus.PREPARING); fail("expected an error") } catch (e: OrderException) {
            assertEquals("Not signed in", e.message)
        }
        assertTrue(r.store.updates.isEmpty())
    }
}
