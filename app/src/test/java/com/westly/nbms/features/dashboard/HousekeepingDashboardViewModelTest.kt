package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.housekeeping.TaskPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HousekeepingDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun task(room: String, priority: String?, status: String? = "pending") =
        HkTaskRow(roomNumber = room, priority = priority, status = status)

    private fun state(queue: List<HkTaskRow>, rooms: Int = 0) =
        housekeepingStateOf(Resource.Success(queue), Resource.Success(rooms), DASH_NOW)

    @Test fun queueHighPriorityAndMyRoomsAreCounted() {
        val s = state(
            listOf(
                task("101", "urgent"), task("102", "high", "in_progress"), task("103", "medium"), task("104", "low"),
                task("105", "urgent", "completed")
            ),
            rooms = 4
        )
        assertEquals(4, s.queue)          // completed task is not in the queue
        assertEquals(2, s.highPriority)   // urgent + high
        assertEquals(4, s.myRooms)
    }

    @Test fun theQueueGridShowsAtMostNineRooms() {
        val s = state((1..12).map { task("2%02d".format(it), "medium") })
        assertEquals(12, s.queue)
        assertEquals(HK_QUEUE_CARD_COUNT, s.tasks.size)
        assertEquals(9, s.tasks.size)
        assertEquals("201", s.tasks.first().roomNumber)
        assertEquals("209", s.tasks.last().roomNumber)
    }

    @Test fun anEmptyQueueShowsTheEmptyState() {
        val s = state(emptyList(), rooms = 2)
        assertEquals(0, s.queue)
        assertEquals(0, s.highPriority)
        assertTrue(s.tasks.isEmpty())
        assertEquals(2, s.myRooms)
    }

    @Test fun tasksNotInTheQueueNeverAppearInTheGrid() {
        val s = state(listOf(task("101", "high", "completed"), task("102", "low", "skipped"), task("103", "low", "pending")))
        assertEquals(listOf("103"), s.tasks.map { it.roomNumber })
    }

    @Test fun theStateCarriesNoHotelWideRoomCounts() {
        // The only room figure is the housekeeper's own assignments.
        val fields = HousekeepingState::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.contains("available", ignoreCase = true) || it.contains("occupied", ignoreCase = true) })
        assertTrue("myRooms" in fields)
    }

    @Test fun priorityBadgeUsesPhase23Priorities() {
        assertEquals(TaskPriority.URGENT, priorityOf("urgent"))
        assertEquals(TaskPriority.HIGH, priorityOf("high"))
        assertEquals(TaskPriority.MEDIUM, priorityOf("medium"))
        assertEquals(TaskPriority.LOW, priorityOf("low"))
        assertNull(priorityOf(null))
        assertNull(priorityOf("whatever"))
        assertEquals("Room 101", roomLabel("101"))
        assertEquals("Room —", roomLabel(null))
    }

    @Test fun loadingAndFailureStates() {
        val loading = housekeepingStateOf(Resource.Loading, Resource.Success(0), DASH_NOW)
        assertTrue(loading.loading)
        val failedQueue = housekeepingStateOf(Resource.Error("x"), Resource.Success(0), DASH_NOW)
        assertTrue(failedQueue.failed)
        assertFalse(failedQueue.loading)
        val failedRooms = housekeepingStateOf(Resource.Success(emptyList()), Resource.Error("x"), DASH_NOW)
        assertTrue(failedRooms.failed)
    }

    @Test fun viewModelAsksOnlyForItsOwnQueueAndOpensTheRoutes() = runTest {
        val asked = mutableListOf<String>()
        val navigator = RecordingNavigator()
        val vm = HousekeepingDashboardViewModel(
            flows = { uid ->
                asked += uid
                HousekeepingFlows(flowOf(Resource.Success(listOf(task("101", "high")))), flowOf(Resource.Success(3)))
            },
            uid = "hk-1", navigator = navigator, clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(listOf("hk-1"), asked)
        assertEquals(1, vm.state.value.queue)
        assertEquals(1, vm.state.value.highPriority)
        assertEquals(3, vm.state.value.myRooms)
        vm.open("housekeeping")
        vm.open("maintenance")
        assertEquals(listOf("housekeeping", "maintenance"), navigator.opened)
    }

    @Test fun aNewTaskShowsWithoutARefresh() = runTest {
        val queue = MutableStateFlow<Resource<List<HkTaskRow>>>(Resource.Success(emptyList()))
        val vm = HousekeepingDashboardViewModel(
            flows = { HousekeepingFlows(queue, flowOf(Resource.Success(0))) },
            uid = "hk-1", navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(0, vm.state.value.queue)
        queue.value = Resource.Success(listOf(task("305", "urgent")))
        assertEquals(1, vm.state.value.queue)
        assertEquals(1, vm.state.value.highPriority)
    }

    @Test fun retryCollectsTheListsAgain() = runTest {
        var collected = 0
        val vm = HousekeepingDashboardViewModel(
            flows = { collected++; HousekeepingFlows(flowOf(Resource.Error("x")), flowOf(Resource.Success(0))) },
            uid = "hk-1", navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        vm.retry()
        assertEquals(2, collected)
    }
}
