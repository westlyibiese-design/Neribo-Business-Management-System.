package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaundryViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** A store whose update waits until [gate] is opened, to prove a second tap does nothing. */
    private class GatedStore : LaundryStore {
        val inner = Laundry22aFakeStore()
        var gate: CompletableDeferred<Unit>? = null
        override fun observe(): Flow<Resource<List<LaundryRequest>>> = inner.observe()
        override suspend fun create(payload: Map<String, Any?>): String { gate?.await(); return inner.create(payload) }
        override suspend fun update(id: String, fields: Map<String, Any?>) { gate?.await(); inner.update(id, fields) }
    }

    private class Rig(usesPin: Boolean = false) {
        val store = GatedStore()
        val session = Laundry22aFakeSession(usesPin = usesPin)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = LaundryViewModel(
            LaundryRepository(store, session, Laundry22aFakeAudit(), Laundry22aFakeNotifier()), session, toast
        )
    }

    private fun TestScope.listen(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    @Test fun theViewListsActiveRequestsOldestFirstWithCounts() = runTest {
        val rig = Rig()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
        rig.store.inner.requests.value = Resource.Success(
            listOf(
                laundry22aRequestOf("b", at = LAUNDRY22A_NOW.plusSeconds(10)),
                laundry22aRequestOf("a", at = LAUNDRY22A_NOW),
                laundry22aRequestOf("z", status = LaundryStatus.DELIVERED)
            )
        )
        val view = rig.vm.view.value as LaundryView.Ready
        assertEquals(listOf("a", "b"), view.active.map { it.id })
        assertEquals(1, view.counts[LaundryStatus.DELIVERED])
    }

    @Test fun aLoadErrorShowsTheErrorMessage() = runTest {
        val rig = Rig()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.view.collect { } }
        rig.store.inner.requests.value = Resource.Error("x")
        assertEquals(LaundryView.Error("We couldn't load laundry requests."), rig.vm.view.value)
    }

    @Test fun logWithoutGuestInfoShowsTheMissingGuestInfoToastAndSavesNothing() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.openSheet()
        rig.vm.submit()
        assertEquals(1, rig.events.size)
        assertEquals("Missing guest info", rig.events[0].title)
        assertEquals("Enter a guest name or room number.", rig.events[0].message)
        assertEquals(ToastType.Error, rig.events[0].type)
        assertTrue(rig.store.inner.created.isEmpty())
        assertTrue(rig.vm.sheet.value.open)
    }

    @Test fun loggingARequestSavesClosesAndResetsTheForm() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.openSheet()
        rig.vm.updateForm { it.copy(guestName = "Mr Okoro", roomNumber = "201") }
        rig.vm.submit()
        assertEquals(1, rig.store.inner.created.size)
        assertEquals("Laundry Request Logged", rig.events.single().title)
        assertFalse(rig.vm.sheet.value.open)
        assertFalse(rig.vm.sheet.value.saving)
        assertEquals(LaundryRequestForm(), rig.vm.sheet.value.form)
        assertEquals(0, rig.session.signOuts)
    }

    @Test fun aSecondTapOnLogRequestWhileSavingDoesNothing() = runTest {
        val rig = Rig()
        rig.store.gate = CompletableDeferred()
        rig.vm.openSheet()
        rig.vm.updateForm { it.copy(guestName = "A") }
        rig.vm.submit()
        assertTrue(rig.vm.sheet.value.saving)
        rig.vm.submit()
        rig.store.gate!!.complete(Unit)
        assertEquals(1, rig.store.inner.created.size)
        assertFalse(rig.vm.sheet.value.saving)
    }

    @Test fun aFailedLogShowsTheErrorToastAndClearsTheSavingState() = runTest {
        val rig = Rig()
        listen(rig)
        rig.store.inner.failWith = IllegalStateException("offline")
        rig.vm.openSheet()
        rig.vm.updateForm { it.copy(guestName = "A") }
        rig.vm.submit()
        assertEquals("Error", rig.events.single().title)
        assertEquals("offline", rig.events.single().message)
        assertFalse(rig.vm.sheet.value.saving)
        assertTrue(rig.vm.sheet.value.open)
    }

    @Test fun advanceShowsStatusUpdatedAndBlocksADoubleTap() = runTest {
        val rig = Rig()
        listen(rig)
        rig.store.gate = CompletableDeferred()
        val r = laundry22aRequestOf("r1", status = LaundryStatus.RECEIVED)
        rig.vm.advance(r)
        assertTrue("r1" in rig.vm.busy.value)
        rig.vm.advance(r)
        rig.vm.togglePaid(r)
        rig.store.gate!!.complete(Unit)
        assertEquals(1, rig.store.inner.updates.size)
        assertEquals("Status Updated", rig.events.single().title)
        assertEquals("Washing", rig.events.single().message)
        assertTrue(rig.vm.busy.value.isEmpty())
    }

    @Test fun aFailedAdvanceShowsUpdateFailed() = runTest {
        val rig = Rig()
        listen(rig)
        rig.store.inner.failWith = IllegalStateException("offline")
        rig.vm.advance(laundry22aRequestOf("r1"))
        assertEquals("Update Failed", rig.events.single().title)
        assertEquals("offline", rig.events.single().message)
        assertTrue(rig.vm.busy.value.isEmpty())
    }

    @Test fun togglePaidShowsMarkedPaidAndMarkedUnpaid() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.togglePaid(laundry22aRequestOf("r1", payment = PaymentStatus.UNPAID))
        rig.vm.togglePaid(laundry22aRequestOf("r2", payment = PaymentStatus.PAID))
        assertEquals(listOf("Marked Paid", "Marked Unpaid"), rig.events.map { it.title })
    }

    @Test fun savingAChargeUpdatesItAndShowsTheAmount() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.openCharge(laundry22aRequestOf("r1"))
        rig.vm.saveCharge("5000", "₦")
        assertEquals(5000.0, rig.store.inner.updates.single().second["charge"])
        assertEquals("Charge Updated", rig.events.single().title)
        assertEquals("₦5,000", rig.events.single().message)
        assertEquals(null, rig.vm.chargeTarget.value)
    }

    @Test fun anInvalidOrNegativeChargeIsIgnored() = runTest {
        val rig = Rig()
        listen(rig)
        rig.vm.openCharge(laundry22aRequestOf("r1"))
        rig.vm.saveCharge("abc", "₦")
        rig.vm.openCharge(laundry22aRequestOf("r1"))
        rig.vm.saveCharge("-5", "₦")
        assertTrue(rig.store.inner.updates.isEmpty())
        assertTrue(rig.events.isEmpty())
        assertEquals(null, rig.vm.chargeTarget.value)
    }

    @Test fun aPinSessionEndsAfterLoggingARequest() = runTest {
        val rig = Rig(usesPin = true)
        rig.vm.openSheet()
        rig.vm.updateForm { it.copy(roomNumber = "201") }
        rig.vm.submit()
        assertTrue(rig.vm.pinEnding.value)
        assertEquals(0, rig.session.signOuts)
        advanceTimeBy(2_600)
        assertEquals(1, rig.session.signOuts)
        assertFalse(rig.vm.pinEnding.value)
    }

    @Test fun aPinSessionEndsAfterDeliveringButNotAfterOtherSteps() = runTest {
        val rig = Rig(usesPin = true)
        rig.vm.advance(laundry22aRequestOf("r1", status = LaundryStatus.RECEIVED))
        assertFalse(rig.vm.pinEnding.value)
        rig.vm.advance(laundry22aRequestOf("r2", status = LaundryStatus.READY))
        assertTrue(rig.vm.pinEnding.value)
        advanceTimeBy(2_600)
        assertEquals(1, rig.session.signOuts)
    }

    @Test fun aNormalSessionNeverSignsOut() = runTest {
        val rig = Rig(usesPin = false)
        rig.vm.advance(laundry22aRequestOf("r2", status = LaundryStatus.READY))
        advanceTimeBy(5_000)
        assertEquals(0, rig.session.signOuts)
        assertFalse(rig.vm.pinEnding.value)
    }
}
