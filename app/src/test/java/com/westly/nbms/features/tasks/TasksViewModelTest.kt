package com.westly.nbms.features.tasks

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val store = FakeTasksStore()
    private val audit = FakeTaskAudit()
    private val toast = ToastController()
    private val zone = ZoneId.of("Africa/Lagos")

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(role: Role = Role.OPERATIONS_MANAGER, now: Instant = TASK_NOW): TasksViewModel {
        val session = FakeTasksSession(role = role)
        return TasksViewModel(TasksRepository(store, session), audit, toast, session) { now }
    }

    private fun TestScope.watch(vm: TasksViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
    }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun load(vararg tasks: StaffTask) { store.all.value = Resource.Success(tasks.toList()) }

    private fun TasksViewModel.ready(): TasksView.Ready = view.value as TasksView.Ready

    private val hourAgo = TASK_NOW.minus(Duration.ofHours(1))
    private val hourAhead = TASK_NOW.plus(Duration.ofHours(1))

    // ---- Assign button flag ----

    @Test fun onlySuperAdminAndOperationsManagerMayAssign() {
        assertTrue(vm(Role.SUPER_ADMIN).canAssign)
        assertTrue(vm(Role.OPERATIONS_MANAGER).canAssign)
        assertFalse(vm(Role.MANAGER).canAssign)
        assertFalse(vm(Role.RECEPTIONIST).canAssign)
        assertFalse(canAssignTasks(null))
    }

    // ---- states ----

    @Test fun startsLoadingThenShowsTasks() = runTest {
        store.all.value = Resource.Loading
        val vm = vm()
        watch(vm)
        assertEquals(TasksView.Loading, vm.view.value)
        load(taskOf("a"))
        assertEquals(1, vm.ready().rows.size)
    }

    @Test fun loadFailureShowsTheErrorMessage() = runTest {
        store.all.value = Resource.Error("boom")
        val vm = vm()
        watch(vm)
        assertEquals(TasksView.Error("We couldn't load tasks."), vm.view.value)
    }

    @Test fun emptyListIsReadyWithNoRows() = runTest {
        load()
        val vm = vm()
        watch(vm)
        assertTrue(vm.ready().rows.isEmpty())
    }

    // ---- stats ----

    @Test fun statsCountActiveOverdueAndCompletedToday() = runTest {
        load(
            taskOf("pending"),
            taskOf("overdue", dueAt = hourAgo),
            taskOf("inprog", status = TaskStatus.IN_PROGRESS),
            taskOf("doneToday", status = TaskStatus.COMPLETED, completedAt = TASK_NOW.minus(Duration.ofMinutes(30))),
            taskOf("doneOld", status = TaskStatus.COMPLETED, completedAt = TASK_NOW.minus(Duration.ofDays(3))),
            taskOf("cancelled", status = TaskStatus.CANCELLED, dueAt = hourAgo),
            taskOf("deleted", dueAt = hourAgo, deleted = true)
        )
        val vm = vm()
        watch(vm)
        assertEquals(TaskStats(active = 3, overdue = 1, completedToday = 1), vm.ready().stats)
    }

    @Test fun statsIgnoreTheFilters() = runTest {
        load(taskOf("a", title = "Mop"), taskOf("b", title = "Paint"))
        val vm = vm()
        watch(vm)
        vm.setSearch("mop")
        assertEquals(1, vm.ready().rows.size)
        assertEquals(2, vm.ready().stats.active)
    }

    // ---- filters ----

    @Test fun defaultFilterIsActiveAllTypes() = runTest {
        val vm = vm()
        assertEquals(TaskStatusFilter.ACTIVE, vm.filters.value.status)
        assertEquals(null, vm.filters.value.type)
        assertEquals("", vm.filters.value.search)
    }

    @Test fun activeFilterHidesCompletedAndCancelledAndDeleted() = runTest {
        load(
            taskOf("a"), taskOf("b", status = TaskStatus.COMPLETED),
            taskOf("c", status = TaskStatus.CANCELLED), taskOf("d", deleted = true)
        )
        val vm = vm()
        watch(vm)
        assertEquals(listOf("a"), vm.ready().rows.map { it.task.id })
        vm.setStatus(TaskStatusFilter.ALL)
        assertEquals(setOf("a", "b", "c"), vm.ready().rows.map { it.task.id }.toSet())
        vm.setStatus(TaskStatusFilter.COMPLETED)
        assertEquals(listOf("b"), vm.ready().rows.map { it.task.id })
        vm.setStatus(TaskStatusFilter.CANCELLED)
        assertEquals(listOf("c"), vm.ready().rows.map { it.task.id })
    }

    @Test fun typeAndSearchFiltersApply() = runTest {
        load(
            taskOf("a", title = "Clean 204", type = TaskType.HOUSEKEEPING, names = listOf("Ada")),
            taskOf("b", title = "Fix tap", type = TaskType.MAINTENANCE, names = listOf("Bola")),
            taskOf("c", title = "Wash sheets", type = TaskType.LAUNDRY, names = listOf("Chidi"))
        )
        val vm = vm()
        watch(vm)
        vm.setType(TaskType.MAINTENANCE)
        assertEquals(listOf("b"), vm.ready().rows.map { it.task.id })
        vm.setType(null)
        vm.setSearch("CLEAN")
        assertEquals(listOf("a"), vm.ready().rows.map { it.task.id })
        vm.setSearch("chidi")
        assertEquals(listOf("c"), vm.ready().rows.map { it.task.id })
        vm.setSearch("")
        assertEquals(3, vm.ready().rows.size)
    }

    // ---- ordering ----

    @Test fun overdueFirstThenNewestFirst() = runTest {
        val t = TASK_NOW
        load(
            taskOf("oldNormal", createdAt = t.minus(Duration.ofDays(3))),
            taskOf("newNormal", createdAt = t.minus(Duration.ofHours(2))),
            taskOf("oldOverdue", createdAt = t.minus(Duration.ofDays(2)), dueAt = hourAgo),
            taskOf("newOverdue", createdAt = t.minus(Duration.ofHours(5)), dueAt = hourAgo)
        )
        val vm = vm()
        watch(vm)
        assertEquals(
            listOf("newOverdue", "oldOverdue", "newNormal", "oldNormal"),
            vm.ready().rows.map { it.task.id }
        )
        assertEquals(listOf(true, true, false, false), vm.ready().rows.map { it.overdue })
    }

    @Test fun aTaskTurnsOverdueAsTheClockMovesOn() = runTest {
        var now = TASK_NOW
        load(taskOf("a", dueAt = TASK_NOW.plus(Duration.ofMinutes(30))))
        val session = FakeTasksSession()
        val vm = TasksViewModel(TasksRepository(store, session), audit, toast, session) { now }
        watch(vm)
        assertFalse(vm.ready().rows.single().overdue)
        now = TASK_NOW.plus(Duration.ofMinutes(45))
        advanceTimeBy(TASKS_CLOCK_TICK_MS + 1)
        runCurrent()
        assertTrue(vm.ready().rows.single().overdue)
    }

    // ---- Reassign / Cancel offered ----

    @Test fun reassignAndCancelAreOfferedOnlyOnActiveTasks() {
        assertTrue(canManageTask(taskOf("a", status = TaskStatus.PENDING)))
        assertTrue(canManageTask(taskOf("a", status = TaskStatus.ACCEPTED)))
        assertTrue(canManageTask(taskOf("a", status = TaskStatus.IN_PROGRESS)))
        assertFalse(canManageTask(taskOf("a", status = TaskStatus.COMPLETED)))
        assertFalse(canManageTask(taskOf("a", status = TaskStatus.CANCELLED)))
    }

    // ---- Cancel flow ----

    @Test fun cancelWritesAuditsAndToasts() = runTest {
        val events = toasts()
        val vm = vm()
        vm.cancel(taskOf("t1", title = "Clean 204", status = TaskStatus.IN_PROGRESS))
        runCurrent()

        val (id, fields) = store.updates.single()
        assertEquals("t1", id)
        assertEquals("cancelled", fields["status"])
        assertTrue(fields.containsKey("updatedAt"))

        val entry = audit.entries.single()
        assertEquals("task_cancelled", entry.action)
        assertEquals("tasks", entry.collection)
        assertEquals("t1", entry.id)
        assertEquals(mapOf<String, Any?>("status" to "in_progress"), entry.previous)
        assertEquals(mapOf<String, Any?>("status" to "cancelled"), entry.new)

        val toast = events.single()
        assertEquals("Task Cancelled", toast.title)
        assertEquals(ToastType.Success, toast.type)
        assertTrue(vm.busyIds.value.isEmpty())
    }

    @Test fun cancelFailureShowsErrorToastAndNoAudit() = runTest {
        val events = toasts()
        store.failWith = IllegalStateException("Permission denied")
        val vm = vm()
        vm.cancel(taskOf("t1"))
        runCurrent()

        assertTrue(audit.entries.isEmpty())
        val toast = events.single()
        assertEquals("Error", toast.title)
        assertEquals("Permission denied", toast.message)
        assertEquals(ToastType.Error, toast.type)
        assertTrue(vm.busyIds.value.isEmpty())
    }

    @Test fun cancelIsBusyWhileRunningAndTimesOut() = runTest {
        val events = toasts()
        store.hang = true
        val vm = vm()
        vm.cancel(taskOf("t1"))
        runCurrent()
        assertEquals(setOf("t1"), vm.busyIds.value)

        advanceTimeBy(TASKS_CANCEL_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(vm.busyIds.value.isEmpty())
        assertEquals("Error", events.single().title)
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aSecondTapWhileBusyDoesNothing() = runTest {
        store.hang = true
        val vm = vm()
        val task = taskOf("t1")
        vm.cancel(task)
        vm.cancel(task)
        runCurrent()
        store.hang = false
        assertTrue(store.updates.isEmpty())
        assertEquals(setOf("t1"), vm.busyIds.value)
    }

    @Test fun finishedTasksCannotBeCancelled() = runTest {
        val vm = vm()
        vm.cancel(taskOf("t1", status = TaskStatus.COMPLETED))
        vm.cancel(taskOf("t2", status = TaskStatus.CANCELLED))
        runCurrent()
        assertTrue(store.updates.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun anAuditFailureDoesNotUndoACancel() = runTest {
        val events = toasts()
        audit.fail = true
        val vm = vm()
        vm.cancel(taskOf("t1"))
        runCurrent()
        assertEquals(1, store.updates.size)
        assertEquals("Task Cancelled", events.single().title)
        assertTrue(vm.busyIds.value.isEmpty())
    }

    // ---- pure helpers ----

    @Test fun labelsAreExact() {
        assertEquals("Active", taskStatusFilterLabel(TaskStatusFilter.ACTIVE))
        assertEquals("All Status", taskStatusFilterLabel(TaskStatusFilter.ALL))
        assertEquals("In Progress", taskStatusFilterLabel(TaskStatusFilter.IN_PROGRESS))
        assertEquals("All Types", taskTypeFilterLabel(null))
        assertEquals("Room Booking", taskTypeFilterLabel(TaskType.BOOKING))
    }

    @Test fun viewOfMapsEachResourceState() {
        assertEquals(TasksView.Loading, tasksViewOf(Resource.Loading, TasksFilters(), TASK_NOW, zone))
        assertEquals(TasksView.Error("We couldn't load tasks."), tasksViewOf(Resource.Error("x"), TasksFilters(), TASK_NOW, zone))
        assertNotNull(tasksViewOf(Resource.Success(emptyList()), TasksFilters(), TASK_NOW, zone) as? TasksView.Ready)
    }
}
