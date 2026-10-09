package com.westly.nbms.features.reservations

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val source = ResSource()
    private val service = ResService()
    private val network = ResNetwork()
    private val toast = ToastController()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReservationsViewModel(source, service, network, ResSession(), toast)

    /** 7 reservations (3 pending, 2 confirmed, 2 checked in) plus a walk-in and a deleted record that must not show. */
    private fun sample() = listOf(
        resBooking("p1", "Ada Obi", "101", status = "pending", createdAt = 70),
        resBooking("p2", "Bola Ade", "102", status = "pending", createdAt = 60, email = "bola@example.com"),
        resBooking("p3", "Chidi Eze", "103", status = "pending", createdAt = 50),
        resBooking("c1", "Dayo Bello", "201", status = "confirmed", createdAt = 40),
        resBooking("c2", "Ada Nwosu", "202", status = "confirmed", createdAt = 30),
        resBooking("i1", "Femi Koya", "301", status = "checked_in", createdAt = 20),
        resBooking("i2", "Gina Okon", "302", status = "checked_in", createdAt = 10),
        resBooking("w1", "Walk In", "401", status = "checked_in", source = "walk_in", createdAt = 99),
        resBooking("d1", "Deleted One", "402", status = "pending", deleted = true, createdAt = 98)
    )

    // ---- List state -----------------------------------------------------------------------------

    @Test fun listGoesFromLoadingToSuccessToError() = runTest {
        val vm = viewModel()
        keepActive(vm)
        assertEquals(LoadStatus.LOADING, vm.ui.value.status)

        source.flow.value = Resource.Success(sample())
        runCurrent()
        assertEquals(LoadStatus.READY, vm.ui.value.status)
        assertEquals(7, vm.ui.value.visible.size)

        source.flow.value = Resource.Error("boom")
        runCurrent()
        assertEquals(LoadStatus.ERROR, vm.ui.value.status)
        assertEquals("We couldn't load room reservations.", UI_LOAD_FAILED)
    }

    @Test fun walkInsAndDeletedRecordsNeverShowAndNewestIsFirst() = runTest {
        val vm = viewModel()
        keepActive(vm)
        source.flow.value = Resource.Success(sample())
        runCurrent()
        val ids = vm.ui.value.visible.map { it.id }
        assertFalse("w1" in ids)
        assertFalse("d1" in ids)
        assertEquals(listOf("p1", "p2", "p3", "c1", "c2", "i1", "i2"), ids)
    }

    @Test fun filterChipAndSearchGiveTheSameListAsTheRules() = runTest {
        val vm = viewModel()
        keepActive(vm)
        source.flow.value = Resource.Success(sample())
        runCurrent()
        val reservations = ReservationRules.roomReservations(sample())

        vm.setFilter(ReservationFilter.PENDING)
        runCurrent()
        assertEquals(ReservationRules.filtered(reservations, ReservationFilter.PENDING, ""), vm.ui.value.visible)

        vm.setQuery("ada")
        runCurrent()
        assertEquals(ReservationRules.filtered(reservations, ReservationFilter.PENDING, "ada"), vm.ui.value.visible)
        assertEquals(listOf("p1"), vm.ui.value.visible.map { it.id })

        vm.setFilter(ReservationFilter.ALL)
        runCurrent()
        assertEquals(listOf("p1", "c2"), vm.ui.value.visible.map { it.id })

        vm.setQuery("BOLA@EXAMPLE")
        runCurrent()
        assertEquals(listOf("p2"), vm.ui.value.visible.map { it.id })
    }

    @Test fun chipLabelsCarryTheCountsAndIgnoreTheSearch() = runTest {
        val vm = viewModel()
        keepActive(vm)
        source.flow.value = Resource.Success(sample())
        runCurrent()
        val expected = listOf("All (7)", "pending (3)", "confirmed (2)", "checked in (2)", "checked out (0)", "cancelled (0)")
        assertEquals(expected, vm.ui.value.chips.map { it.text })
        assertEquals(ReservationFilter.entries, vm.ui.value.chips.map { it.filter })

        vm.setQuery("zzz-no-match")
        runCurrent()
        assertEquals(expected, vm.ui.value.chips.map { it.text })
        assertTrue(vm.ui.value.visible.isEmpty())
    }

    @Test fun selectedChipFollowsTheFilter() = runTest {
        val vm = viewModel()
        keepActive(vm)
        source.flow.value = Resource.Success(sample())
        runCurrent()
        assertEquals(listOf(ReservationFilter.ALL), vm.ui.value.chips.filter { it.selected }.map { it.filter })
        vm.setFilter(ReservationFilter.CONFIRMED)
        runCurrent()
        assertEquals(listOf(ReservationFilter.CONFIRMED), vm.ui.value.chips.filter { it.selected }.map { it.filter })
    }

    @Test fun emptyListIsReadyWithNothingVisible() = runTest {
        val vm = viewModel()
        keepActive(vm)
        source.flow.value = Resource.Success(emptyList())
        runCurrent()
        assertEquals(LoadStatus.READY, vm.ui.value.status)
        assertTrue(vm.ui.value.visible.isEmpty())
        assertEquals("All (0)", vm.ui.value.chips.first().text)
    }

    // ---- Status change --------------------------------------------------------------------------

    @Test fun statusChangeSuccessToast() = runTest {
        val toasts = collectToasts(toast)
        val vm = viewModel()
        vm.changeStatus(resBooking(), BookingStatus.NO_SHOW)
        assertEquals(1, toasts.size)
        assertEquals("Reservation Updated", toasts[0].title)
        assertEquals("Reservation no show.", toasts[0].message)
        assertEquals(ToastType.Success, toasts[0].type)
        assertEquals(listOf(BookingStatus.NO_SHOW), service.statusCalls.map { it.second })
    }

    @Test fun statusToastTextsForEveryAllowedStatus() {
        assertEquals("Reservation confirmed.", statusToastMessage(BookingStatus.CONFIRMED))
        assertEquals("Reservation rejected.", statusToastMessage(BookingStatus.REJECTED))
        assertEquals("Reservation cancelled.", statusToastMessage(BookingStatus.CANCELLED))
        assertEquals("Reservation no show.", statusToastMessage(BookingStatus.NO_SHOW))
    }

    @Test fun statusChangeFailureToast() = runTest {
        val toasts = collectToasts(toast)
        service.statusResult = Result.failure(ReservationException("Booking not found."))
        val vm = viewModel()
        vm.changeStatus(resBooking(), BookingStatus.CONFIRMED)
        assertEquals(1, toasts.size)
        assertEquals("Update Failed", toasts[0].title)
        assertEquals("Booking not found.", toasts[0].message)
        assertEquals(ToastType.Error, toasts[0].type)
        assertFalse(vm.busy.value)
    }

    @Test fun busyFlagIsOnWhileRunningAndASecondCallIsIgnored() = runTest {
        val toasts = collectToasts(toast)
        val gate = CompletableDeferred<Unit>()
        service.statusGate = gate
        val vm = viewModel()
        assertFalse(vm.busy.value)

        vm.changeStatus(resBooking(), BookingStatus.CONFIRMED)
        assertTrue(vm.busy.value)
        vm.changeStatus(resBooking("b2"), BookingStatus.REJECTED)
        assertEquals(1, service.statusCalls.size)

        gate.complete(Unit)
        assertFalse(vm.busy.value)
        assertEquals(1, toasts.size)

        vm.changeStatus(resBooking("b3"), BookingStatus.CANCELLED)
        assertEquals(2, service.statusCalls.size)
    }

    @Test fun aThrownErrorStillClearsBusyAndShowsAFailureToast() = runTest {
        val toasts = collectToasts(toast)
        val throwing = object : ReservationsService by service {
            override suspend fun changeStatus(booking: com.westly.nbms.features.bookings.Booking, newStatus: BookingStatus): Result<Unit> =
                throw IllegalStateException("kaput")
        }
        val vm2 = ReservationsViewModel(source, throwing, network, ResSession(), toast)
        vm2.changeStatus(resBooking(), BookingStatus.CONFIRMED)
        assertFalse(vm2.busy.value)
        assertEquals("Update Failed", toasts.last().title)
        assertEquals("kaput", toasts.last().message)
    }

    // ---- Which buttons a card shows -------------------------------------------------------------

    @Test fun actionButtonsMapToLabelsAndDestinations() {
        assertEquals(ActionSpec("Confirm Arrival", BookingStatus.CONFIRMED), actionSpec(ReservationAction.CONFIRM_ARRIVAL))
        assertEquals(ActionSpec("Reject", BookingStatus.REJECTED), actionSpec(ReservationAction.REJECT))
        assertEquals(ActionSpec("Check In", null), actionSpec(ReservationAction.CHECK_IN))
        assertEquals(ActionSpec("Cancel", BookingStatus.CANCELLED), actionSpec(ReservationAction.CANCEL))
        assertEquals(ActionSpec("No Show", BookingStatus.NO_SHOW), actionSpec(ReservationAction.NO_SHOW))
    }

    private fun labels(status: String, role: Role) =
        ReservationRules.actionsFor(resBooking(status = status), role).map { actionSpec(it).label }

    @Test fun cardButtonsAreExactlyWhatTheRulesReturn() {
        assertEquals(listOf("Confirm Arrival", "Reject", "Check In"), labels("pending", Role.SUPER_ADMIN))
        assertEquals(listOf("Confirm Arrival", "Reject", "Check In"), labels("pending", Role.RECEPTIONIST))
        assertEquals(listOf("Check In", "Cancel", "No Show"), labels("confirmed", Role.RECEPTIONIST))
        assertEquals(listOf("Check In", "Cancel", "No Show"), labels("confirmed", Role.MANAGER))
        for (status in listOf("checked_in", "checked_out", "cancelled", "rejected", "no_show")) {
            assertTrue(status, labels(status, Role.SUPER_ADMIN).isEmpty())
        }
        assertTrue(labels("pending", Role.ACCOUNTANT).isEmpty())
        assertTrue(labels("confirmed", Role.HOUSEKEEPING).isEmpty())
    }

    @Test fun onlyBookingsAwaitingCheckInOpenTheDialog() {
        assertTrue(ReservationRules.awaitingCheckIn(resBooking(status = "pending")))
        assertTrue(ReservationRules.awaitingCheckIn(resBooking(status = "confirmed")))
        assertFalse(ReservationRules.awaitingCheckIn(resBooking(status = "checked_in")))
        assertFalse(ReservationRules.awaitingCheckIn(resBooking(status = "cancelled")))
    }

    @Test fun theCheckInButtonOpensTheDialogAndOtherButtonsChangeStatus() = runTest {
        collectToasts(toast)
        val vm = viewModel()
        val b = resBooking(status = "pending")

        vm.onAction(b, ReservationAction.CHECK_IN)
        assertEquals(b, vm.checkIn.value.booking)
        assertTrue(service.statusCalls.isEmpty())
        vm.closeCheckIn()
        assertNull(vm.checkIn.value.booking)

        vm.onAction(b, ReservationAction.REJECT)
        assertEquals(listOf(BookingStatus.REJECTED), service.statusCalls.map { it.second })
    }

    // ---- Small texts ----------------------------------------------------------------------------

    @Test fun checkInTimeIsShownAsHourMinuteAmPm() {
        val lagos = TimeZone.of("Africa/Lagos")
        assertEquals("2:05 PM", checkInTimeText(Instant.parse("2026-03-10T13:05:00Z"), lagos))
        assertEquals("12:00 AM", checkInTimeText(Instant.parse("2026-03-10T23:00:00Z"), lagos))
        assertEquals("9:30 AM", checkInTimeText(Instant.parse("2026-03-10T08:30:00Z"), lagos))
    }

    @Test fun summaryTexts() {
        assertEquals("Room 101 (Deluxe Room)", roomSummary(resBooking()))
        assertEquals("Room 101", roomSummary(resBooking(roomType = null)))
        assertEquals("2 night(s)", nightsText(2))
        assertEquals("1 night(s)", nightsText(ReservationRules.nightsPaid(resBooking(nights = null))))
    }

    @Test fun paymentHelpTextsAreTheOnesPhase13Uses() {
        assertEquals("Guest pays now. The payment is sent to the Accountant for approval.", PaymentOption.PAY_AT_CHECK_IN.help())
        assertEquals("Guest pays when leaving. Payment will be collected at check-out.", PaymentOption.PAY_AT_CHECK_OUT.help())
    }

    @Test fun checkInDoneMessages() {
        assertEquals(
            "Ada Obi has been checked in. Payment sent to the Accountant for approval.",
            checkInDoneMessage("Ada Obi", true)
        )
        assertEquals(
            "Ada Obi has been checked in. Payment will be collected at check-out.",
            checkInDoneMessage("Ada Obi", false)
        )
    }
}
