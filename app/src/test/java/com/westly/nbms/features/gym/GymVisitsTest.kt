package com.westly.nbms.features.gym

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class GymVisitsTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")

    private class Rig {
        val store = FakeGymTxStore()
        val audit = FakeGymAudit()
        val notifier = FakeGymNotifier()
        val repo = GymVisitsRepository(store, FakeGymSession(), audit, notifier)
    }

    private fun live(status: String = "active", end: Instant? = Instant.parse("2026-11-10T00:00:00Z"), active: String? = null) =
        LiveGymMember("Ada Obi", status, end, active, 3, false)

    private suspend fun refusal(rig: Rig, member: LiveGymMember?): String {
        if (member != null) rig.store.members["m1"] = member
        return try {
            rig.repo.checkIn(gymMember(), now)
            fail("should have been refused"); ""
        } catch (e: GymException) {
            e.message.orEmpty()
        }
    }

    @Test fun anActiveMemberChecksIn() = runTest {
        val rig = Rig()
        rig.store.members["m1"] = live()
        val out = rig.repo.checkIn(gymMember(), now)
        assertEquals("Ada Obi", out.memberName)
        assertEquals(1, rig.store.visits.size)
        val visit = rig.store.visits.getValue(out.visitId)
        assertEquals("2026-10-10", visit["dateKey"])
        assertEquals(false, visit["isDeleted"])
        assertEquals(null, visit["checkOutAt"])
        assertEquals(out.visitId, rig.store.members.getValue("m1").activeVisitId)
        assertEquals(4, rig.store.members.getValue("m1").visitCount)
        assertEquals("gym_checked_in", rig.audit.entries.single()[0])
        assertEquals(listOf("gym_check_in"), rig.notifier.types)
    }

    @Test fun refusalsUseTheExactMessagesAndWriteNothing() = runTest {
        assertEquals("Member not found.", refusal(Rig(), null))
        assertEquals("This membership has expired. Renew before checking in.", refusal(Rig(), live(end = Instant.parse("2026-10-01T00:00:00Z"))))
        assertEquals("This membership has expired. Renew before checking in.", refusal(Rig(), live(status = "expired", end = Instant.parse("2026-10-01T00:00:00Z"))))
        assertEquals("This membership is suspended.", refusal(Rig(), live(status = "suspended")))
        assertEquals("This membership is cancelled.", refusal(Rig(), live(status = "cancelled")))
        val rig = Rig()
        assertEquals("Ada Obi is already checked in.", refusal(rig, live(active = "v0")))
        assertTrue(rig.store.visits.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aRemovedMemberIsNotFound() = runTest {
        val rig = Rig()
        assertEquals("Member not found.", refusal(rig, live().copy(deleted = true)))
    }

    @Test fun twoCheckInsForTheSameMemberMakeExactlyOneOpenVisit() = runTest {
        val rig = Rig()
        rig.store.members["m1"] = live()
        rig.repo.checkIn(gymMember(), now)
        try {
            rig.repo.checkIn(gymMember(), now)
            fail("second check-in must be refused")
        } catch (e: GymException) {
            assertEquals("Ada Obi is already checked in.", e.message)
        }
        assertEquals(1, rig.store.visits.size)
    }

    @Test fun checkOutClosesTheVisitAndClearsTheGuard() = runTest {
        val rig = Rig()
        rig.store.members["m1"] = live()
        val inn = rig.repo.checkIn(gymMember(), now)
        val out = rig.repo.checkOut(gymVisit(inn.visitId))
        assertEquals("Ada Obi", out.memberName)
        assertTrue(inn.visitId in rig.store.closed)
        assertEquals(null, rig.store.members.getValue("m1").activeVisitId)
        assertEquals(listOf("gym_check_in", "gym_check_out"), rig.notifier.types)
        // and the member can come back
        rig.repo.checkIn(gymMember(), now)
    }

    @Test fun checkOutKeepsTheGuardWhenItPointsAtAnotherVisit() = runTest {
        val rig = Rig()
        rig.store.members["m1"] = live(active = "other")
        rig.store.visits["v9"] = mapOf("memberName" to "Ada Obi")
        rig.repo.checkOut(gymVisit("v9"))
        assertEquals("other", rig.store.members.getValue("m1").activeVisitId)
    }

    @Test fun aVisitClosedTwiceIsRefused() = runTest {
        val rig = Rig()
        rig.store.members["m1"] = live()
        val inn = rig.repo.checkIn(gymMember(), now)
        rig.repo.checkOut(gymVisit(inn.visitId))
        try {
            rig.repo.checkOut(gymVisit(inn.visitId))
            fail("second check-out must be refused")
        } catch (e: GymException) {
            assertEquals("Ada Obi is already checked out.", e.message)
        }
    }

    @Test fun aFailingAuditOrAlertNeverBreaksTheCheckIn() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.notifier.fail = true
        rig.store.members["m1"] = live()
        rig.repo.checkIn(gymMember(), now)
        assertEquals(1, rig.store.visits.size)
    }

    @Test fun theVisitPayloadHasEveryField() {
        val p = buildVisitPayload("m1", "Ada Obi", "u1", "Gina", "2026-10-10")
        assertEquals(
            mapOf<String, Any?>(
                "memberId" to "m1", "memberName" to "Ada Obi", "checkInAt" to GymServerTime, "checkOutAt" to null,
                "checkedInBy" to "u1", "checkedInByName" to "Gina", "checkedOutBy" to null, "checkedOutByName" to null,
                "dateKey" to "2026-10-10", "isDeleted" to false
            ),
            p
        )
        assertFalse(p.containsKey("missing"))
    }
}
