package com.westly.nbms.features.housekeeping

import androidx.compose.runtime.Composable
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.NotificationType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/** A bound action that draws nothing; only its place in the list matters. */
private class NoopOverviewAction : HousekeepingOverviewAction {
    @Composable
    override fun Content(session: SessionState.SignedIn) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class HousekeepingOverviewViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Rig(role: Role = Role.MANAGER, actions: Set<HousekeepingOverviewAction> = emptySet()) {
        val source = FakeOverviewSource()
        val service = FakeOverviewService()
        val roomLogic = FakeHousekeepingRoomLogic()
        val audit = OverviewFakeAudit()
        val notifier = FakeHousekeepingNotifier()
        val toast = ToastController()
        val session = OverviewFakeSession(role)
        val navigator = OverviewFakeNavigator()
        val events = mutableListOf<ToastEvent>()
        val vm = HousekeepingOverviewViewModel(source, service, roomLogic, audit, notifier, toast, session, navigator, actions)
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    private val cleaningRoom = overviewRoom("r1", "101", status = "cleaning", type = "Deluxe Room", floor = "1")

    // ---- Mark Clean -------------------------------------------------------------------------

    @Test fun markCleanCreatesACleaningMediumTaskForMeThenCompletesIt() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.markClean(cleaningRoom)

        val created = rig.service.created.single()
        assertEquals(TaskType.CLEANING, created.type)
        assertEquals(TaskPriority.MEDIUM, created.priority)
        assertEquals("u1", created.assignedTo)
        assertEquals("Wale", created.assignedToName)
        assertEquals("r1", created.roomId)
        assertEquals("101", created.roomNumber)

        val completed = rig.service.completed.single()
        assertEquals("task-1", completed.taskId)
        assertEquals(HousekeepingRoomRef("r1", "101", "Deluxe Room"), completed.room)
        assertEquals(HousekeepingActor("u1", "Wale"), completed.actor)
        assertEquals(listOf("create", "complete"), rig.service.order)
    }

    @Test fun markCleanShowsRoomMarkedCleanToast() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.markClean(cleaningRoom)
        val toast = rig.events.single()
        assertEquals("Room Marked Clean", toast.title)
        assertEquals("Room 101 is now available.", toast.message)
        assertEquals(ToastType.Success, toast.type)
        assertTrue(rig.vm.busyRoomIds.value.isEmpty())
    }

    @Test fun markCleanFailureShowsTheErrorAndFreesTheButton() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.service.failComplete = IllegalStateException("Room has a guest checked in.")
        rig.vm.markClean(cleaningRoom)
        assertEquals(ToastType.Error, rig.events.single().type)
        assertEquals("Room has a guest checked in.", rig.events.single().message)
        assertTrue(rig.vm.busyRoomIds.value.isEmpty())
    }

    @Test fun housekeepingRoleCannotUseManagementMarkClean() = runTest {
        val rig = Rig(role = Role.HOUSEKEEPING)
        listenToToasts(rig)
        rig.vm.markClean(cleaningRoom)
        assertTrue(rig.service.order.isEmpty())
        assertEquals(ToastType.Error, rig.events.single().type)
    }

    // ---- Maintenance ------------------------------------------------------------------------

    @Test fun maintenanceFlagSetsStatusAuditsOldStatusAndNotifies() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.flagMaintenance(cleaningRoom)

        assertEquals(listOf("status:r1:maintenance"), rig.roomLogic.calls)

        val entry = rig.audit.entries.single()
        assertEquals("room_maintenance", entry.action)
        assertEquals("rooms", entry.collection)
        assertEquals("r1", entry.documentId)
        assertEquals("cleaning", entry.previous?.get("status"))
        assertEquals("maintenance", entry.new?.get("status"))

        val call = rig.notifier.calls.single()
        assertEquals(NotificationType.MAINTENANCE_REQUEST, call.type)
        assertTrue(call.message.contains("Room 101"))
        assertTrue(call.message.contains("Wale"))

        assertEquals(ToastType.Success, rig.events.single().type)
        assertEquals("Maintenance Requested", rig.events.single().title)
    }

    @Test fun maintenanceFlagNeverEndsThePinSession() = runTest {
        val rig = Rig()
        rig.vm.flagMaintenance(cleaningRoom)
        assertEquals(0, rig.session.signOutCalls)
    }

    @Test fun failedStatusChangeShowsErrorWithoutAuditOrAlert() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.roomLogic.failStatus = IllegalStateException("Cannot change this room right now.")
        rig.vm.flagMaintenance(cleaningRoom)
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
        assertEquals(ToastType.Error, rig.events.single().type)
        assertEquals("Cannot change this room right now.", rig.events.single().message)
        assertTrue(rig.vm.busyRoomIds.value.isEmpty())
    }

    @Test fun failedAlertDoesNotUndoTheFlag() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.notifier.fail = true
        rig.vm.flagMaintenance(cleaningRoom)
        assertEquals(1, rig.audit.entries.size)
        assertEquals(ToastType.Success, rig.events.single().type)
    }

    @Test fun maintenanceIsBlockedForNonManagementRoles() = runTest {
        val rig = Rig(role = Role.HOUSEKEEPING)
        rig.vm.flagMaintenance(cleaningRoom)
        assertTrue(rig.roomLogic.calls.isEmpty())
    }

    // ---- Navigation, actions, live state ----------------------------------------------------

    @Test fun roomAssignmentsButtonOpensTheAssignmentsRoute() {
        val rig = Rig()
        rig.vm.openAssignments()
        assertEquals(listOf("housekeeping/assignments"), rig.navigator.opened)
    }

    @Test fun noBoundActionsMeansOnlyRoomAssignments() {
        assertTrue(Rig().vm.actions.isEmpty())
    }

    @Test fun boundActionsKeepTheirOrderSoTheyRenderBeforeRoomAssignments() {
        val first = NoopOverviewAction()
        val second = NoopOverviewAction()
        val rig = Rig(actions = linkedSetOf(first, second))
        assertEquals(listOf(first, second), rig.vm.actions)
    }

    @Test fun stateFollowsTheLiveRoomsAndTasks() = runTest {
        val rig = Rig()
        assertTrue(rig.vm.state.first().loading)

        rig.source.rooms.value = Resource.Success(listOf(cleaningRoom, overviewRoom("r2", "102", status = "available")))
        rig.source.tasks.value = Resource.Success(listOf(overviewTask("t1")))
        val state = rig.vm.state.first { !it.loading }
        assertEquals(listOf("101"), state.cleaningRooms.map { it.number })
        assertEquals(1, state.available)
        assertEquals(1, state.unassignedPending)
    }
}
