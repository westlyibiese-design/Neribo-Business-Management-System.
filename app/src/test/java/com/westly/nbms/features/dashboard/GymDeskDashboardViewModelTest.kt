package com.westly.nbms.features.dashboard

import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.DashGymMember
import com.westly.nbms.features.dashboard.data.DashGymVisit
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GymDeskDashboardViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun inDays(days: Long): Instant = DASH_NOW.plus(Duration.ofDays(days))

    private fun member(id: String, name: String = "Member $id", status: String? = "active", end: Instant? = inDays(60), deleted: Boolean = false) =
        DashGymMember(id = id, name = name, status = status, endDate = end, isDeleted = deleted)

    private fun visit(id: String, dateKey: String? = "2026-10-12", out: Instant? = null, deleted: Boolean = false) =
        DashGymVisit(id = id, memberId = "m", checkInAt = DASH_NOW, checkOutAt = out, dateKey = dateKey, isDeleted = deleted)

    private fun state(members: List<DashGymMember>, visits: List<DashGymVisit> = emptyList(), now: Instant = DASH_NOW) =
        gymDeskStateOf(Resource.Success(members), Resource.Success(visits), now, DASH_LAGOS)

    // ---- the four numbers

    @Test fun activeMembersUseTheEffectiveStatus() {
        val s = state(
            listOf(
                member("1"),                                              // active, runs for 60 more days
                member("2", end = null),                                  // active, no end date
                member("3", end = DASH_NOW.minusSeconds(3_600)),          // stored active but the end date has passed: expired
                member("4", status = "suspended"),
                member("5", status = "cancelled"),
                member("6", status = "expired", end = inDays(30)),        // stored expired but renewed: active again
                member("7", deleted = true)
            )
        )
        assertEquals(3, s.active)
    }

    @Test fun inGymNowIsEveryVisitWithoutACheckOut() {
        val s = state(
            members = emptyList(),
            visits = listOf(
                visit("1"),
                visit("2"),
                visit("3", out = DASH_NOW),
                visit("4", deleted = true)
            )
        )
        assertEquals(2, s.inGymNow)
    }

    @Test fun visitsTodayUseTheBusinessDay() {
        val s = state(
            members = emptyList(),
            visits = listOf(
                visit("1", dateKey = "2026-10-12"),
                visit("2", dateKey = "2026-10-12", out = DASH_NOW),
                visit("3", dateKey = "2026-10-11"),
                visit("4", dateKey = null),
                visit("5", dateKey = "2026-10-12", deleted = true)
            )
        )
        assertEquals(2, s.visitsToday)
    }

    @Test fun todayIsTheBusinessDayNotUtc() {
        // 23:30Z on the 11th is 00:30 on the 12th in Lagos: a visit keyed 2026-10-12 is "today" there
        val late = Instant.parse("2026-10-11T23:30:00Z")
        val s = state(emptyList(), listOf(visit("1", dateKey = "2026-10-12"), visit("2", dateKey = "2026-10-11")), now = late)
        assertEquals(1, s.visitsToday)
    }

    @Test fun theDeskIsDeskWide() {
        // nobody is filtered out: every member of the business counts, whoever registered them
        val s = state((1..12).map { member("m$it") })
        assertEquals(12, s.active)
    }

    // ---- memberships expiring soon

    @Test fun expiringIsSevenDaysOrLessSoonestFirst() {
        val s = state(
            listOf(
                member("1", "Seven", end = inDays(7)),
                member("2", "Three", end = inDays(3)),
                member("3", "Eight", end = inDays(8)),                    // outside the window
                member("4", "Tomorrow", end = DASH_NOW.plusSeconds(12 * 3_600)),
                member("5", "Gone", end = DASH_NOW.minusSeconds(3_600)),  // already expired
                member("6", "NoEnd", end = null),
                member("7", "Suspended", status = "suspended", end = inDays(2)),
                member("8", "Removed", end = inDays(2), deleted = true)
            )
        )
        assertEquals(listOf("Tomorrow", "Three", "Seven"), s.expiring.map { it.name })
        assertEquals(listOf(1, 3, 7), s.expiring.map { it.daysLeft })
        assertEquals(3, s.expiringCount)
        assertFalse(s.hasMoreExpiring)
    }

    @Test fun membersExpiringTheSameDayAreSortedByName() {
        val s = state(listOf(member("1", "bola", end = inDays(2)), member("2", "Ade", end = inDays(2)), member("3", "Chi", end = inDays(1))))
        assertEquals(listOf("Chi", "Ade", "bola"), s.expiring.map { it.name })
    }

    @Test fun theCardListsAtMostFiveAndOffersViewAllWhenThereAreMore() {
        val five = state((1..5).map { member("m$it", "Member $it", end = inDays(it.toLong())) })
        assertEquals(5, five.expiring.size)
        assertEquals(5, five.expiringCount)
        assertFalse(five.hasMoreExpiring)

        val six = state((1..6).map { member("m$it", "Member $it", end = inDays(it.toLong())) })
        assertEquals(5, six.expiring.size)
        assertEquals(6, six.expiringCount)          // the stat card still counts all of them
        assertTrue(six.hasMoreExpiring)
        assertEquals(listOf(1, 2, 3, 4, 5), six.expiring.map { it.daysLeft })   // the soonest five
    }

    @Test fun noExpiringMembersGivesAnEmptyList() {
        val s = state(listOf(member("1"), member("2")))
        assertTrue(s.expiring.isEmpty())
        assertEquals(0, s.expiringCount)
    }

    // ---- loading and errors

    @Test fun stateIsLoadingUntilBothListsArrive() {
        assertTrue(gymDeskStateOf(Resource.Loading, Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS).loading)
        assertTrue(gymDeskStateOf(Resource.Success(emptyList()), Resource.Loading, DASH_NOW, DASH_LAGOS).loading)
        assertFalse(state(emptyList()).loading)
    }

    @Test fun anErrorInEitherListIsAnError() {
        val a = gymDeskStateOf(Resource.Error("x"), Resource.Success(emptyList()), DASH_NOW, DASH_LAGOS)
        val b = gymDeskStateOf(Resource.Success(emptyList()), Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        val c = gymDeskStateOf(Resource.Loading, Resource.Error("x"), DASH_NOW, DASH_LAGOS)
        listOf(a, b, c).forEach {
            assertTrue(it.failed)
            assertFalse(it.loading)
        }
    }

    @Test fun errorTextIsTheGymMessage() {
        assertEquals("We couldn't load gym data.", MSG_GYM_LOAD_FAILED)
    }

    // ---- the view model

    @Test fun aNewVisitMovesTheCounters() = runTest {
        val members = MutableStateFlow<Resource<List<DashGymMember>>>(Resource.Success(listOf(member("1"))))
        val visits = MutableStateFlow<Resource<List<DashGymVisit>>>(Resource.Success(emptyList()))
        val vm = GymDeskDashboardViewModel(
            flows = { GymDeskFlows(members, visits) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertEquals(1, vm.state.value.active)
        assertEquals(0, vm.state.value.inGymNow)
        visits.value = Resource.Success(listOf(visit("v1")))
        assertEquals(1, vm.state.value.inGymNow)
        assertEquals(1, vm.state.value.visitsToday)
        visits.value = Resource.Success(listOf(visit("v1", out = DASH_NOW)))
        assertEquals(0, vm.state.value.inGymNow)
        assertEquals(1, vm.state.value.visitsToday)
    }

    @Test fun tilesAndTheViewAllLinkOpenTheGymPages() = runTest {
        val navigator = RecordingNavigator()
        val vm = GymDeskDashboardViewModel(
            flows = { GymDeskFlows(flowOf(Resource.Success(emptyList())), flowOf(Resource.Success(emptyList()))) },
            zone = DASH_LAGOS, navigator = navigator, clock = flowOf(DASH_NOW)
        )
        vm.open(GYM_DESK_ROUTE_CHECKIN)
        vm.open(GYM_DESK_ROUTE_MEMBERS)
        vm.open(GYM_DESK_ROUTE_ATTENDANCE)
        vm.open(GYM_DESK_ROUTE_REPORTS)
        assertEquals(listOf("gym/checkin", "gym/members", "gym/attendance", "gym/reports"), navigator.opened)
    }

    @Test fun aFailingReadShowsTheErrorAndRetryCollectsAgain() = runTest {
        var collected = 0
        val vm = GymDeskDashboardViewModel(
            flows = { collected++; GymDeskFlows(flowOf(Resource.Error("x")), flowOf(Resource.Success(emptyList()))) },
            zone = DASH_LAGOS, navigator = RecordingNavigator(), clock = flowOf(DASH_NOW)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        assertTrue(vm.state.value.failed)
        assertFalse(vm.state.value.loading)
        vm.retry()
        assertEquals(2, collected)
    }
}
