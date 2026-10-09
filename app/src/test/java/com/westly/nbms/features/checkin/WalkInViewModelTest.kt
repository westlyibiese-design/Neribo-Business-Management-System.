package com.westly.nbms.features.checkin

import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.features.rooms.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class WalkInViewModelTest {

    private val store = FakeWalkInStore()
    private val roomLogic = FakeRoomLogic()
    private val connectivity = FakeConnectivity()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A Firestore that only knows the two live reads the page makes. */
    private fun firestoreWith(rooms: List<Room>): BusinessFirestore =
        Proxy.newProxyInstance(BusinessFirestore::class.java.classLoader, arrayOf(BusinessFirestore::class.java)) { _, m, _ ->
            when (m.name) {
                "observeList" -> flowOf(Resource.Success(rooms))
                "observeDoc" -> flowOf(Resource.Success(null))
                else -> throw UnsupportedOperationException(m.name)
            }
        } as BusinessFirestore

    private fun viewModel(
        session: FakeSession = FakeSession(),
        rooms: List<Room> = listOf(testRoom(), testRoom("r2", "102", status = "occupied"))
    ): WalkInViewModel {
        val repo = WalkInRepository(store, roomLogic, FakeRealtime(), FakeAudit(), FakeNotifier(), connectivity)
        return WalkInViewModel(firestoreWith(rooms), repo, session, ToastController())
    }

    private fun kotlinx.coroutines.test.TestScope.keepLiveReads(vm: WalkInViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rooms.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.checkOutTime.collect { } }
    }

    private fun WalkInViewModel.fillIn() {
        setFullName("Ada Obi")
        setPhone("08031234567")
        setRoom("r1")
    }

    @Test fun roomsOfEveryStatusAreListed() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        val rooms = vm.rooms.value
        assertFalse(rooms.loading)
        assertFalse(rooms.failed)
        assertEquals(listOf("101", "102"), rooms.rooms.map { it.number })
        assertEquals(listOf("available", "occupied"), rooms.rooms.map { it.status })
    }

    @Test fun doubleTapCreatesExactlyOneBooking() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.fillIn()
        store.gate = CompletableDeferred()

        vm.submit()
        assertTrue(vm.state.value.busy)
        vm.submit() // second tap while the first is still saving
        vm.submit()

        store.gate!!.complete(Unit)
        assertEquals(1, store.commits.size)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.success)
    }

    @Test fun successResetsTheFormAndShowsTheSuccessScreen() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.fillIn()
        vm.submit()
        val ui = vm.state.value
        assertEquals("Ada Obi", ui.success!!.guestName)
        assertEquals("", ui.form.fullName)
        assertNull(ui.form.roomId)
        vm.registerAnother()
        assertNull(vm.state.value.success)
    }

    @Test fun anOccupiedRoomCannotBeSubmitted() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.setFullName("Ada Obi")
        vm.setPhone("0803")
        vm.setRoom("r2")
        vm.submit()
        assertTrue(store.commits.isEmpty())
        assertNull(vm.state.value.success)
        assertFalse(vm.state.value.busy)
    }

    @Test fun anEmptyNameIsIgnoredAndShowsTheMessages() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.setRoom("r1")
        vm.submit()
        assertTrue(store.commits.isEmpty())
        assertTrue(vm.state.value.showErrors)
    }

    @Test fun noRoomMeansNothingHappens() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.setFullName("Ada")
        vm.setPhone("0803")
        vm.submit()
        assertTrue(store.commits.isEmpty())
        assertFalse(vm.state.value.busy)
    }

    @Test fun signedOutMeansNothingHappens() = runTest {
        val vm = viewModel(session = FakeSession(role = null))
        keepLiveReads(vm)
        vm.fillIn()
        vm.submit()
        assertTrue(store.commits.isEmpty())
    }

    @Test fun failedSaveClearsTheBusyStateSoItCanBeTriedAgain() = runTest {
        val vm = viewModel()
        keepLiveReads(vm)
        vm.fillIn()
        store.failWith = IllegalStateException("boom")
        vm.submit()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.success)
        store.failWith = null
        vm.submit()
        assertEquals(1, store.commits.size)
    }

    @Test fun pinSessionEndsTwoAndAHalfSecondsAfterSuccess() = runTest {
        val session = FakeSession(usesPin = true)
        val vm = viewModel(session = session)
        keepLiveReads(vm)
        vm.fillIn()
        vm.submit()
        assertNotNull(vm.state.value.success)
        assertEquals(0, session.signOuts)
        advanceTimeBy(2_400)
        assertEquals(0, session.signOuts)
        advanceTimeBy(200)
        assertEquals(1, session.signOuts)
    }

    @Test fun emailPasswordSessionStaysSignedIn() = runTest {
        val session = FakeSession(usesPin = false)
        val vm = viewModel(session = session)
        keepLiveReads(vm)
        vm.fillIn()
        vm.submit()
        advanceTimeBy(10_000)
        assertEquals(0, session.signOuts)
    }

    // ---- dates ----

    @Test fun checkOutCanNeverBeBeforeCheckIn() = runTest {
        val vm = viewModel()
        vm.setCheckInDate(LocalDate(2026, 10, 10))
        vm.setCheckOutDate(LocalDate(2026, 10, 8))
        assertEquals(LocalDate(2026, 10, 10), vm.state.value.form.checkOutDate)
        vm.setCheckOutDate(LocalDate(2026, 10, 14))
        assertEquals(LocalDate(2026, 10, 14), vm.state.value.form.checkOutDate)
    }

    @Test fun movingCheckInPastCheckOutPushesCheckOutToTheNextDay() = runTest {
        val vm = viewModel()
        vm.setCheckInDate(LocalDate(2026, 10, 10))
        vm.setCheckOutDate(LocalDate(2026, 10, 11))
        vm.setCheckInDate(LocalDate(2026, 10, 20))
        assertEquals(LocalDate(2026, 10, 21), vm.state.value.form.checkOutDate)
        // a later check-out is left alone
        vm.setCheckOutDate(LocalDate(2026, 10, 25))
        vm.setCheckInDate(LocalDate(2026, 10, 22))
        assertEquals(LocalDate(2026, 10, 25), vm.state.value.form.checkOutDate)
    }
}
