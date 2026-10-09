package com.westly.nbms.features.finance

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.NotificationType
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class RecordPaymentPayloadTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val user = SessionUser("u1", "biz1", Role.RECEPTIONIST, "Rita", "r@x.com", null, "active", false)

    private fun form(
        guest: String = "  Ada Obi ",
        amount: String = "5000",
        type: String = "deposit",
        method: String = "bank_transfer",
        bookingId: String = "",
        notes: String = ""
    ) = RecordPaymentForm(guest, amount, type, method, bookingId, notes)

    // ── the document ──

    @Test fun theNewDocumentIsAlwaysPendingWithEmptyApprovalFields() {
        val payload = buildPaymentPayload(form(bookingId = " WI-AB12CD ", notes = " paid at desk "), user)
        assertEquals(
            mapOf<String, Any?>(
                "guestName" to "Ada Obi",
                "amount" to 5_000.0,
                "paymentMethod" to "bank_transfer",
                "type" to "deposit",
                "bookingId" to "WI-AB12CD",
                "notes" to "paid at desk",
                "recordedBy" to "u1",
                "recordedByName" to "Rita",
                "approvalStatus" to "pending",
                "approvedBy" to null,
                "approvedByName" to null,
                "approvedAt" to null,
                "rejectedReason" to null,
                "isDeleted" to false
            ),
            payload
        )
        // createdAt is stamped by BusinessFirestore.add (server time) because it is not in the map.
        assertFalse(payload.containsKey("createdAt"))
    }

    @Test fun blankOptionalFieldsAreStoredAsNull() {
        val payload = buildPaymentPayload(form(bookingId = "   ", notes = ""), user)
        assertNull(payload["bookingId"])
        assertNull(payload["notes"])
        assertTrue(payload.containsKey("bookingId"))
        assertTrue(payload.containsKey("notes"))
    }

    @Test fun everyTypeAndMethodHasTheStoredKeyFromTheSpec() {
        assertEquals(
            listOf("room_payment", "walk_in_payment", "deposit", "refund", "other"),
            PAYMENT_TYPE_OPTIONS.map { it.key }
        )
        assertEquals(
            listOf("Room Payment", "Walk-In", "Deposit", "Refund", "Other"),
            PAYMENT_TYPE_OPTIONS.map { it.label }
        )
        assertEquals(
            listOf("cash", "credit_card", "debit_card", "bank_transfer", "mobile_payment"),
            PAYMENT_METHOD_OPTIONS.map { it.key }
        )
        assertEquals(
            listOf("Cash", "Credit Card", "Debit Card", "Bank Transfer", "Mobile Payment"),
            PAYMENT_METHOD_OPTIONS.map { it.label }
        )
    }

    // ── the form checks ──

    @Test fun guestNameAndAmountAreRequired() {
        assertTrue(validateRecordPaymentForm(form(guest = "   ")).guestName != null)
        assertTrue(validateRecordPaymentForm(form(amount = "")).amount != null)
        assertTrue(validateRecordPaymentForm(form(amount = "abc")).amount != null)
        assertTrue(validateRecordPaymentForm(form(amount = "-5")).amount != null)
        assertFalse(validateRecordPaymentForm(form()).any)
    }

    @Test fun zeroIsAnAllowedAmountAndDecimalsParse() {
        assertEquals(0.0, parsePaymentAmount("0")!!, 0.0)
        assertEquals(5_000.5, parsePaymentAmount("5000.5")!!, 0.0)
        assertEquals(5_000.0, parsePaymentAmount("5000.")!!, 0.0)
        assertNull(parsePaymentAmount(" "))
    }

    @Test fun amountTypingKeepsDigitsAndOneDot() {
        assertEquals("1250.50", filterPaymentAmountInput("1,250.5.0"))
        assertEquals("12", filterPaymentAmountInput("-1a2"))
        assertEquals("", filterPaymentAmountInput("abc"))
    }

    // ── refund vs other alert ──

    @Test fun onlyARefundSendsTheRefundAlert() {
        assertEquals(PaymentAlert.REFUND_ISSUED, paymentAlertFor("refund"))
        listOf("room_payment", "walk_in_payment", "deposit", "other").forEach {
            assertEquals(PaymentAlert.PAYMENT_RECEIVED, paymentAlertFor(it))
        }
    }

    @Test fun onlyPinSessionsEndAfterSaving() {
        assertTrue(shouldEndPinSession(user.copy(usesPin = true)))
        assertFalse(shouldEndPinSession(user.copy(usesPin = false)))
    }

    // ── saving through the view model ──

    private class Rig(usesPin: Boolean = false, role: Role = Role.RECEPTIONIST) {
        val firestore = RecordPaymentFirestore()
        val audit = RecordPaymentAudit()
        val notifier = RecordPaymentNotifier()
        val session = RecordPaymentSession(usesPin, role)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = PaymentsViewModel(firestore, audit, notifier, session, toast)
        val signedIn: SessionState.SignedIn get() = session.state.value as SessionState.SignedIn
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    @Test fun savingAPaymentWritesTheDocumentAuditsAndAnnouncesIt() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        var saved = 0
        rig.vm.save(form(type = "deposit", method = "bank_transfer"), rig.signedIn) { saved++ }

        assertEquals(1, rig.firestore.adds.size)
        val (collection, value) = rig.firestore.adds.single()
        assertEquals("payments", collection)
        @Suppress("UNCHECKED_CAST")
        val payload = value as Map<String, Any?>
        assertEquals("pending", payload["approvalStatus"])
        assertEquals(false, payload["isDeleted"])

        val entry = rig.audit.entries.single()
        assertEquals("payment_recorded", entry.action)
        assertEquals("payments", entry.collection)
        assertEquals("pay1", entry.documentId)
        assertNull(entry.previous)
        assertEquals(mapOf<String, Any?>("amount" to 5_000.0), entry.new)

        val alert = rig.notifier.calls.single()
        assertEquals(NotificationType.PAYMENT_RECEIVED, alert.type)
        assertTrue(alert.message.contains("bank transfer"))
        assertTrue(alert.message.contains("Ada Obi"))
        assertTrue(alert.message.contains("Rita"))

        val toast = rig.events.single()
        assertEquals("Payment Recorded", toast.title)
        assertEquals("Sent to the Accountant for approval.", toast.message)
        assertEquals(ToastType.Success, toast.type)
        assertEquals(1, saved)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aRefundSendsTheRefundAlertInstead() = runTest {
        val rig = Rig()
        rig.vm.save(form(type = "refund", notes = "double charge"), rig.signedIn) {}
        val alert = rig.notifier.calls.single()
        assertEquals(NotificationType.REFUND_ISSUED, alert.type)
        assertTrue(alert.message.contains("double charge"))
        assertEquals(1, rig.firestore.adds.size)
    }

    @Test fun aFailingAlertNeverTurnsASavedPaymentIntoAnError() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.notifier.fail = true
        var saved = 0
        rig.vm.save(form(), rig.signedIn) { saved++ }
        assertEquals(1, saved)
        assertEquals("Payment Recorded", rig.events.single().title)
    }

    @Test fun aSharedDeviceSessionSignsOutTwoAndAHalfSecondsAfterSaving() = runTest {
        val rig = Rig(usesPin = true)
        rig.vm.save(form(), rig.signedIn) {}

        assertTrue(rig.vm.endingSession.value)
        advanceTimeBy(2_499)
        runCurrent()
        assertEquals(0, rig.session.signOuts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, rig.session.signOuts)
    }

    @Test fun anEmailSessionStaysSignedIn() = runTest {
        val rig = Rig(usesPin = false, role = Role.SUPER_ADMIN)
        rig.vm.save(form(), rig.signedIn) {}
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(rig.vm.endingSession.value)
        assertEquals(0, rig.session.signOuts)
    }

    @Test fun aFailedSaveShowsTheErrorKeepsTheDialogOpenAndDoesNotSignOut() = runTest {
        val rig = Rig(usesPin = true)
        listenToToasts(rig)
        rig.firestore.failWith = IllegalStateException("No permission")
        var saved = 0
        rig.vm.save(form(), rig.signedIn) { saved++ }

        assertEquals(0, saved)
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
        val toast = rig.events.single()
        assertEquals("Error", toast.title)
        assertEquals("No permission", toast.message)
        assertEquals(ToastType.Error, toast.type)
        assertFalse(rig.vm.saving.value)
        assertFalse(rig.vm.endingSession.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, rig.session.signOuts)
    }

    @Test fun anInvalidFormIsNeverWritten() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.save(form(guest = " "), rig.signedIn) {}
        assertTrue(rig.firestore.adds.isEmpty())
        assertEquals("Error", rig.events.single().title)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aSecondTapWhileSavingDoesNothing() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.firestore.gate = gate
        var saved = 0
        rig.vm.save(form(), rig.signedIn) { saved++ }
        assertTrue(rig.vm.saving.value)
        rig.vm.save(form(), rig.signedIn) { saved++ }
        assertEquals(1, rig.firestore.adds.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, rig.firestore.adds.size)
        assertEquals(1, saved)
        assertFalse(rig.vm.saving.value)
    }

    // ── private test doubles (this file only) ──

    private class RecordPaymentFirestore : BusinessFirestore {
        override val businessId: String = "biz1"
        val adds = mutableListOf<Pair<String, Any>>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null

        override fun collection(name: String): CollectionReference = throw UnsupportedOperationException("collection")
        override fun doc(collection: String, id: String): DocumentReference = throw UnsupportedOperationException("doc")

        override fun <T : Any> observeList(
            collection: String,
            clazz: Class<T>,
            query: (Query) -> Query
        ): Flow<Resource<List<T>>> = emptyFlow()

        override fun <T : Any> observeDoc(collection: String, id: String, clazz: Class<T>): Flow<Resource<T?>> =
            throw UnsupportedOperationException("observeDoc")

        override suspend fun <T : Any> add(collection: String, value: T): String {
            adds += collection to value
            gate?.await()
            failWith?.let { throw it }
            return "pay1"
        }

        override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) = throw UnsupportedOperationException("update")
        override suspend fun set(collection: String, id: String, value: Any, merge: Boolean) = throw UnsupportedOperationException("set")
        override suspend fun delete(collection: String, id: String) = throw UnsupportedOperationException("delete")
        override suspend fun <R> runTransaction(block: suspend (Transaction, BusinessFirestore) -> R): R =
            throw UnsupportedOperationException("runTransaction")
    }

    private class RecordPaymentAudit : AuditLogger {
        data class Entry(
            val action: String,
            val collection: String,
            val documentId: String,
            val previous: Map<String, Any?>?,
            val new: Map<String, Any?>?
        )

        val entries = mutableListOf<Entry>()

        override suspend fun log(
            action: String, collection: String, documentId: String,
            previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?
        ) {
            entries += Entry(action, collection, documentId, previousValue, newValue)
        }
    }

    private class RecordPaymentNotifier : Notifier {
        data class Call(val type: String, val title: String, val message: String)

        val calls = mutableListOf<Call>()
        var fail = false

        override suspend fun notify(
            type: String, title: String, message: String, severity: String, link: String?,
            forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
        ) {
            if (fail) throw IllegalStateException("push is down")
            calls += Call(type, title, message)
        }
    }

    private class RecordPaymentSession(usesPin: Boolean, role: Role) : SessionManager {
        var signOuts = 0
        override val state: StateFlow<SessionState> = MutableStateFlow(
            SessionState.SignedIn(
                SessionUser("u1", "biz1", role, "Rita", "r@x.com", null, "active", usesPin),
                Business("biz1", "Hotel", "ABC123", setOf(role), timezone = "Africa/Lagos"),
                emptySet<ModuleKey>()
            )
        )

        override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
        override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
        override suspend fun signOut() {
            signOuts++
        }
        override suspend fun refresh() {}
    }
}
