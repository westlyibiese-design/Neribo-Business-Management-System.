package com.westly.nbms.features.gym

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class GymMembersTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val day = 86_400L

    private class Rig {
        val store = FakeGymMembersStore()
        val audit = FakeGymAudit()
        val notifier = FakeGymNotifier()
        val repo = GymMembersRepository(store, FakeGymSession(), audit, notifier)
    }

    private val weekly = GymPackage("p1", "Weekly Pass", 5000.0, "Weekly")

    // ── registering ──

    @Test fun aCustomRegistrationWritesWestlysDocument() {
        val form = RegisterMemberForm(name = "  Ada Obi ", phone = " 0803 ", customName = "Weekly Pass", durationText = "7", priceText = "5000")
        val p = buildRegisterPayload(form, null, now, "u1", "Gina")
        assertEquals("Ada Obi", p["name"])
        assertEquals("0803", p["phone"])
        assertNull(p["email"])
        assertNull(p["roomNumber"])
        assertEquals("custom", p["packageId"])
        assertEquals("Weekly Pass", p["packageName"])
        assertEquals(5000.0, p["packagePrice"] as Double, 0.0)
        assertEquals(7, p["durationDays"])
        assertEquals(now.toGymTimestamp(), p["startDate"])
        assertEquals(now.plusSeconds(7 * day).toGymTimestamp(), p["endDate"])
        assertEquals("active", p["status"])
        assertEquals(0, p["visitCount"])
        assertNull(p["lastVisitAt"])
        assertNull(p["activeVisitId"])
        assertEquals("u1", p["registeredBy"])
        assertEquals("Gina", p["registeredByName"])
        assertEquals(GymServerTime, p["createdAt"])
        assertEquals(GymServerTime, p["updatedAt"])
        assertEquals(false, p["isDeleted"])
    }

    @Test fun aBlankCustomNameBecomesCustomMembership() {
        val p = buildRegisterPayload(RegisterMemberForm(name = "Ada", customName = "  "), null, now, "u1", "Gina")
        assertEquals("Custom Membership", p["packageName"])
        assertEquals(30, p["durationDays"])
        assertEquals(0.0, p["packagePrice"] as Double, 0.0)
    }

    @Test fun aListedPackageBringsItsNamePriceAndDays() {
        val form = RegisterMemberForm(name = "Ada", packageId = "p1", durationText = "999", priceText = "1")
        val p = buildRegisterPayload(form, weekly, now, "u1", "Gina")
        assertEquals("p1", p["packageId"])
        assertEquals("Weekly Pass", p["packageName"])
        assertEquals(5000.0, p["packagePrice"] as Double, 0.0)
        assertEquals(7, p["durationDays"])
        assertEquals(now.plusSeconds(7 * day).toGymTimestamp(), p["endDate"])
    }

    @Test fun registerChecks() {
        assertEquals(MSG_NAME_REQUIRED, validateRegisterForm(RegisterMemberForm(name = "  ")).name)
        assertEquals(MSG_DURATION_INVALID, validateRegisterForm(RegisterMemberForm(name = "A", durationText = "0")).duration)
        assertEquals(MSG_DURATION_INVALID, validateRegisterForm(RegisterMemberForm(name = "A", durationText = "")).duration)
        assertEquals(MSG_PRICE_INVALID, validateRegisterForm(RegisterMemberForm(name = "A", priceText = "-1")).price)
        assertFalse(validateRegisterForm(RegisterMemberForm(name = "A")).any)
        // a listed package skips the custom checks
        assertFalse(validateRegisterForm(RegisterMemberForm(name = "A", packageId = "p1", durationText = "")).any)
    }

    @Test fun registerWritesAuditsAndAlerts() = runTest {
        val rig = Rig()
        val id = rig.repo.register(RegisterMemberForm(name = "Ada Obi", packageId = "p1"), listOf(weekly), now)
        assertEquals("m-new", id)
        assertEquals(1, rig.store.added.size)
        assertEquals("gym_member_registered", rig.audit.entries.single()[0])
        assertEquals(listOf("gym_membership_registered"), rig.notifier.types)
    }

    @Test fun aBadFormWritesNothing() = runTest {
        val rig = Rig()
        try {
            rig.repo.register(RegisterMemberForm(name = ""), emptyList(), now)
            fail("must throw")
        } catch (e: GymException) {
            assertEquals("Member name is required.", e.message)
        }
        assertTrue(rig.store.added.isEmpty())
    }

    @Test fun aVanishedPackageIsRefused() = runTest {
        val rig = Rig()
        try {
            rig.repo.register(RegisterMemberForm(name = "Ada", packageId = "gone"), listOf(weekly), now)
            fail("must throw")
        } catch (e: GymException) {
            assertEquals(MSG_PACKAGE_MISSING, e.message)
        }
        assertTrue(rig.store.added.isEmpty())
    }

    // ── renewing ──

    @Test fun anEarlyRenewalKeepsTheRemainingDays() = runTest {
        val rig = Rig()
        rig.store.liveEnds["m1"] = now.plusSeconds(5 * day)
        val out = rig.repo.renew("m1", weekly, now)
        assertEquals(now.plusSeconds(12 * day), out.newEnd)
        val f = rig.store.renewals.single()
        assertEquals("p1", f["packageId"])
        assertEquals(7, f["durationDays"])
        assertEquals("active", f["status"])
        assertEquals(out.newEnd.toGymTimestamp(), f["endDate"])
        assertEquals("gym_membership_renewed", rig.audit.entries.single()[0])
        assertEquals(listOf("gym_membership_renewed"), rig.notifier.types)
    }

    @Test fun aLapsedRenewalStartsNow() = runTest {
        val rig = Rig()
        rig.store.liveEnds["m1"] = now.minusSeconds(40 * day)
        assertEquals(now.plusSeconds(7 * day), rig.repo.renew("m1", weekly, now).newEnd)
    }

    @Test fun renewingAMissingMemberFails() = runTest {
        val rig = Rig()
        try {
            rig.repo.renew("nobody", weekly, now)
            fail("must throw")
        } catch (e: GymException) {
            assertEquals("Member not found.", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    // ── edit, status, remove ──

    @Test fun editAndStatusAndRemovePayloads() {
        val edit = buildEditFields(EditMemberForm(name = " Ada ", phone = " ", email = "a@x.com", roomNumber = "12", notes = ""))
        assertEquals("Ada", edit["name"])
        assertNull(edit["phone"])
        assertEquals("a@x.com", edit["email"])
        assertNull(edit["notes"])
        assertEquals(GymServerTime, edit["updatedAt"])
        assertEquals(mapOf<String, Any?>("status" to "suspended", "statusReason" to null, "updatedAt" to GymServerTime), buildStatusFields(MembershipStatus.SUSPENDED))
        assertEquals(mapOf<String, Any?>("isDeleted" to true, "updatedAt" to GymServerTime), buildSoftDeleteFields())
        assertEquals(MSG_NAME_REQUIRED, validateEditForm(EditMemberForm(name = " ")))
    }

    @Test fun onlySuspendingSendsTheAlert() = runTest {
        val rig = Rig()
        rig.repo.setStatus(gymMember(), MembershipStatus.SUSPENDED)
        rig.repo.setStatus(gymMember(), MembershipStatus.ACTIVE)
        assertEquals(listOf("gym_membership_suspended"), rig.notifier.types)
        assertEquals(2, rig.audit.entries.size)
        assertEquals("suspended", rig.store.updates[0].second["status"])
        assertEquals("active", rig.store.updates[1].second["status"])
    }

    @Test fun removingOnlyHidesTheMember() = runTest {
        val rig = Rig()
        rig.repo.softDelete(gymMember())
        assertEquals(true, rig.store.updates.single().second["isDeleted"])
        assertEquals("gym_member_deleted", rig.audit.entries.single()[0])
    }

    // ── filters and rows ──

    private val members = listOf(
        gymMember("a", "Zed Zane", phone = "0801", status = "active", endSeconds = now.epochSecond + 3 * day),
        gymMember("b", "ada obi", phone = "0802", room = "204", status = "active", endSeconds = now.epochSecond - day),
        gymMember("c", "Bola Ade", phone = null, email = "b@x.com", status = "suspended", endSeconds = now.epochSecond + 30 * day),
        gymMember("d", "Gone Guy", deleted = true)
    )

    @Test fun membersAreSortedByNameIgnoringCaseAndRemovedOnesHidden() {
        val rows = filterMembers(members, MemberFilters(), now)
        assertEquals(listOf("b", "c", "a"), rows.map { it.member.id }) // ada, Bola, Zed
    }

    @Test fun theStatusFilterUsesTheEffectiveStatus() {
        val expired = filterMembers(members, MemberFilters(status = MembershipStatus.EXPIRED), now)
        assertEquals(listOf("b"), expired.map { it.member.id })
        val active = filterMembers(members, MemberFilters(status = MembershipStatus.ACTIVE), now)
        assertEquals(listOf("a"), active.map { it.member.id })
        assertEquals(listOf("c"), filterMembers(members, MemberFilters(status = MembershipStatus.SUSPENDED), now).map { it.member.id })
    }

    @Test fun searchMatchesNamePhoneOrRoom() {
        assertEquals(listOf("b", "c"), filterMembers(members, MemberFilters(search = "ad"), now).map { it.member.id })
        assertEquals(listOf("a"), filterMembers(members, MemberFilters(search = " 0801 "), now).map { it.member.id })
        assertEquals(listOf("b"), filterMembers(members, MemberFilters(search = "204"), now).map { it.member.id })
    }

    @Test fun expiryNoteOnlyForActiveMembersWithAWeekOrLess() {
        val rows = filterMembers(members, MemberFilters(), now).associateBy { it.member.id }
        assertEquals("3d left", expiryNote(rows.getValue("a")))
        assertNull(expiryNote(rows.getValue("b"))) // expired
        assertNull(expiryNote(rows.getValue("c"))) // suspended
        val today = MemberRow(gymMember(), MembershipStatus.ACTIVE, 0)
        assertEquals("expires today", expiryNote(today))
        assertNull(expiryNote(MemberRow(gymMember(), MembershipStatus.ACTIVE, 8)))
        assertEquals("7d left", expiryNote(MemberRow(gymMember(), MembershipStatus.ACTIVE, 7)))
    }

    @Test fun theSublinePrefersPhoneThenEmailThenRoom() {
        assertEquals("0801", memberSubline(gymMember(phone = "0801", email = "a@x.com", room = "1")))
        assertEquals("a@x.com", memberSubline(gymMember(phone = null, email = "a@x.com", room = "1")))
        assertEquals("Room 1", memberSubline(gymMember(phone = null, email = null, room = "1")))
        assertEquals("—", memberSubline(gymMember(phone = null, email = null, room = null)))
    }

    @Test fun theViewCountsEveryNonRemovedMember() {
        val view = membersViewOf(Resource.Success(members), MemberFilters(search = "zed"), now) as MembersView.Ready
        assertEquals(3, view.total)
        assertEquals(1, view.rows.size)
    }

    @Test fun renewStartsOnTheCurrentPackageElseTheFirst() {
        val other = GymPackage("p2", "Monthly", 15000.0, "Monthly")
        assertEquals("p2", defaultRenewPackage(gymMember().copy(packageId = "p2"), listOf(weekly, other))?.id)
        assertEquals("p1", defaultRenewPackage(gymMember().copy(packageId = "gone"), listOf(weekly, other))?.id)
        assertNull(defaultRenewPackage(gymMember(), emptyList()))
    }

    // ── packages (tolerant read) ──

    @Test fun packagesAreReadTolerantly() {
        val data = mapOf(
            "packages" to listOf(
                mapOf("id" to "p1", "name" to "Monthly", "price" to 15000L, "duration" to "Monthly"),
                mapOf("id" to 2L, "name" to " Weekly ", "price" to "5000", "duration" to "Weekly"),
                mapOf("name" to "No id", "price" to 1.0),
                mapOf("id" to "p4", "name" to "", "price" to 1.0),
                mapOf("id" to "p5", "name" to "No price"),
                mapOf("id" to "p6", "name" to "Negative", "price" to -5.0),
                "junk", null
            )
        )
        val parsed = parseGymPackages(data)
        assertEquals(listOf("p1", "2"), parsed.map { it.id })
        assertEquals("Weekly", parsed[1].name)
        assertEquals(5000.0, parsed[1].price, 0.0)
        assertTrue(parseGymPackages(null).isEmpty())
        assertTrue(parseGymPackages("nope").isEmpty())
        assertTrue(parseGymPackages(mapOf("packages" to "x")).isEmpty())
    }

    @Test fun packageLabelsReadLikeWestly() {
        assertEquals("Monthly Pass — ₦15,000 (Monthly)", packageOptionLabel(GymPackage("p", "Monthly Pass", 15000.0, "Monthly"), "₦"))
    }

    // ── check-in page helpers ──

    @Test fun searchShowsAtMostEightAndNothingForBlank() {
        val many = (1..12).map { gymMember("m$it", "Member %02d".format(it)) }
        assertEquals(8, searchMembers(many, "member", emptySet(), now).size)
        assertTrue(searchMembers(many, "   ", emptySet(), now).isEmpty())
        assertEquals("Member 01", searchMembers(many, "member", emptySet(), now).first().member.name)
    }

    @Test fun searchFlagsMembersAlreadyInTheGymAndTheirStatus() {
        val ms = listOf(
            gymMember("a", "Ada", activeVisitId = "v1", endSeconds = now.epochSecond + day),
            gymMember("b", "Adam", endSeconds = now.epochSecond + day),
            gymMember("c", "Adele", status = "suspended"),
            gymMember("d", "Adan", deleted = true)
        )
        val found = searchMembers(ms, "ad", setOf("b"), now)
        assertEquals(listOf("a", "b", "c"), found.map { it.member.id })
        assertTrue(found[0].inGym)
        assertTrue(found[1].inGym) // open visit found through the visit list
        assertFalse(found[2].inGym)
        assertEquals(MembershipStatus.SUSPENDED, found[2].status)
    }

    @Test fun openAndTodaysVisitsAreNewestFirst() {
        val visits = listOf(
            gymVisit("old", inSeconds = 100, outSeconds = 200),
            gymVisit("open1", inSeconds = 300),
            gymVisit("open2", inSeconds = 400),
            gymVisit("gone", inSeconds = 500, deleted = true),
            gymVisit("yesterday", dateKey = "2026-10-09", inSeconds = 50)
        )
        assertEquals(listOf("open2", "open1", "yesterday"), openVisitsOf(visits).map { it.id })
        assertEquals(listOf("open2", "open1", "old"), visitsOfDay(visits, "2026-10-10").map { it.id })
    }

    @Test fun aVisitWithoutItsServerTimeYetCountsAsNewest() {
        val visits = listOf(gymVisit("a", inSeconds = 900), gymVisit("pending", inSeconds = null))
        assertEquals("pending", openVisitsOf(visits).first().id)
    }

    @Test fun checkInUiReportsLoadFailureFromEitherQuery() {
        val ok = Resource.Success(emptyList<GymMember>())
        val bad = Resource.Error("x")
        assertTrue(buildCheckInUi(ok, bad, "", now, "Africa/Lagos").failed)
        assertTrue(buildCheckInUi(bad, Resource.Success(emptyList<GymVisit>()), "", now, "Africa/Lagos").failed)
        assertFalse(buildCheckInUi(ok, Resource.Success(emptyList<GymVisit>()), "", now, "Africa/Lagos").failed)
    }

    // ── attendance filters ──

    @Test fun attendanceFiltersByDayAndName() {
        val visits = listOf(
            gymVisit("a", name = "Ada Obi", dateKey = "2026-10-10", inSeconds = 100),
            gymVisit("b", name = "Bola", dateKey = "2026-10-10", inSeconds = 200),
            gymVisit("c", name = "Ada Obi", dateKey = "2026-10-09", inSeconds = 50),
            gymVisit("d", name = "Ada Obi", dateKey = "2026-10-10", inSeconds = 300, deleted = true)
        )
        assertEquals(listOf("b", "a"), filterVisits(visits, "2026-10-10", "").map { it.id })
        assertEquals(listOf("a", "c"), filterVisits(visits, null, " ada ").map { it.id })
        assertEquals(listOf("c"), filterVisits(visits, "2026-10-09", "OBI").map { it.id })
        assertTrue(filterVisits(visits, "2026-01-01", "").isEmpty())
    }
}
