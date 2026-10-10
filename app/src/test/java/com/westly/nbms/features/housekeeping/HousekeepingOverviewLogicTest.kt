package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HousekeepingOverviewLogicTest {

    private val rooms = listOf(
        overviewRoom("r10", "10", status = "cleaning", type = "Deluxe Room", floor = "2"),
        overviewRoom("r2", "2", status = "cleaning"),
        overviewRoom("r3", "3", status = "cleaning", isDeleted = true),
        overviewRoom("r4", "4", status = "maintenance"),
        overviewRoom("r5", "5", status = "available"),
        overviewRoom("r6", "6", status = "available"),
        overviewRoom("r7", "7", status = "occupied"),
        overviewRoom("r8", "8", status = "available", isDeleted = true)
    )

    @Test fun listShowsOnlyCleaningRoomsThatAreNotDeletedInNumberOrder() {
        val state = buildOverviewState(Resource.Success(rooms), Resource.Success(emptyList()))
        assertEquals(listOf("2", "10"), state.cleaningRooms.map { it.number })
    }

    @Test fun threeStatCountsIgnoreDeletedRooms() {
        val state = buildOverviewState(Resource.Success(rooms), Resource.Success(emptyList()))
        assertEquals(2, state.needsCleaning)
        assertEquals(1, state.maintenance)
        assertEquals(2, state.available)
    }

    @Test fun subtitleCountsRoomsNeedingCleaning() {
        assertEquals("2 rooms need cleaning today across all housekeepers", buildOverviewState(Resource.Success(rooms), Resource.Success(emptyList())).subtitle)
        assertEquals("1 room needs cleaning today across all housekeepers", overviewSubtitle(1))
        assertEquals("0 rooms need cleaning today across all housekeepers", overviewSubtitle(0))
    }

    @Test fun roomCardSecondLineIsTypeAndFloor() {
        assertEquals("Deluxe Room · Floor 2", overviewRoomSubtitle(rooms.first()))
    }

    @Test fun bannerShowsOnlyForPendingTasksWithNoOwner() {
        val none = buildOverviewState(
            Resource.Success(rooms),
            Resource.Success(listOf(overviewTask("t1", assignedTo = "h1"), overviewTask("t2", status = "in_progress")))
        )
        assertFalse(none.showUnassignedBanner)

        val two = buildOverviewState(
            Resource.Success(rooms),
            Resource.Success(listOf(overviewTask("t1"), overviewTask("t2", assignedTo = ""), overviewTask("t3", assignedTo = "h1"), overviewTask("t4", isDeleted = true)))
        )
        assertTrue(two.showUnassignedBanner)
        assertEquals(2, two.unassignedPending)
        assertEquals("2 auto-generated tasks have no assigned housekeeper yet (no active room assignment found).", two.bannerText)
    }

    @Test fun bannerTextForOneTask() {
        assertEquals("1 auto-generated task has no assigned housekeeper yet (no active room assignment found).", unassignedBannerText(1))
    }

    @Test fun loadingRoomsMeansLoading() {
        assertTrue(buildOverviewState(Resource.Loading, Resource.Success(emptyList())).loading)
    }

    @Test fun roomErrorShowsTheErrorStateWithDetail() {
        val state = buildOverviewState(Resource.Error("x", IllegalStateException("Missing permissions")), Resource.Success(emptyList()))
        assertTrue(state.failed)
        assertFalse(state.loading)
        assertEquals("Missing permissions", state.errorDetail)
    }

    @Test fun roomErrorWithoutDetailHasNoDetail() {
        val state = buildOverviewState(Resource.Error(MSG_ROOMS_LOAD_FAILED), Resource.Success(emptyList()))
        assertNull(state.errorDetail)
    }

    @Test fun taskErrorDoesNotBreakThePage() {
        val state = buildOverviewState(Resource.Success(rooms), Resource.Error("nope"))
        assertFalse(state.failed)
        assertEquals(0, state.unassignedPending)
    }

    @Test fun onlyManagementRolesMayAct() {
        assertTrue(canActOnOverview(Role.SUPER_ADMIN))
        assertTrue(canActOnOverview(Role.MANAGER))
        assertTrue(canActOnOverview(Role.OPERATIONS_MANAGER))
        assertFalse(canActOnOverview(Role.HOUSEKEEPING))
        assertFalse(canActOnOverview(Role.RECEPTIONIST))
    }
}
