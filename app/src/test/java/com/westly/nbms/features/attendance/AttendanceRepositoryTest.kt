package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class AttendanceRepositoryTest {

    private class Rig(role: Role = Role.RECEPTIONIST, signedIn: Boolean = true, timezone: String = "Africa/Lagos") {
        val store = AttFakeStore()
        val session = AttFakeSession(role, uid = "u9", name = "Ada Recorder", signedIn = signedIn, timezone = timezone)
        val repo = AttendanceRepository(store, session)
    }

    private val staff = attUser("s1", "Sam Staff", role = "housekeeping")

    // ── save: merge-write of the standard id ──

    @Test fun saveWritesToTheStandardDocumentId() = runTest {
        val rig = Rig()
        rig.repo.save(AttendanceRowState(), staff, "2026-10-09", existing = null)
        assertEquals(listOf("s1__2026-10-09"), rig.store.merges.map { it.first })
    }

    @Test fun savePayloadHasEveryFieldOfTheRecord() = runTest {
        val rig = Rig()
        rig.repo.save(AttendanceRowState(AttendanceStatus.LATE, " 09:15 ", "17:00", " Bus broke down "), staff, "2026-10-09", existing = null)
        val p = rig.store.merges.single().second
        assertEquals("s1", p["staffId"])
        assertEquals("Sam Staff", p["staffName"])
        assertEquals("housekeeping", p["staffRole"])
        assertEquals("2026-10-09", p["dateKey"])
        assertEquals(Timestamp(Instant.parse("2026-10-08T23:00:00Z").epochSecond, 0), p["date"]) // midnight in Lagos
        assertEquals("late", p["status"])
        assertEquals("09:15", p["clockIn"])
        assertEquals("17:00", p["clockOut"])
        assertEquals("Bus broke down", p["notes"])
        assertEquals("u9", p["recordedBy"])
        assertEquals("Ada Recorder", p["recordedByName"])
        assertSame(AttendanceServerTime, p["updatedAt"])
        assertEquals(false, p["isDeleted"])
    }

    @Test fun blankTimesAndNotesAreStoredAsNull() = runTest {
        val rig = Rig()
        rig.repo.save(AttendanceRowState(AttendanceStatus.ABSENT, "", "   ", ""), staff, "2026-10-09", existing = null)
        val p = rig.store.merges.single().second
        assertTrue(p.containsKey("clockIn") && p["clockIn"] == null)
        assertTrue(p.containsKey("clockOut") && p["clockOut"] == null)
        assertTrue(p.containsKey("notes") && p["notes"] == null)
    }

    @Test fun createdAtIsAddedOnlyWhenThereWasNoRecord() = runTest {
        val rig = Rig()
        rig.repo.save(AttendanceRowState(), staff, "2026-10-09", existing = null)
        rig.repo.save(AttendanceRowState(), staff, "2026-10-09", existing = att("s1"))
        val (fresh, update) = rig.store.merges.map { it.second }
        assertSame(AttendanceServerTime, fresh["createdAt"])
        assertFalse(update.containsKey("createdAt"))
        assertSame(AttendanceServerTime, update["updatedAt"])
    }

    @Test fun staffWithNoRoleStoresNullRole() = runTest {
        val rig = Rig()
        rig.repo.save(AttendanceRowState(), attUser("s2", "No Role", role = null), "2026-10-09", null)
        assertTrue(rig.store.merges.single().second["staffRole"] == null)
    }

    @Test fun midnightFollowsTheBusinessTimeZone() = runTest {
        val rig = Rig(timezone = "UTC")
        rig.repo.save(AttendanceRowState(), staff, "2026-10-09", null)
        assertEquals(Timestamp(Instant.parse("2026-10-09T00:00:00Z").epochSecond, 0), rig.store.merges.single().second["date"])
    }

    @Test fun saveThrowsWhenSignedOutAndWritesNothing() = runTest {
        val rig = Rig(signedIn = false)
        try {
            rig.repo.save(AttendanceRowState(), staff, "2026-10-09", null)
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertEquals("Not signed in", e.message)
        }
        assertTrue(rig.store.merges.isEmpty())
    }

    @Test fun saveThrowsWhenTheWriteFails() = runTest {
        val rig = Rig()
        rig.store.failWith = RuntimeException("offline")
        try {
            rig.repo.save(AttendanceRowState(), staff, "2026-10-09", null)
            fail("expected an exception")
        } catch (e: RuntimeException) {
            assertEquals("offline", e.message)
        }
    }

    // ── observe ──

    @Test fun observeAllPassesTheWholeCollectionThrough() = runTest {
        val rig = Rig()
        rig.store.records.value = Resource.Success(listOf(att("a"), att("b", deleted = true)))
        val r = rig.repo.observeAll().first() as Resource.Success
        assertEquals(listOf("a", "b"), r.data.map { it.staffId }) // callers drop the deleted ones
    }

    @Test fun observeUsersDropsDeletedAccounts() = runTest {
        val rig = Rig()
        rig.store.users.value = Resource.Success(listOf(attUser("a"), attUser("b", deleted = true), attUser("c", status = "suspended")))
        val r = rig.repo.observeUsers().first() as Resource.Success
        assertEquals(listOf("a", "c"), r.data.map { it.id }) // callers keep the active ones
    }

    @Test fun observeUsersPassesLoadingAndErrorsThrough() = runTest {
        val rig = Rig()
        rig.store.users.value = Resource.Loading
        assertTrue(rig.repo.observeUsers().first() is Resource.Loading)
        rig.store.users.value = Resource.Error("boom")
        assertEquals("boom", (rig.repo.observeUsers().first() as Resource.Error).message)
    }

    @Test fun payloadBuilderIsPureAndSkipsCreatedAtForExisting() {
        val p = attendanceSavePayload(AttendanceRowState(), staff, "2026-10-09", att("s1"), "u1", "Ada", LAGOS)
        assertNull(p["createdAt"])
        assertFalse(p.containsKey("createdAt"))
    }
}
