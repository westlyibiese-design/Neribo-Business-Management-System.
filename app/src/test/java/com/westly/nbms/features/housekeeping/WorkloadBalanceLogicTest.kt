package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorkloadBalanceLogicTest {

    private val today = "2026-10-10"

    private fun shift(id: String, name: String = id, status: String = "scheduled") = ShiftRef(id, name, status)

    @Test fun peopleOnDutyStartAtZero() {
        val state = computeWorkload(today, emptyList(), listOf(shift("a", "Ada"), shift("b", "Bola")))
        assertEquals(listOf("Ada", "Bola"), state.rows.map { it.name })
        assertTrue(state.rows.all { it.rooms == 0 && it.load == 0.0 })
        assertEquals(2, state.onShift)
    }

    @Test fun onlyScheduledShiftsCountAndEachPersonOnce() {
        val state = computeWorkload(today, emptyList(), listOf(shift("a"), shift("a"), shift("b", status = "cancelled"), shift("c", status = "completed")))
        assertEquals(1, state.onShift)
        assertEquals(listOf("a"), state.rows.map { it.housekeeperId })
    }

    @Test fun loadIsTheSumOfStoredWeights() {
        val tasks = listOf(
            overviewTask("t1", assignedTo = "a", assignedToName = "Ada", weight = 2.0),
            overviewTask("t2", assignedTo = "a", assignedToName = "Ada", weight = 1.5)
        )
        val row = computeWorkload(today, tasks, emptyList()).rows.single()
        assertEquals(2, row.rooms)
        assertEquals(3.5, row.load, 0.0001)
        assertEquals("Ada", row.name)
    }

    @Test fun missingWeightFallsBackToTheComputedWeight() {
        val tasks = listOf(overviewTask("t1", assignedTo = "a", type = "checkout_cleaning", priority = "high"))
        assertEquals(2.5, computeWorkload(today, tasks, emptyList()).rows.single().load, 0.0001)
    }

    @Test fun rowsAreSortedByLoadHeaviestFirst() {
        val tasks = listOf(
            overviewTask("t1", assignedTo = "light", assignedToName = "Light", weight = 1.0),
            overviewTask("t2", assignedTo = "heavy", assignedToName = "Heavy", weight = 3.0),
            overviewTask("t3", assignedTo = "mid", assignedToName = "Mid", weight = 2.0)
        )
        assertEquals(listOf("Heavy", "Mid", "Light"), computeWorkload(today, tasks, emptyList()).rows.map { it.name })
    }

    @Test fun heavierDayWhenMoreThanQuarterAboveAverage() {
        val tasks = listOf(
            overviewTask("t1", assignedTo = "a", weight = 4.0),
            overviewTask("t2", assignedTo = "b", weight = 1.0),
            overviewTask("t3", assignedTo = "c", weight = 1.0)
        )
        val rows = computeWorkload(today, tasks, emptyList()).rows
        assertTrue(rows.first { it.housekeeperId == "a" }.heavier)
        assertFalse(rows.first { it.housekeeperId == "b" }.heavier)
    }

    @Test fun exactlyQuarterAboveAverageIsNotHeavier() {
        // average 2.0, threshold 2.5
        val tasks = listOf(overviewTask("t1", assignedTo = "a", weight = 2.5), overviewTask("t2", assignedTo = "b", weight = 1.5))
        assertFalse(computeWorkload(today, tasks, emptyList()).rows.any { it.heavier })
    }

    @Test fun aLoneHousekeeperIsNeverHeavier() {
        assertFalse(computeWorkload(today, listOf(overviewTask("t1", assignedTo = "a", weight = 9.0)), emptyList()).rows.single().heavier)
    }

    @Test fun barWidthIsLoadOverMaxWithAThreePercentMinimum() {
        val tasks = listOf(overviewTask("t1", assignedTo = "a", weight = 4.0), overviewTask("t2", assignedTo = "b", weight = 2.0))
        val rows = computeWorkload(today, tasks, listOf(shift("c"))).rows
        assertEquals(1.0f, rows.first { it.housekeeperId == "a" }.barFraction, 0.0001f)
        assertEquals(0.5f, rows.first { it.housekeeperId == "b" }.barFraction, 0.0001f)
        assertEquals(0.03f, rows.first { it.housekeeperId == "c" }.barFraction, 0.0001f)
    }

    @Test fun barWidthWithNoLoadAtAllIsTheMinimum() {
        assertEquals(0.03f, computeWorkload(today, emptyList(), listOf(shift("a"))).rows.single().barFraction, 0.0001f)
    }

    @Test fun onlyTodaysOpenTasksCount() {
        val tasks = listOf(
            overviewTask("open1", assignedTo = "a", weight = 1.0),
            overviewTask("open2", status = "in_progress", assignedTo = "a", weight = 1.0),
            overviewTask("done", status = "completed", assignedTo = "a", weight = 1.0),
            overviewTask("skipped", status = "skipped", assignedTo = "a", weight = 1.0),
            overviewTask("yesterday", dayKey = "2026-10-09", assignedTo = "a", weight = 1.0),
            overviewTask("deleted", isDeleted = true, assignedTo = "a", weight = 1.0)
        )
        assertEquals(2, computeWorkload(today, tasks, emptyList()).rows.single().rooms)
    }

    @Test fun tasksWithoutAnOwnerAreCountedAsUnassigned() {
        val tasks = listOf(overviewTask("t1"), overviewTask("t2", assignedTo = ""), overviewTask("t3", assignedTo = "a"))
        val state = computeWorkload(today, tasks, emptyList())
        assertEquals(2, state.unassigned)
        assertEquals("2 unassigned", state.unassignedText)
    }

    @Test fun cardIsHiddenWhileLoadingAndWhenThereIsNothingToShow() {
        assertFalse(WorkloadCardState(loading = true).visible)
        assertFalse(computeWorkload(today, emptyList(), emptyList()).visible)
        assertTrue(computeWorkload(today, emptyList(), listOf(shift("a"))).visible)
        assertTrue(computeWorkload(today, listOf(overviewTask("t1")), emptyList()).visible)
    }

    @Test fun emptyRosterWithTasksShowsTheNoStaffText() {
        val state = computeWorkload(today, listOf(overviewTask("t1")), emptyList())
        assertTrue(state.showEmptyRoster)
        assertEquals("No housekeeping staff on shift today.", WORKLOAD_EMPTY_ROSTER)
        assertEquals("0 on shift today", state.onShiftText)
    }

    @Test fun rowTextShowsRoomsAndCredits() {
        val tasks = listOf(
            overviewTask("t1", assignedTo = "a", weight = 2.0), overviewTask("t2", assignedTo = "a", weight = 1.0),
            overviewTask("t3", assignedTo = "a", weight = 1.5), overviewTask("t4", assignedTo = "b", weight = 2.0)
        )
        val rows = computeWorkload(today, tasks, emptyList()).rows
        assertEquals("3 rooms · 4.5 credits", rows.first { it.housekeeperId == "a" }.summary)
        assertEquals("1 room · 2.0 credits", rows.first { it.housekeeperId == "b" }.summary)
    }

    @Test fun onShiftPersonKeepsTheirShiftNameWhenTasksAreAssignedToThem() {
        val tasks = listOf(overviewTask("t1", assignedTo = "a", assignedToName = "Ada (task)", weight = 1.0))
        assertEquals("Ada", computeWorkload(today, tasks, listOf(shift("a", "Ada"))).rows.single().name)
    }

    // ---- Resource handling ------------------------------------------------------------------

    @Test fun loadingTasksOrShiftsMeansLoading() {
        assertTrue(buildWorkloadState(today, Resource.Loading, Resource.Success(emptyList())).loading)
        assertTrue(buildWorkloadState(today, Resource.Success(emptyList()), Resource.Loading).loading)
    }

    @Test fun unreadableTasksHideTheCard() {
        assertFalse(buildWorkloadState(today, Resource.Error("x"), Resource.Success(emptyList())).visible)
    }

    @Test fun unreadableShiftsStillShowTheTaskWork() {
        val state = buildWorkloadState(today, Resource.Success(listOf(overviewTask("t1", assignedTo = "a", weight = 1.0))), Resource.Error("x"))
        assertTrue(state.visible)
        assertEquals(1, state.rows.size)
    }

    // ---- Time zone --------------------------------------------------------------------------

    private val lateEvening = Instant.parse("2026-10-09T23:30:00Z")

    @Test fun todayKeyUsesTheBusinessTimeZone() {
        assertEquals("2026-10-10", dateKeyInZone(lateEvening, zoneOfName("Africa/Lagos")))
        assertEquals("2026-10-09", dateKeyInZone(Instant.parse("2026-10-10T02:00:00Z"), zoneOfName("America/New_York")))
    }

    @Test fun missingOrBrokenTimeZoneFallsBackToLagos() {
        assertEquals(ZoneId.of("Africa/Lagos"), zoneOfName(null))
        assertEquals(ZoneId.of("Africa/Lagos"), zoneOfName(""))
        assertEquals(ZoneId.of("Africa/Lagos"), zoneOfName("Mars/Olympus"))
    }
}
