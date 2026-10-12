package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GymOverviewViewModelTest {

    private val gymOn = setOf(ModuleKey.GYM)

    @Test fun cardIsHiddenWhenTheGymModuleIsOffForEveryRole() {
        for (role in Role.entries) {
            assertFalse("$role must not see the gym card without the GYM module", gymOverviewVisible(role, emptySet()))
            assertFalse(gymOverviewVisible(role, setOf(ModuleKey.BAR, ModuleKey.LAUNDRY)))
        }
    }

    @Test fun cardIsShownToTheRolesThatMayOpenGymReports() {
        assertTrue(gymOverviewVisible(Role.SUPER_ADMIN, gymOn))
        assertTrue(gymOverviewVisible(Role.MANAGER, gymOn))
        assertTrue(gymOverviewVisible(Role.OPERATIONS_MANAGER, gymOn))
        assertTrue(gymOverviewVisible(Role.GYM_STAFF, gymOn))
    }

    @Test fun cardIsHiddenForRolesThatMayNotOpenGymReportsEvenWithTheModuleOn() {
        assertFalse(gymOverviewVisible(Role.ACCOUNTANT, gymOn))
        assertFalse(gymOverviewVisible(Role.RECEPTIONIST, gymOn))
        assertFalse(gymOverviewVisible(Role.WAITER, gymOn))
        assertFalse(gymOverviewVisible(Role.HOUSEKEEPING, gymOn))
        assertFalse(gymOverviewVisible(Role.DRIVER, gymOn))
    }

    @Test fun stateIsLoadingUntilBothListsArrive() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val zone = ZoneId.of("Africa/Lagos")
        val members = Resource.Success(emptyList<DashGymMember>())
        val visits = Resource.Success(emptyList<DashGymVisit>())
        assertEquals(GymOverviewState.Loading, gymOverviewStateOf(Resource.Loading, visits, now, zone))
        assertEquals(GymOverviewState.Loading, gymOverviewStateOf(members, Resource.Loading, now, zone))
        assertEquals(GymOverviewState.Loading, gymOverviewStateOf(Resource.Loading, Resource.Loading, now, zone))
    }

    @Test fun anErrorInEitherListIsAnError() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val zone = ZoneId.of("Africa/Lagos")
        val ok = Resource.Success(emptyList<DashGymMember>())
        assertEquals(GymOverviewState.Error, gymOverviewStateOf(Resource.Error("x"), Resource.Success(emptyList()), now, zone))
        assertEquals(GymOverviewState.Error, gymOverviewStateOf(ok, Resource.Error("x"), now, zone))
        assertEquals(GymOverviewState.Error, gymOverviewStateOf(Resource.Loading, Resource.Error("x"), now, zone))
    }

    @Test fun readyStateUsesTheBusinessZoneForToday() {
        // 23:30Z on the 11th is 00:30 on the 12th in Lagos: the visit keyed 2026-10-12 is "today" there
        val now = Instant.parse("2026-10-11T23:30:00Z")
        val members = Resource.Success(listOf(DashGymMember("m1", "Ada", "active", null)))
        val visits = Resource.Success(
            listOf(
                DashGymVisit("v1", "m1", dateKey = "2026-10-12"),
                DashGymVisit("v2", "m1", checkOutAt = Instant.parse("2026-10-10T20:00:00Z"), dateKey = "2026-10-10")
            )
        )
        val lagos = gymOverviewStateOf(members, visits, now, ZoneId.of("Africa/Lagos"))
        assertEquals(GymOverviewState.Ready(GymSummary(activeMembers = 1, inGymNow = 1, visitsToday = 1)), lagos)
        val utc = gymOverviewStateOf(members, visits, now, ZoneId.of("UTC"))
        assertEquals(GymOverviewState.Ready(GymSummary(activeMembers = 1, inGymNow = 1, visitsToday = 0)), utc)
    }
}
