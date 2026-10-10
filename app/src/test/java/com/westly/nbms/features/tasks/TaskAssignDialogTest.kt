package com.westly.nbms.features.tasks

import com.google.firebase.Timestamp
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.NotificationType
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TaskAssignDialogTest {

    private val staff = listOf(
        TaskStaffUser("u-ada", "Ada", "receptionist"),
        TaskStaffUser("u-bola", "Bola", "waiter"),
        TaskStaffUser("u-chidi", "Chidi", "housekeeping")
    )
    private val store = FakeTasksStore()
    private val audit = FakeTaskAudit()
    private val notifier = FakeTaskNotifier()
    private val toast = ToastController()
    private var session = FakeTasksSession(uid = "me", name = "Boss")

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(session: FakeTasksSession = this.session) =
        TaskAssignViewModel(FakeTaskStaffSource(staff), TasksRepository(store, session), audit, notifier, toast, session)

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun form(
        title: String = "Fix tap", selected: Set<String> = setOf("u-ada"), type: TaskType = TaskType.OTHER,
        priority: TaskPriority = TaskPriority.MEDIUM, description: String = "", time: LocalTime? = null
    ) = TaskAssignForm(type = type, priority = priority, title = title, description = description, dueTime = time, selectedIds = selected)

    // ---- pure form rules ----

    @Test fun titleIsCheckedBeforeStaff() {
        assertEquals(TaskAssignIssue.MISSING_TITLE, validateTaskAssign(form(title = "  ", selected = emptySet()), reassign = false))
        assertEquals(TaskAssignIssue.NO_STAFF, validateTaskAssign(form(title = "ok", selected = emptySet()), reassign = false))
        assertNull(validateTaskAssign(form(), reassign = false))
    }

    @Test fun reassignModeSkipsTheTitleCheck() {
        assertEquals(TaskAssignIssue.NO_STAFF, validateTaskAssign(form(title = "", selected = emptySet()), reassign = true))
        assertNull(validateTaskAssign(form(title = ""), reassign = true))
    }

    @Test fun issueTextsAreExact() {
        assertEquals("Missing title", TaskAssignIssue.MISSING_TITLE.title)
        assertEquals("Give the task a short title.", TaskAssignIssue.MISSING_TITLE.message)
        assertEquals("No staff selected", TaskAssignIssue.NO_STAFF.title)
        assertEquals("Select at least one staff member.", TaskAssignIssue.NO_STAFF.message)
    }

    @Test fun openingStartsFreshWithDefaults() {
        val f = newTaskAssignForm(null)
        assertEquals(TaskType.OTHER, f.type); assertEquals(TaskPriority.MEDIUM, f.priority)
        assertEquals("", f.title); assertEquals("", f.description); assertNull(f.dueTime)
        assertTrue(f.selectedIds.isEmpty()); assertTrue(f.suggestedOnly); assertEquals("", f.search)
    }

    @Test fun openingWithDefaultsPrefillsTypeTitleAndDescription() {
        val f = newTaskAssignForm(TaskDefaults(type = TaskType.LAUNDRY, title = "Collect laundry", description = "Room 5"))
        assertEquals(TaskType.LAUNDRY, f.type); assertEquals("Collect laundry", f.title); assertEquals("Room 5", f.description)
        assertTrue(f.selectedIds.isEmpty())
    }

    @Test fun changingTheTypeClearsTheSelectedStaff() {
        val f = form(selected = setOf("u-ada", "u-bola"), type = TaskType.BOOKING)
        assertTrue(f.withType(TaskType.DRINK_ORDER).selectedIds.isEmpty())
        assertEquals(TaskType.DRINK_ORDER, f.withType(TaskType.DRINK_ORDER).type)
        assertEquals("picking the same type keeps the staff", f.selectedIds, f.withType(TaskType.BOOKING).selectedIds)
    }

    @Test fun onlyActiveNotDeletedStaffAreKeptAndSorted() {
        val docs = listOf(
            TaskUserDoc("2", "Zed", "waiter", "active", false), TaskUserDoc("1", "amy", "driver", "active", false),
            TaskUserDoc("3", "Sus", "driver", "suspended", false), TaskUserDoc("4", "Gone", "driver", "active", true)
        )
        assertEquals(listOf("amy", "Zed"), activeTaskStaff(docs).map { it.name })
        assertEquals(TaskStaffUser("1", "amy", "driver"), activeTaskStaff(docs).first())
    }

    @Test fun userMirrorDocsReadTolerantly() {
        val d = TaskUserDoc()
        assertEquals("active", d.status); assertFalse(d.isDeleted); assertEquals("", d.name)
    }

    // ---- validation toasts ----

    @Test fun blankTitleShowsMissingTitleAndSavesNothing() = runTest {
        val events = toasts()
        var done = false
        vm().submit(form(title = " ", selected = emptySet()), null, null, staff) { done = true }
        assertEquals(1, events.size)
        assertEquals("Missing title", events[0].title); assertEquals("Give the task a short title.", events[0].message)
        assertEquals(ToastType.Error, events[0].type)
        assertFalse(done); assertTrue(store.created.isEmpty()); assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
    }

    @Test fun noStaffShowsNoStaffSelected() = runTest {
        val events = toasts()
        vm().submit(form(selected = emptySet()), null, null, staff) { }
        assertEquals("No staff selected", events.single().title)
        assertEquals("Select at least one staff member.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertTrue(store.created.isEmpty())
    }

    @Test fun staffWhoLeftTheListCannotBeAssigned() = runTest {
        val events = toasts()
        vm().submit(form(selected = setOf("u-ghost")), null, null, staff) { }
        assertEquals("No staff selected", events.single().title)
        assertTrue(store.created.isEmpty())
    }

    @Test fun managementRolesInTheSelectionAreIgnored() = runTest {
        val events = toasts()
        val withBoss = staff + TaskStaffUser("u-boss", "Boss", "super_admin")
        vm().submit(form(selected = setOf("u-boss")), null, null, withBoss) { }
        assertEquals("No staff selected", events.single().title)
    }

    // ---- create ----

    @Test fun createSavesTheTaskAuditsNotifiesAndToasts() = runTest {
        val events = toasts()
        var done = false
        val model = vm()
        val defaults = TaskDefaults(relatedCollection = "bookings", relatedId = "bk1", relatedLabel = "Room 204")
        model.submit(
            form(title = "  Fix tap  ", description = "  Use new washer ", priority = TaskPriority.HIGH, selected = setOf("u-bola", "u-ada")),
            defaults, null, staff
        ) { done = true }

        val f = store.created.single()
        assertEquals("Fix tap", f["title"]); assertEquals("other", f["type"]); assertEquals("Use new washer", f["description"])
        assertEquals("high", f["priority"]); assertEquals("pending", f["status"])
        assertEquals(listOf("u-ada", "u-bola"), f["assignedToIds"]); assertEquals(listOf("Ada", "Bola"), f["assignedToNames"])
        assertEquals("me", f["assignedBy"]); assertEquals("Boss", f["assignedByName"])
        assertNull(f["dueAt"])
        assertEquals("bookings", f["relatedCollection"]); assertEquals("bk1", f["relatedId"]); assertEquals("Room 204", f["relatedLabel"])
        listOf("acceptedBy", "acceptedByName", "acceptedAt", "completedAt").forEach { assertNull(it, f[it]) }
        assertServerTime(f["createdAt"])
        assertEquals(false, f["isDeleted"])

        assertEquals(1, audit.entries.size)
        val a = audit.entries.single()
        assertEquals("task_assigned", a.action); assertEquals("tasks", a.collection); assertEquals("task1", a.id)
        assertEquals(mapOf("title" to "Fix tap", "assignedToNames" to listOf("Ada", "Bola")), a.new)

        val n = notifier.calls.single()
        assertEquals(NotificationType.TASK_ASSIGNED, n.type)
        assertEquals(listOf("u-ada", "u-bola"), n.userIds)
        assertEquals("warning", n.severity)
        assertTrue(n.message.contains("Boss") && n.message.contains("Fix tap") && n.message.contains("high"))

        assertEquals("Task Assigned", events.single().title)
        assertEquals("Fix tap → Ada, Bola", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
        assertTrue(done)
        assertFalse(model.saving.value)
    }

    private fun assertServerTime(value: Any?) = assertTrue("createdAt must be the server-time marker", value === TaskServerTime)

    @Test fun blankDescriptionIsSavedAsNull() = runTest {
        vm().submit(form(description = "   "), null, null, staff) { }
        assertNull(store.created.single()["description"])
    }

    @Test fun relatedFieldsAreNullWithoutDefaults() = runTest {
        vm().submit(form(), null, null, staff) { }
        val f = store.created.single()
        assertNull(f["relatedCollection"]); assertNull(f["relatedId"]); assertNull(f["relatedLabel"])
    }

    @Test fun typeAndPriorityAreSaved() = runTest {
        vm().submit(form(type = TaskType.TRANSPORT, priority = TaskPriority.URGENT, selected = setOf("u-ada")), null, null, staff) { }
        assertEquals("transport", store.created.single()["type"]); assertEquals("urgent", store.created.single()["priority"])
    }

    @Test fun dueTimeBecomesTodayAtThatTimeInTheBusinessZone() = runTest {
        val zone = ZoneId.of("Africa/Lagos")
        val model = vm()
        model.submit(form(time = LocalTime.of(14, 30, 45)), null, null, staff) { }
        val due = store.created.single()["dueAt"] as Timestamp
        val local = Instant.ofEpochSecond(due.seconds, due.nanoseconds.toLong()).atZone(zone)
        assertEquals(LocalTime.of(14, 30), local.toLocalTime())
        assertEquals(model.today(), local.toLocalDate())
    }

    @Test fun dueTimeFollowsTheBusinessTimeZone() = runTest {
        val tokyo = FakeTasksSession(timezone = "Asia/Tokyo")
        val model = vm(tokyo)
        model.submit(form(time = LocalTime.of(8, 0)), null, null, staff) { }
        val due = store.created.single()["dueAt"] as Timestamp
        val local = Instant.ofEpochSecond(due.seconds, due.nanoseconds.toLong()).atZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(LocalTime.of(8, 0), local.toLocalTime())
    }

    // ---- reassign ----

    @Test fun reassignUpdatesTheTaskAuditsNotifiesAndToasts() = runTest {
        val events = toasts()
        var done = false
        vm().submit(form(title = "", selected = setOf("u-chidi", "u-ada")), null, ReassignTarget("t77", "Fix tap"), staff) { done = true }

        assertTrue(store.created.isEmpty())
        val (id, f) = store.updates.single()
        assertEquals("t77", id)
        assertEquals(listOf("u-ada", "u-chidi"), f["assignedToIds"]); assertEquals(listOf("Ada", "Chidi"), f["assignedToNames"])
        assertEquals("pending", f["status"])
        listOf("acceptedBy", "acceptedByName", "acceptedAt").forEach { assertNull(it, f[it]) }
        assertTrue(f["reassignedAt"] === TaskServerTime)
        assertEquals("me", f["reassignedBy"]); assertEquals("Boss", f["reassignedByName"])

        val a = audit.entries.single()
        assertEquals("task_reassigned", a.action); assertEquals("tasks", a.collection); assertEquals("t77", a.id)
        assertEquals(mapOf("assignedToNames" to listOf("Ada", "Chidi")), a.new)

        val n = notifier.calls.single()
        assertEquals(NotificationType.TASK_REASSIGNED, n.type)
        assertEquals(listOf("u-ada", "u-chidi"), n.userIds)
        assertTrue(n.message.contains("Boss") && n.message.contains("Fix tap"))

        assertEquals("Task Reassigned", events.single().title)
        assertEquals("Now assigned to Ada, Chidi", events.single().message)
        assertTrue(done)
    }

    // ---- failures ----

    @Test fun aFailedSaveShowsErrorAndDoesNotAuditOrNotifyOrClose() = runTest {
        val events = toasts()
        store.failWith = IllegalStateException("Missing or insufficient permissions.")
        var done = false
        val model = vm()
        model.submit(form(), null, null, staff) { done = true }
        assertEquals("Error", events.single().title)
        assertEquals("Missing or insufficient permissions.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertFalse(done); assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
        assertFalse("saving is always cleared", model.saving.value)
    }

    @Test fun aFailedReassignShowsErrorToo() = runTest {
        val events = toasts()
        store.failWith = IllegalStateException("nope")
        vm().submit(form(), null, ReassignTarget("t1", "x"), staff) { }
        assertEquals("Error", events.single().title); assertEquals("nope", events.single().message)
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aBrokenAuditOrNotifierDoesNotUndoASavedTask() = runTest {
        val events = toasts()
        audit.fail = true; notifier.fail = true
        var done = false
        vm().submit(form(), null, null, staff) { done = true }
        assertEquals(1, store.created.size)
        assertEquals("Task Assigned", events.single().title)
        assertTrue(done)
    }

    @Test fun aSaveThatNeverFinishesTimesOut() = runTest {
        val events = toasts()
        store.hang = true
        val model = vm()
        var done = false
        model.submit(form(), null, null, staff) { done = true }
        assertTrue(model.saving.value)
        advanceTimeBy(TASK_SAVE_TIMEOUT_MS + 1)
        runCurrent()
        assertFalse(model.saving.value)
        assertEquals("Error", events.single().title); assertEquals(MSG_TASK_TIMEOUT, events.single().message)
        assertFalse(done)
    }

    @Test fun signedOutShowsAnErrorAndSavesNothing() = runTest {
        val events = toasts()
        vm(FakeTasksSession(signedIn = false)).submit(form(), null, null, staff) { }
        assertEquals("Error", events.single().title); assertEquals("Not signed in", events.single().message)
        assertTrue(store.created.isEmpty())
    }

    // ---- the staff list ----

    @Test fun theDialogGetsItsStaffFromTheSource() = runTest {
        val model = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.staff.collect { } }
        val s = model.staff.value as com.westly.nbms.core.data.Resource.Success
        assertEquals(listOf("Ada", "Bola", "Chidi"), s.data.map { it.name })
    }
}
