package com.westly.nbms.features.tasks

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

private class FakeShiftsSource : MyShiftsSource {
    val flow = MutableStateFlow<Resource<List<MyShiftDoc>>>(Resource.Success(emptyList()))
    val queries = mutableListOf<String>()
    override fun observe(uid: String): Flow<Resource<List<MyShiftDoc>>> { queries += uid; return flow }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MyTasksViewModelTest {

    private val store = FakeTasksStore()
    private val shifts = FakeShiftsSource()
    private val audit = FakeTaskAudit()
    private val notifier = FakeTaskNotifier()
    private val toast = ToastController()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): MyTasksViewModel {
        val session = FakeTasksSession(role = Role.HOUSEKEEPING, uid = "me", name = "Hana")
        return MyTasksViewModel(TasksRepository(store, session), shifts, audit, notifier, toast, session) { TASK_NOW }
    }

    private fun TestScope.watch(vm: MyTasksViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
    }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun load(vararg tasks: StaffTask) { store.all.value = Resource.Success(tasks.toList()) }

    private val hourAgo = TASK_NOW.minus(Duration.ofHours(1))

    @Test fun asksForMyTasksOnly() = runTest {
        val vm = vm(); watch(vm)
        assertEquals(listOf("me"), store.mineQueries.distinct())
        assertEquals(listOf("me"), shifts.queries.distinct())
    }

    @Test fun showsLoadingErrorAndReady() = runTest {
        store.all.value = Resource.Loading
        val vm = vm(); watch(vm)
        assertEquals(MyTasksView.Loading, vm.view.value)
        store.all.value = Resource.Error("boom")
        assertEquals(MyTasksView.Error("We couldn't load your tasks."), vm.view.value)
        load(taskOf("a", createdAt = hourAgo), taskOf("b", status = TaskStatus.COMPLETED, completedAt = hourAgo))
        val ready = vm.view.value as MyTasksView.Ready
        assertEquals(listOf("a"), ready.active.map { it.task.id })
        assertEquals(listOf("b"), ready.finished.map { it.id })
    }

    @Test fun overdueIsFlagged() = runTest {
        load(taskOf("late", dueAt = hourAgo, createdAt = hourAgo))
        val vm = vm(); watch(vm)
        assertTrue((vm.view.value as MyTasksView.Ready).active.single().overdue)
    }

    @Test fun shiftsCardStaysEmptyWithoutShiftsAndOnShiftError() = runTest {
        load(taskOf("a"))
        val vm = vm(); watch(vm)
        assertTrue((vm.view.value as MyTasksView.Ready).shifts.isEmpty())
        shifts.flow.value = Resource.Error("denied")
        assertTrue((vm.view.value as MyTasksView.Ready).shifts.isEmpty())
    }

    @Test fun shiftsShowWhenTheyExist() = runTest {
        load(taskOf("a"))
        shifts.flow.value = Resource.Success(
            listOf(MyShiftDoc(id = "s1", staffId = "me", date = "2026-10-09", startTime = "08:00", endTime = "16:00", label = "Morning"))
        )
        val vm = vm(); watch(vm)
        assertEquals("Today", (vm.view.value as MyTasksView.Ready).shifts.single().dayLabel)
    }

    @Test fun acceptWritesAuditsAndClearsBusy() = runTest {
        load(taskOf("a", status = TaskStatus.PENDING))
        val vm = vm(); watch(vm)
        vm.accept(taskOf("a", status = TaskStatus.PENDING))
        val (id, fields) = store.updates.single()
        assertEquals("a", id)
        assertEquals("accepted", fields["status"])
        assertEquals("me", fields["acceptedBy"])
        assertEquals("Hana", fields["acceptedByName"])
        assertEquals("task_accepted", audit.entries.single().action)
        assertEquals("tasks", audit.entries.single().collection)
        assertTrue(vm.busy.value.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun startWritesInProgressAndAudits() = runTest {
        val vm = vm(); watch(vm)
        vm.start(taskOf("a", status = TaskStatus.ACCEPTED))
        assertEquals("in_progress", store.updates.single().second["status"])
        assertEquals("task_started", audit.entries.single().action)
    }

    @Test fun completeWritesAuditsNotifiesAndToasts() = runTest {
        val events = toasts()
        val vm = vm(); watch(vm)
        vm.complete(taskOf("a", title = "Clean 204", status = TaskStatus.IN_PROGRESS).copy(assignedBy = "boss1"))
        assertEquals("completed", store.updates.single().second["status"])
        assertTrue(store.updates.single().second.containsKey("completedAt"))
        assertEquals("task_completed", audit.entries.single().action)
        val call = notifier.calls.single()
        assertEquals("Task Completed", call.title)
        assertTrue(call.message.contains("Hana") && call.message.contains("Clean 204"))
        assertEquals(listOf("boss1"), call.userIds)
        assertEquals("Task Completed", events.single().title)
        assertEquals("Clean 204", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
    }

    @Test fun failureShowsErrorToastAndClearsBusy() = runTest {
        val events = toasts()
        store.failWith = IllegalStateException("No permission")
        val vm = vm(); watch(vm)
        vm.accept(taskOf("a", status = TaskStatus.PENDING))
        assertEquals("Error", events.single().title)
        assertEquals("No permission", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertTrue(vm.busy.value.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun auditOrNotifierFailureNeverUndoesTheAction() = runTest {
        val events = toasts()
        audit.fail = true
        notifier.fail = true
        val vm = vm(); watch(vm)
        vm.complete(taskOf("a", status = TaskStatus.ACCEPTED))
        assertEquals(1, store.updates.size)
        assertEquals("Task Completed", events.single().title)
        assertTrue(vm.busy.value.isEmpty())
    }

    @Test fun staleTapsAreIgnored() = runTest {
        val vm = vm(); watch(vm)
        vm.complete(taskOf("a", status = TaskStatus.PENDING))
        vm.accept(taskOf("b", status = TaskStatus.COMPLETED))
        assertTrue(store.updates.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun tapWhileBusyIsIgnored() = runTest {
        store.hang = true
        val vm = vm(); watch(vm)
        val task = taskOf("a", status = TaskStatus.ACCEPTED)
        vm.start(task)
        assertEquals(MyTaskAction.START, vm.busy.value["a"])
        vm.complete(task)
        assertEquals(MyTaskAction.START, vm.busy.value["a"])
        assertTrue(store.updates.isEmpty())
    }
}
