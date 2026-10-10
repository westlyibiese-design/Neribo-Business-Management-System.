package com.westly.nbms.features.opslog

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MaintenanceRepositoryTest {

    private class Rig(role: Role = Role.MANAGER, signedIn: Boolean = true, name: String = "Ada") {
        val store = MtFakeStore()
        val session = LfFakeSession(role, name = name, signedIn = signedIn)
        val audit = LfFakeAudit()
        val notifier = LfFakeNotifier()
        val rooms = MtFakeRoomLogic()
        val repo = MaintenanceRepository(store, session, audit, notifier, rooms)
    }

    private val input = MaintenanceInput("r2", "202", "AC not cooling", "Warm air", MaintenancePriority.HIGH)

    // ── observe ──

    @Test fun observeLeavesOutDeletedRequests() = runTest {
        val rig = Rig()
        rig.store.requests.value = Resource.Success(listOf(mtRequest("a"), mtRequest("b", deleted = true)))
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

    // ── create ──

    @Test fun createSavesTheRequestPutsTheRoomInMaintenanceAndNotifies() = runTest {
        val rig = Rig(Role.HOUSEKEEPING, name = "Ada")
        val result = rig.repo.create(input)

        assertEquals("m1", result.id)
        assertNull(result.roomError)
        assertEquals(listOf(buildMaintenanceCreatePayload(input, "u1", "Ada")), rig.store.created)
        assertEquals(listOf("r2" to RoomStatus.MAINTENANCE), rig.rooms.statusCalls)

        assertEquals(1, rig.notifier.calls.size)
        val n = rig.notifier.calls[0]
        assertEquals("New Maintenance Request", n.title)
        assertEquals("AC not cooling reported for Room 202 by Ada.", n.message)
        // Logging is not audited.
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun theRoomChangeFailingKeepsTheRequestAndReportsTheReason() = runTest {
        val rig = Rig()
        rig.rooms.failWith = IllegalStateException("Room not found")
        val result = rig.repo.create(input)

        assertEquals("m1", result.id)
        assertEquals("Room not found", result.roomError)
        assertEquals(1, rig.store.created.size)
        // The alert still goes out.
        assertEquals(1, rig.notifier.calls.size)
    }

    @Test fun aFailingAlertDoesNotFailLogging() = runTest {
        val rig = Rig()
        rig.notifier.fail = true
        val result = rig.repo.create(input)
        assertNull(result.roomError)
        assertEquals(1, rig.store.created.size)
    }

    @Test fun aSaveFailureThrowsAndTouchesNothingElse() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("Permission denied")
        try {
            rig.repo.create(input)
            fail("expected an exception")
        } catch (e: MaintenanceException) {
            assertEquals("Permission denied", e.message)
        }
        assertTrue(rig.rooms.statusCalls.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun createNeedsASignedInUser() = runTest {
        val rig = Rig(signedIn = false)
        try {
            rig.repo.create(input)
            fail("expected an exception")
        } catch (e: MaintenanceException) {
            assertEquals(MSG_MAINTENANCE_NOT_SIGNED_IN, e.message)
        }
        assertTrue(rig.store.created.isEmpty())
    }

    @Test fun createIsRefusedForARoleThatCannotOpenThePage() = runTest {
        val rig = Rig(Role.MAINTENANCE_TECHNICIAN)
        try {
            rig.repo.create(input)
            fail("expected an exception")
        } catch (e: MaintenanceException) {
            assertEquals(MSG_MAINTENANCE_NO_PERMISSION, e.message)
        }
        assertTrue(rig.store.created.isEmpty())
    }

    // ── close ──

    @Test fun closeUpdatesTheRequestFreesTheRoomAuditsAndNotifies() = runTest {
        val rig = Rig(Role.MANAGER, name = "Ada")
        val freed = rig.repo.close(mtRequest("m9", MaintenancePriority.HIGH, room = "202", roomId = "r2"))

        assertTrue(freed)
        assertEquals(listOf("m9" to buildMaintenanceClosePayload("u1", "Ada")), rig.store.updates)
        assertEquals(listOf("r2" to RoomStatus.AVAILABLE), rig.rooms.statusCalls)

        assertEquals(1, rig.audit.entries.size)
        val a = rig.audit.entries[0]
        assertEquals("maintenance_closed", a.action)
        assertEquals("maintenance", a.collection)
        assertEquals("m9", a.id)
        assertEquals(mapOf("status" to "open"), a.previous)
        assertEquals(mapOf("status" to "closed"), a.new)

        assertEquals(1, rig.notifier.calls.size)
        assertEquals("Maintenance Request Resolved", rig.notifier.calls[0].title)
        assertEquals("Maintenance issue at Room 202 resolved by Ada.", rig.notifier.calls[0].message)
    }

    @Test fun anOccupiedRoomStaysOccupiedButTheRequestStillCloses() = runTest {
        val rig = Rig()
        rig.rooms.failWith = IllegalStateException("Room has a guest")
        val freed = rig.repo.close(mtRequest("m9"))

        assertFalse(freed)
        assertEquals(1, rig.store.updates.size)
        assertEquals("closed", rig.store.updates[0].second["status"])
        assertEquals("maintenance_closed", rig.audit.entries.single().action)
        assertEquals(1, rig.notifier.calls.size)
        assertEquals("Room 202 stays Occupied.", closeResultToast("202", freed).message)
    }

    @Test fun aFailingAuditOrAlertDoesNotFailClose() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.notifier.fail = true
        assertTrue(rig.repo.close(mtRequest("m9")))
        assertEquals(1, rig.store.updates.size)
    }

    @Test fun aRequestWithNoRoomClosesWithoutTouchingAnyRoom() = runTest {
        val rig = Rig()
        assertTrue(rig.repo.close(mtRequest("m9", room = "—", roomId = null)))
        assertTrue(rig.rooms.statusCalls.isEmpty())
        assertEquals(1, rig.store.updates.size)
    }

    @Test fun aCloseSaveFailureThrowsAndLeavesTheRoomAlone() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("Permission denied")
        try {
            rig.repo.close(mtRequest("m9"))
            fail("expected an exception")
        } catch (e: MaintenanceException) {
            assertEquals("Permission denied", e.message)
        }
        assertTrue(rig.rooms.statusCalls.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun closeIsRefusedForARoleThatCannotOpenThePage() = runTest {
        val rig = Rig(Role.ACCOUNTANT)
        try {
            rig.repo.close(mtRequest("m9"))
            fail("expected an exception")
        } catch (e: MaintenanceException) {
            assertEquals(MSG_MAINTENANCE_NO_PERMISSION, e.message)
        }
        assertTrue(rig.store.updates.isEmpty())
    }

    @Test fun operationsManagerCanLogAndClose() = runTest {
        val rig = Rig(Role.OPERATIONS_MANAGER)
        rig.repo.create(input)
        rig.repo.close(mtRequest("m9"))
        assertEquals(1, rig.store.created.size)
        assertEquals(1, rig.store.updates.size)
    }
}
