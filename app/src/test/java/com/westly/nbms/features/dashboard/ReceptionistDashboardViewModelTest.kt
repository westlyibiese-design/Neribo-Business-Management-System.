package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.messages.InboxMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceptionistDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun state(bookings: List<DashBooking>) =
        receptionStateOf(Resource.Success(bookings), DASH_NOW, DASH_LAGOS)

    private fun message(id: String, name: String, status: String, atMillis: Long, subject: String? = null, body: String = "Hello") =
        InboxMessage(id = id, name = name, status = status, subject = subject, message = body, createdAt = Instant.fromEpochMilliseconds(atMillis))

    @Test fun expectedInOutAndInHouseAreCounted() {
        val s = state(
            listOf(
                dashBooking("a", status = "confirmed", checkIn = dashAt("2026-10-12T14:00:00")),
                dashBooking("b", status = "confirmed", checkIn = dashAt("2026-10-13T14:00:00")),
                dashBooking("c", status = "checked_in", checkIn = dashAt("2026-10-10T14:00:00"), checkOut = dashAt("2026-10-12T11:00:00")),
                dashBooking("d", status = "checked_in", checkIn = dashAt("2026-10-11T14:00:00"), checkOut = dashAt("2026-10-14T11:00:00")),
                dashBooking("e", status = "confirmed", checkIn = dashAt("2026-10-12T15:00:00"), deleted = true),
                dashBooking("f", status = "pending", checkIn = dashAt("2026-10-12T16:00:00"))
            )
        )
        assertEquals(1, s.expectedIn)
        assertEquals(1, s.expectedOut)
        assertEquals(2, s.inHouse)
        assertEquals(listOf("a"), s.arrivals.map { it.id })
        assertEquals(listOf("c"), s.departures.map { it.id })
    }

    @Test fun arrivalsAndDeparturesShowTheFirstSixButTheCardsCountAll() {
        val arrivals = (1..8).map { dashBooking("in$it", status = "confirmed", checkIn = dashAt("2026-10-12T0$it:00:00")) }
        val departures = (1..7).map {
            dashBooking("out$it", status = "checked_in", checkIn = dashAt("2026-10-09T10:00:00"), checkOut = dashAt("2026-10-12T0$it:30:00"))
        }
        val s = state(arrivals + departures)
        assertEquals(8, s.expectedIn)
        assertEquals(7, s.expectedOut)
        assertEquals(7, s.inHouse)
        assertEquals(listOf("in1", "in2", "in3", "in4", "in5", "in6"), s.arrivals.map { it.id })
        assertEquals(listOf("out1", "out2", "out3", "out4", "out5", "out6"), s.departures.map { it.id })
    }

    @Test fun todayIsTheBusinessDayNotUtc() {
        // 23:30 UTC on the 11th is 00:30 on the 12th in Lagos.
        val s = receptionStateOf(
            Resource.Success(listOf(dashBooking("a", status = "confirmed", checkIn = dashAt("2026-10-12T00:30:00")))),
            java.time.Instant.parse("2026-10-11T23:30:00Z"), DASH_LAGOS
        )
        assertEquals(1, s.expectedIn)
    }

    @Test fun emptyBookingsGiveEmptyCards() {
        val s = state(emptyList())
        assertEquals(0, s.expectedIn)
        assertTrue(s.arrivals.isEmpty())
        assertTrue(s.departures.isEmpty())
    }

    @Test fun loadingAndFailureStates() {
        val loading = receptionStateOf(Resource.Loading, DASH_NOW, DASH_LAGOS)
        assertTrue(loading.loading)
        val failed = receptionStateOf(Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        assertTrue(failed.failed)
        assertFalse(failed.loading)
    }

    @Test fun messagesCardShowsTheFourNewestAndTheUnreadCount() {
        val list = listOf(
            message("1", "Old", "read", 1_000),
            message("2", "B", "new", 5_000),
            message("3", "C", "new", 4_000),
            message("4", "D", "read", 3_000),
            message("5", "E", "read", 2_000)
        )
        val s = receptionMessagesOf(list, loaded = true, allowed = true)
        assertTrue(s.visible)
        assertEquals(2, s.unread)
        assertEquals(listOf("2", "3", "4", "5"), s.recent.map { it.id })
    }

    @Test fun messagesCardIsHiddenBeforeLoadAfterAnErrorOrForARoleWithoutAccess() {
        val list = listOf(message("1", "A", "new", 1_000))
        assertFalse(receptionMessagesOf(list, loaded = false, allowed = true).visible)
        assertFalse(receptionMessagesOf(list, loaded = true, allowed = false).visible)
        assertTrue(receptionMessagesOf(emptyList(), loaded = true, allowed = true).recent.isEmpty())
    }

    @Test fun viewModelSendsEachTileToItsRoute() = runTest {
        val navigator = RecordingNavigator()
        val vm = ReceptionistDashboardViewModel(
            flows = { ReceptionFlows(flowOf(Resource.Success(emptyList()))) },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW),
            messages = MutableStateFlow(emptyList()), messagesAllowed = true,
            startMessageTracking = {}, loadMessages = { true }
        )
        listOf("checkin", "checkout", "room-reservations", "messages", "bookings").forEach(vm::open)
        assertEquals(listOf("checkin", "checkout", "room-reservations", "messages", "bookings"), navigator.opened)
    }

    @Test fun inboxIsLoadedAndTrackedOnlyForAnAllowedRole() = runTest {
        var tracked = 0
        var loaded = 0
        val inbox = MutableStateFlow(listOf(message("1", "A", "new", 1_000)))
        val vm = ReceptionistDashboardViewModel(
            flows = { ReceptionFlows(flowOf(Resource.Success(emptyList()))) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW),
            messages = inbox, messagesAllowed = true,
            startMessageTracking = { tracked++ }, loadMessages = { loaded++; true }
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messageState.collect { } }
        assertEquals(1, tracked)
        assertEquals(1, loaded)
        assertTrue(vm.messageState.value.visible)
        assertEquals(1, vm.messageState.value.unread)

        var blockedLoads = 0
        val blocked = ReceptionistDashboardViewModel(
            flows = { ReceptionFlows(flowOf(Resource.Success(emptyList()))) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW),
            messages = inbox, messagesAllowed = false,
            startMessageTracking = { error("must not track") }, loadMessages = { blockedLoads++; true }
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { blocked.messageState.collect { } }
        assertEquals(0, blockedLoads)
        assertFalse(blocked.messageState.value.visible)
    }

    @Test fun aFailedInboxLoadHidesTheCardWhileBookingsStillShow() = runTest {
        val vm = ReceptionistDashboardViewModel(
            flows = { ReceptionFlows(flowOf(Resource.Success(listOf(dashBooking("a", status = "checked_in", checkOut = dashAt("2026-10-12T11:00:00")))))) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW),
            messages = MutableStateFlow(listOf(message("1", "A", "new", 1_000))), messagesAllowed = true,
            startMessageTracking = {}, loadMessages = { false }
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messageState.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertFalse(vm.messageState.value.visible)
        assertEquals(1, vm.state.value.expectedOut)
    }

    @Test fun retryCollectsTheBookingsAgain() = runTest {
        var collected = 0
        val vm = ReceptionistDashboardViewModel(
            flows = { collected++; ReceptionFlows(flowOf(Resource.Error("x"))) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW),
            messages = MutableStateFlow(emptyList()), messagesAllowed = false,
            startMessageTracking = {}, loadMessages = { true }
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertEquals(1, collected)
        vm.retry()
        assertEquals(2, collected)
    }
}
