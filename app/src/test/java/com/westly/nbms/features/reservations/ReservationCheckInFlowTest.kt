package com.westly.nbms.features.reservations

import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationCheckInFlowTest {

    private val scheduler = TestCoroutineScheduler()
    private val source = ResSource()
    private val service = ResService()
    private val network = ResNetwork()
    private val toast = ToastController()
    private val lagos = TimeZone.of("Africa/Lagos")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReservationsViewModel(source, service, network, ResSession(), toast)

    private fun expectedEntitled(date: LocalDate, time: LocalTime, nights: Int, official: String): Instant? =
        ReservationRules.entitledCheckOut(
            LocalDateTime(date, time).toInstant(lagos), nights, official, ZoneId.of("Africa/Lagos")
        )

    // ---- Opening ---------------------------------------------------------------------------------

    @Test fun openingSeedsNowPayAtCheckInAndCash() = runTest {
        val vm = viewModel()
        service.officialTime = "12:30"
        vm.openCheckIn(resBooking())
        val st = vm.checkIn.value
        assertNotNull(st.booking)
        assertNotNull(st.date)
        assertNotNull(st.time)
        assertEquals(PaymentOption.PAY_AT_CHECK_IN, st.paymentOption)
        assertEquals(PaymentMethod.CASH, st.paymentMethod)
        assertEquals("", st.idDocumentRef)
        assertEquals("", st.notes)
        assertEquals("12:30", st.officialTime)
        assertNotNull(st.entitledCheckOut)
        assertFalse(st.busy)
    }

    @Test fun theOfficialTimeIsLoadedOnceAndFallsBackToEleven() = runTest {
        val vm = viewModel()
        val gate = CompletableDeferred<String>()
        service.officialGate = gate
        vm.openCheckIn(resBooking())
        assertEquals("11:00", vm.checkIn.value.officialTime)
        gate.complete("15:00")
        assertEquals("15:00", vm.checkIn.value.officialTime)
    }

    // ---- The entitled check-out ------------------------------------------------------------------

    @Test fun entitledCheckOutRecomputesWhenDateTimeOrOfficialTimeChange() = runTest {
        val vm = viewModel()
        val gate = CompletableDeferred<String>()
        service.officialGate = gate
        val booking = resBooking(nights = 2)
        vm.openCheckIn(booking)

        val day = LocalDate(2026, 3, 10)
        vm.setCheckInDate(day)
        vm.setCheckInTime(LocalTime(14, 0))
        val first = vm.checkIn.value.entitledCheckOut
        assertEquals(expectedEntitled(day, LocalTime(14, 0), 2, "11:00"), first)
        assertEquals(Instant.parse("2026-03-12T10:00:00Z"), first)   // 12 Mar 11:00 in Lagos (UTC+1)

        // another date moves it
        val later = LocalDate(2026, 3, 15)
        vm.setCheckInDate(later)
        val second = vm.checkIn.value.entitledCheckOut
        assertEquals(expectedEntitled(later, LocalTime(14, 0), 2, "11:00"), second)
        assertNotEquals(first, second)

        // the time of day does not change which DATE counts
        vm.setCheckInTime(LocalTime(23, 30))
        assertEquals(second, vm.checkIn.value.entitledCheckOut)

        // the official time arriving later changes it
        gate.complete("15:00")
        assertEquals(expectedEntitled(later, LocalTime(23, 30), 2, "15:00"), vm.checkIn.value.entitledCheckOut)
        assertNotEquals(second, vm.checkIn.value.entitledCheckOut)
    }

    @Test fun moreNightsPaidMeanALaterCheckOut() = runTest {
        val one = viewModel().also { it.openCheckIn(resBooking(nights = 1)) }
        val three = viewModel().also { it.openCheckIn(resBooking(nights = 3)) }
        val day = LocalDate(2026, 5, 1)
        for (vm in listOf(one, three)) {
            vm.setCheckInDate(day)
            vm.setCheckInTime(LocalTime(9, 0))
        }
        assertEquals(expectedEntitled(day, LocalTime(9, 0), 1, "11:00"), one.checkIn.value.entitledCheckOut)
        assertEquals(expectedEntitled(day, LocalTime(9, 0), 3, "11:00"), three.checkIn.value.entitledCheckOut)
    }

    @Test fun noDateMeansNoEntitledCheckOut() = runTest {
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.setCheckInDate(null)
        assertNull(vm.checkIn.value.entitledCheckOut)
    }

    // ---- Checks before the service ---------------------------------------------------------------

    @Test fun offlineToastAndTheServiceIsNotCalled() = runTest {
        val toasts = collectToasts(toast)
        network.online = false
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        assertEquals(1, toasts.size)
        assertEquals("You're Offline", toasts[0].title)
        assertEquals("No internet connection detected. Please reconnect and try again.", toasts[0].message)
        assertEquals(ToastType.Error, toasts[0].type)
        assertTrue(service.checkInCalls.isEmpty())
        assertFalse(vm.checkIn.value.busy)

        // the guard was released: once online, the next tap goes through
        network.online = true
        vm.submitCheckIn()
        assertEquals(1, service.checkInCalls.size)
    }

    @Test fun invalidDateToastAndTheServiceIsNotCalled() = runTest {
        val toasts = collectToasts(toast)
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.setCheckInDate(null)
        vm.submitCheckIn()
        assertEquals(1, toasts.size)
        assertEquals("Check-In Failed", toasts[0].title)
        assertEquals("Please enter a valid check-in date and time.", toasts[0].message)
        assertEquals(ToastType.Error, toasts[0].type)
        assertTrue(service.checkInCalls.isEmpty())
    }

    @Test fun checksComeInTheOrderOfflineThenDateThenEntitled() {
        val day = LocalDate(2026, 3, 10)
        val time = LocalTime(10, 0)

        val offline = validateCheckIn(false, null, null, 0, "11:00", lagos)
        assertEquals(CheckInCheck.Rejected("You're Offline", "No internet connection detected. Please reconnect and try again."), offline)

        val badDate = validateCheckIn(true, null, time, 2, "11:00", lagos)
        assertEquals(CheckInCheck.Rejected("Check-In Failed", "Please enter a valid check-in date and time."), badDate)

        val none = validateCheckIn(true, day, time, 0, "11:00", lagos)
        assertEquals(
            CheckInCheck.Rejected("Check-In Failed", "Could not compute the entitled check-out date. Please try again."),
            none
        )

        val ok = validateCheckIn(true, day, time, 2, "11:00", lagos)
        assertTrue(ok is CheckInCheck.Ok)
        assertEquals(expectedEntitled(day, time, 2, "11:00"), (ok as CheckInCheck.Ok).entitledCheckOut)
    }

    // ---- The submit ------------------------------------------------------------------------------

    @Test fun aSecondSubmitIsIgnoredWhileTheFirstRuns() = runTest {
        collectToasts(toast)
        val gate = CompletableDeferred<Unit>()
        service.checkInGate = gate
        val vm = viewModel()
        vm.openCheckIn(resBooking())

        vm.submitCheckIn()
        vm.submitCheckIn()
        vm.submitCheckIn()
        assertEquals(1, service.checkInCalls.size)
        assertTrue(vm.checkIn.value.busy)

        gate.complete(Unit)
        assertFalse(vm.checkIn.value.busy)
        assertEquals(1, service.checkInCalls.size)
    }

    @Test fun blankIdAndNotesBecomeNullAndTextIsKept() = runTest {
        collectToasts(toast)
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.setIdDocumentRef("   ")
        vm.setNotes("")
        vm.submitCheckIn()
        val blank = service.checkInCalls.single().second
        assertNull(blank.idDocumentRef)
        assertNull(blank.notes)

        val vm2 = viewModel()
        service.checkInCalls.clear()
        vm2.openCheckIn(resBooking())
        vm2.setIdDocumentRef(" A1234567 ")
        vm2.setNotes("Late arrival")
        vm2.setPaymentOption(PaymentOption.PAY_AT_CHECK_OUT)
        vm2.setPaymentMethod(PaymentMethod.BANK_TRANSFER)
        vm2.submitCheckIn()
        val form = service.checkInCalls.single().second
        assertEquals("A1234567", form.idDocumentRef)
        assertEquals("Late arrival", form.notes)
        assertEquals(PaymentOption.PAY_AT_CHECK_OUT, form.paymentOption)
        assertEquals(PaymentMethod.BANK_TRANSFER, form.paymentMethod)
    }

    @Test fun theFormCarriesTheChosenCheckInMoment() = runTest {
        collectToasts(toast)
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.setCheckInDate(LocalDate(2026, 3, 10))
        vm.setCheckInTime(LocalTime(14, 0))
        vm.submitCheckIn()
        assertEquals(Instant.parse("2026-03-10T13:00:00Z"), service.checkInCalls.single().second.checkInAt)
    }

    @Test fun successToastWhenPaidNow() = runTest {
        val toasts = collectToasts(toast)
        service.checkInResult = { Result.success(resOutcome(paidNow = true)) }
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        assertEquals("Check-In Complete", toasts.single().title)
        assertEquals("Ada Obi has been checked in. Payment sent to the Accountant for approval.", toasts.single().message)
        assertEquals(ToastType.Success, toasts.single().type)
        assertNotNull(vm.checkIn.value.success)
        assertNull(vm.checkIn.value.booking)
        assertFalse(vm.checkIn.value.busy)
    }

    @Test fun successToastWhenPayingLater() = runTest {
        val toasts = collectToasts(toast)
        service.checkInResult = { Result.success(resOutcome(paidNow = false)) }
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.setPaymentOption(PaymentOption.PAY_AT_CHECK_OUT)
        vm.submitCheckIn()
        assertEquals("Ada Obi has been checked in. Payment will be collected at check-out.", toasts.single().message)
        assertEquals(false, vm.checkIn.value.success?.paidNow)
    }

    @Test fun failureToastAndBusyIsCleared() = runTest {
        val toasts = collectToasts(toast)
        service.checkInResult = { Result.failure(ReservationException("Guest is already checked in.")) }
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        assertEquals("Check-In Failed", toasts.single().title)
        assertEquals("Guest is already checked in.", toasts.single().message)
        assertEquals(ToastType.Error, toasts.single().type)
        assertFalse(vm.checkIn.value.busy)
        assertFalse(vm.checkIn.value.slow)
        assertNull(vm.checkIn.value.success)
        assertNotNull(vm.checkIn.value.booking)       // the dialog stays open so the person can retry

        // a retry is possible: the guard was released
        service.checkInResult = { Result.success(resOutcome()) }
        vm.submitCheckIn()
        assertEquals(2, service.checkInCalls.size)
        assertNotNull(vm.checkIn.value.success)
    }

    @Test fun aThrownErrorClearsBusyToo() = runTest {
        val toasts = collectToasts(toast)
        service.checkInResult = { throw IllegalStateException("network died") }
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        assertEquals("Check-In Failed", toasts.single().title)
        assertEquals("network died", toasts.single().message)
        assertFalse(vm.checkIn.value.busy)
        vm.submitCheckIn()
        assertEquals(2, service.checkInCalls.size)
    }

    @Test fun theSlowHintAppearsOnlyAfterSixSeconds() = runTest {
        collectToasts(toast)
        val gate = CompletableDeferred<Unit>()
        service.checkInGate = gate
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()

        assertFalse(vm.checkIn.value.slow)
        scheduler.advanceTimeBy(5_999)
        scheduler.runCurrent()
        assertFalse(vm.checkIn.value.slow)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.checkIn.value.slow)
        assertEquals(
            "Still working — please don't refresh or leave this page. This can take longer on a slow connection.",
            UI_MSG_STILL_WORKING
        )

        gate.complete(Unit)
        assertFalse(vm.checkIn.value.slow)
        assertFalse(vm.checkIn.value.busy)
    }

    @Test fun noHintForAFastCheckIn() = runTest {
        collectToasts(toast)
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertFalse(vm.checkIn.value.slow)
    }

    // ---- Closing ---------------------------------------------------------------------------------

    @Test fun theDialogCannotBeClosedWhileSaving() = runTest {
        collectToasts(toast)
        val gate = CompletableDeferred<Unit>()
        service.checkInGate = gate
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        vm.closeCheckIn()
        assertNotNull(vm.checkIn.value.booking)
        gate.complete(Unit)
    }

    @Test fun checkInAnotherGuestReturnsToTheList() = runTest {
        collectToasts(toast)
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.submitCheckIn()
        assertNotNull(vm.checkIn.value.success)
        vm.checkInAnother()
        assertNull(vm.checkIn.value.success)
        assertNull(vm.checkIn.value.booking)
    }

    @Test fun cancelClosesTheDialogWithoutCallingTheService() = runTest {
        val vm = viewModel()
        vm.openCheckIn(resBooking())
        vm.closeCheckIn()
        assertNull(vm.checkIn.value.booking)
        assertTrue(service.checkInCalls.isEmpty())
    }
}
