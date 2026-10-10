package com.westly.nbms.features.opslog

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class LostFoundRepositoryTest {

    private class Rig(role: Role = Role.MANAGER, signedIn: Boolean = true, name: String = "Ada") {
        val store = LfFakeStore()
        val session = LfFakeSession(role, name = name, signedIn = signedIn)
        val audit = LfFakeAudit()
        val notifier = LfFakeNotifier()
        val repo = LostFoundRepository(store, session, audit, notifier) { LF_NOW }
    }

    private val input = LogFoundInput(
        itemName = "Wristwatch", description = "Silver, leather strap", roomId = "r1", roomNumber = "201",
        foundAt = Instant.parse("2026-10-09T09:00:00Z"), foundBy = "Ada", status = ItemStatus.STORED, notes = null, photoUrl = null
    )

    // ── observe ──

    @Test fun observeLeavesOutDeletedItems() = runTest {
        val rig = Rig()
        rig.store.items.value = Resource.Success(listOf(lfItem("a"), lfItem("b", deleted = true)))
        val r = rig.repo.observe().first() as Resource.Success
        assertEquals(listOf("a"), r.data.map { it.id })
    }

    @Test fun observePassesLoadingAndErrorsThrough() = runTest {
        val rig = Rig()
        rig.store.items.value = Resource.Loading
        assertTrue(rig.repo.observe().first() is Resource.Loading)
        rig.store.items.value = Resource.Error("boom")
        assertTrue(rig.repo.observe().first() is Resource.Error)
    }

    // ── create ──

    @Test fun createWritesThePayloadThenAuditsAndNotifies() = runTest {
        val rig = Rig(Role.HOUSEKEEPING)
        val id = rig.repo.create(input)
        assertEquals("lf1", id)
        assertEquals(1, rig.store.created.size)

        val p = rig.store.created[0]
        assertEquals("Wristwatch", p["itemName"])
        assertEquals("Silver, leather strap", p["description"])
        assertEquals("r1", p["roomId"])
        assertEquals("201", p["roomNumber"])
        assertEquals("stored", p["status"])
        assertEquals("u1", p["createdBy"])
        assertEquals("Ada", p["createdByName"])
        assertEquals("u1", p["updatedBy"])
        assertNull(p["notes"])
        assertNull(p["photoUrl"])
        assertEquals(false, p["isDeleted"])
        @Suppress("UNCHECKED_CAST")
        val history = p["statusHistory"] as List<Map<String, Any?>>
        assertEquals(1, history.size)
        assertEquals("Item logged", history[0]["note"])
        assertEquals(LF_NOW, history[0]["changedAt"])

        assertEquals(1, rig.audit.entries.size)
        val a = rig.audit.entries[0]
        assertEquals("lost_found_item_logged", a.action)
        assertEquals("lost_found", a.collection)
        assertEquals("lf1", a.id)
        assertNull(a.previous)
        assertEquals(mapOf("itemName" to "Wristwatch", "roomNumber" to "201", "status" to "stored"), a.new)

        assertEquals(1, rig.notifier.calls.size)
        assertEquals("Lost & Found Item Logged", rig.notifier.calls[0].title)
        assertTrue(rig.notifier.calls[0].message.contains("Wristwatch"))
        assertTrue(rig.notifier.calls[0].message.contains("Room 201"))
        assertTrue(rig.notifier.calls[0].message.contains("Ada"))
        assertEquals("/admin/lost-found", rig.notifier.calls[0].link)
    }

    @Test fun createKeepsTypedRoomWithNullRoomId() = runTest {
        val rig = Rig(Role.HOUSEKEEPING)
        rig.repo.create(input.copy(roomId = null, roomNumber = "305"))
        assertNull(rig.store.created[0]["roomId"])
        assertEquals("305", rig.store.created[0]["roomNumber"])
    }

    @Test fun createFailureThrowsAndDoesNotAuditOrNotify() = runTest {
        val rig = Rig(Role.HOUSEKEEPING)
        rig.store.failWith = IllegalStateException("No permission")
        try {
            rig.repo.create(input)
            fail("expected LostFoundException")
        } catch (e: LostFoundException) {
            assertEquals("No permission", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun auditOrAlertFailuresDoNotFailTheCreate() = runTest {
        val rig = Rig(Role.HOUSEKEEPING)
        rig.audit.fail = true
        rig.notifier.fail = true
        assertEquals("lf1", rig.repo.create(input))
        assertEquals(1, rig.store.created.size)
    }

    @Test fun createIsRefusedWhenSignedOutOrWithoutPermission() = runTest {
        val out = Rig(signedIn = false)
        try { out.repo.create(input); fail("expected LostFoundException") } catch (e: LostFoundException) { assertEquals(MSG_LOST_FOUND_NOT_SIGNED_IN, e.message) }
        assertTrue(out.store.created.isEmpty())

        val receptionist = Rig(Role.RECEPTIONIST)
        try { receptionist.repo.create(input); fail("expected LostFoundException") } catch (e: LostFoundException) { assertEquals(MSG_LOST_FOUND_NO_PERMISSION, e.message) }
        assertTrue(receptionist.store.created.isEmpty())
    }

    // ── status change ──

    @Test fun changeStatusUpdatesAndAppendsHistory() = runTest {
        val rig = Rig(Role.MANAGER, name = "Boss")
        rig.repo.changeStatus(lfItem("a", name = "Wristwatch", status = ItemStatus.STORED), ItemStatus.RETURNED_TO_GUEST, " Guest called ")

        assertEquals(1, rig.store.updates.size)
        val (id, fields) = rig.store.updates[0]
        assertEquals("a", id)
        assertEquals(setOf("status", "updatedAt", "updatedBy", "updatedByName", "statusHistory"), fields.keys)
        assertEquals("returned_to_guest", fields["status"])
        assertEquals("u1", fields["updatedBy"])
        assertEquals("Boss", fields["updatedByName"])
        val append = fields["statusHistory"] as LostFoundHistoryAppend
        assertEquals("returned_to_guest", append.entry["status"])
        assertEquals("Guest called", append.entry["note"])
        assertEquals("Boss", append.entry["changedByName"])
        assertEquals(LF_NOW, append.entry["changedAt"])

        assertEquals(1, rig.audit.entries.size)
        val a = rig.audit.entries[0]
        assertEquals("lost_found_status_changed:stored→returned_to_guest", a.action)
        assertEquals("lost_found", a.collection)
        assertEquals("a", a.id)
        assertEquals(mapOf("status" to "stored"), a.previous)
        assertEquals(mapOf("status" to "returned_to_guest"), a.new)
    }

    @Test fun claimedAndReturnedSendTheClaimedAlertWithTheGuestNameOrFallback() = runTest {
        val rig = Rig(Role.SUPER_ADMIN, name = "Boss")
        rig.repo.changeStatus(lfItem("a", name = "Ring", guestName = "Mr Okoro"), ItemStatus.CLAIMED, null)
        rig.repo.changeStatus(lfItem("b", name = "Scarf"), ItemStatus.RETURNED_TO_GUEST, null)
        assertEquals(2, rig.notifier.calls.size)
        assertEquals("Lost & Found Item Claimed", rig.notifier.calls[0].title)
        assertTrue(rig.notifier.calls[0].message.contains("Ring"))
        assertTrue(rig.notifier.calls[0].message.contains("Mr Okoro"))
        assertTrue(rig.notifier.calls[0].message.contains("Boss"))
        assertTrue(rig.notifier.calls[1].message.contains("the guest"))
    }

    @Test fun otherStatusChangesSendNoAlert() = runTest {
        val rig = Rig(Role.MANAGER)
        rig.repo.changeStatus(lfItem("a", status = ItemStatus.CLAIMED), ItemStatus.DISPOSED, null)
        rig.repo.changeStatus(lfItem("b", status = ItemStatus.DISPOSED), ItemStatus.STORED, "Found again")
        assertTrue(rig.notifier.calls.isEmpty())
        assertEquals(2, rig.audit.entries.size)
    }

    @Test fun changeStatusFailureThrowsAndDoesNotAudit() = runTest {
        val rig = Rig(Role.MANAGER)
        rig.store.failWith = IllegalStateException("offline")
        try {
            rig.repo.changeStatus(lfItem("a"), ItemStatus.CLAIMED, null)
            fail("expected LostFoundException")
        } catch (e: LostFoundException) {
            assertEquals("offline", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun housekeepingCannotChangeStatus() = runTest {
        val rig = Rig(Role.HOUSEKEEPING)
        try {
            rig.repo.changeStatus(lfItem("a"), ItemStatus.CLAIMED, null)
            fail("expected LostFoundException")
        } catch (e: LostFoundException) {
            assertEquals(MSG_LOST_FOUND_NO_PERMISSION, e.message)
        }
        assertTrue(rig.store.updates.isEmpty())
    }

    @Test fun changeStatusWhenSignedOutIsRefused() = runTest {
        val rig = Rig(signedIn = false)
        try {
            rig.repo.changeStatus(lfItem("a"), ItemStatus.CLAIMED, null)
            fail("expected LostFoundException")
        } catch (e: LostFoundException) {
            assertEquals(MSG_LOST_FOUND_NOT_SIGNED_IN, e.message)
        }
        assertTrue(rig.store.updates.isEmpty())
    }
}
