package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WorkloadBalanceViewModelTest {

    // 23:30 UTC on 9 Oct is already 10 Oct in Lagos but still 9 Oct in New York.
    private val clock = { Instant.parse("2026-10-09T23:30:00Z") }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun noTimeZoneInSettingsMeansAfricaLagos() = runTest {
        val source = FakeWorkloadSource(timezoneName = null)
        val vm = WorkloadBalanceViewModel(source, clock)
        vm.state.first()
        assertEquals(listOf("2026-10-10"), source.requestedKeys)
    }

    @Test fun settingsTimeZoneDecidesTodayKey() = runTest {
        val source = FakeWorkloadSource(timezoneName = "America/New_York")
        val vm = WorkloadBalanceViewModel(source, clock)
        vm.state.first()
        assertEquals(listOf("2026-10-09"), source.requestedKeys)
    }

    @Test fun cardIsHiddenWhileLoadingThenShowsRows() = runTest {
        val source = FakeWorkloadSource()
        val vm = WorkloadBalanceViewModel(source, clock)
        assertFalse(vm.state.first().visible)

        source.shifts.value = Resource.Success(listOf(ShiftRef("a", "Ada", "scheduled")))
        source.tasks.value = Resource.Success(listOf(overviewTask("t1", assignedTo = "a", weight = 2.0, dayKey = "2026-10-10")))
        val state = vm.state.first { it.visible }
        assertEquals("Ada", state.rows.single().name)
        assertEquals("1 room · 2.0 credits", state.rows.single().summary)
        assertEquals("1 on shift today", state.onShiftText)
    }

    @Test fun cardStaysHiddenWhenThereIsNothingToShow() = runTest {
        val source = FakeWorkloadSource()
        val vm = WorkloadBalanceViewModel(source, clock)
        source.tasks.value = Resource.Success(emptyList())
        val state = vm.state.first { !it.loading }
        assertFalse(state.visible)
        assertTrue(state.rows.isEmpty())
    }

    @Test fun tasksFromOtherDaysAreIgnored() = runTest {
        val source = FakeWorkloadSource()
        val vm = WorkloadBalanceViewModel(source, clock)
        source.tasks.value = Resource.Success(listOf(overviewTask("old", assignedTo = "a", dayKey = "2026-10-09")))
        assertFalse(vm.state.first { !it.loading }.visible)
    }
}
