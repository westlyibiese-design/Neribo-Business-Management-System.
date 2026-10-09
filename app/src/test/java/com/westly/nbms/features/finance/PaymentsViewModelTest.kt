package com.westly.nbms.features.finance

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsViewModelTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ts(iso: String): Timestamp = Timestamp(Instant.parse(iso).epochSecond, 0)

    private fun doc(
        id: String,
        guest: String? = "Ada Obi",
        type: String? = "room_payment",
        amount: Double = 1_000.0,
        status: String? = "pending",
        at: String? = "2026-10-07T10:00:00Z",
        deleted: Boolean = false
    ) = PaymentDoc(
        id = id,
        guestName = guest,
        type = type,
        amount = amount,
        approvalStatus = status,
        createdAt = at?.let { ts(it) },
        isDeleted = deleted
    )

    private fun ids(rows: List<PaymentDoc>) = rows.map { it.id }

    // ── month filter ──

    @Test fun monthFilterKeepsOnlyThatMonthInTheBusinessZone() {
        val all = listOf(
            doc("oct", at = "2026-10-07T10:00:00Z"),
            doc("sep", at = "2026-09-15T10:00:00Z"),
            // 23:30 UTC on 30 Sep is 00:30 on 1 Oct in Lagos, so it belongs to October.
            doc("edge", at = "2026-09-30T23:30:00Z")
        )
        assertEquals(listOf("oct", "edge"), ids(filterPayments(all, "2026-10", "", lagos)))
        assertEquals(listOf("sep"), ids(filterPayments(all, "2026-09", "", lagos)))
    }

    @Test fun clearingTheMonthShowsEveryMonthNewestFirst() {
        val all = listOf(
            doc("sep", at = "2026-09-15T10:00:00Z"),
            doc("oct", at = "2026-10-07T10:00:00Z"),
            doc("aug", at = "2026-08-01T10:00:00Z")
        )
        assertEquals(listOf("oct", "sep", "aug"), ids(filterPayments(all, "", "", lagos)))
        assertEquals(listOf("oct", "sep", "aug"), ids(filterPayments(all, "  ", "", lagos)))
    }

    @Test fun deletedPaymentsNeverShow() {
        val all = listOf(doc("a"), doc("b", deleted = true))
        assertEquals(listOf("a"), ids(filterPayments(all, "", "", lagos)))
    }

    @Test fun aPaymentWithNoDateYetStaysVisibleAndComesFirst() {
        val all = listOf(doc("old", at = "2026-10-01T10:00:00Z"), doc("fresh", at = null))
        assertEquals(listOf("fresh", "old"), ids(filterPayments(all, "2026-10", "", lagos)))
    }

    // ── search ──

    @Test fun searchMatchesTheGuestNameIgnoringCase() {
        val all = listOf(doc("a", guest = "Ada Obi"), doc("b", guest = "Bola Ade"), doc("c", guest = null))
        assertEquals(listOf("a", "b"), ids(filterPayments(all, "", "  AD ", lagos)).sorted())
        assertEquals(listOf("b"), ids(filterPayments(all, "", "bola", lagos)))
    }

    @Test fun searchMatchesTheRawTypeString() {
        val all = listOf(
            doc("a", type = "room_payment"),
            doc("b", type = "deposit"),
            doc("c", type = "stay_extension")
        )
        assertEquals(listOf("b"), ids(filterPayments(all, "", "deposit", lagos)))
        assertEquals(listOf("a"), ids(filterPayments(all, "", "room_pay", lagos)))
        assertEquals(listOf("c"), ids(filterPayments(all, "", "stay_ext", lagos)))
        // The label "Room Payment" is not the raw string, so it does not match.
        assertTrue(filterPayments(all, "", "Room Payment", lagos).isEmpty())
    }

    // ── totals ──

    @Test fun totalsAreCountedOverTheFilteredRowsOnly() {
        val all = listOf(
            doc("a", amount = 1_000.0, status = "approved", at = "2026-10-02T10:00:00Z"),
            doc("b", amount = 500.0, status = "pending", at = "2026-10-03T10:00:00Z"),
            doc("c", amount = 700.0, status = "rejected", at = "2026-10-04T10:00:00Z"),
            doc("d", amount = 9_000.0, status = "approved", at = "2026-09-04T10:00:00Z"),
            doc("e", amount = 250.0, status = null, at = "2026-10-05T10:00:00Z"),
            doc("f", amount = 4_000.0, status = "approved", deleted = true)
        )
        val october = paymentTotals(filterPayments(all, "2026-10", "", lagos))
        assertEquals(1_000.0, october.approved, 0.0001)
        assertEquals(750.0, october.pending, 0.0001) // b + e (a missing status counts as pending)

        val everything = paymentTotals(filterPayments(all, "", "", lagos))
        assertEquals(10_000.0, everything.approved, 0.0001)

        val searched = paymentTotals(filterPayments(all, "", "room_payment", lagos))
        assertEquals(10_000.0, searched.approved, 0.0001)
        assertEquals(0.0, paymentTotals(emptyList()).pending, 0.0)
    }

    // ── amount styling and labels ──

    @Test fun amountStyleByStatus() {
        assertEquals(PaymentAmountStyle.APPROVED, paymentAmountStyle("approved"))
        assertTrue(PaymentAmountStyle.APPROVED.bold)
        assertFalse(PaymentAmountStyle.APPROVED.strikethrough)

        assertEquals(PaymentAmountStyle.REJECTED, paymentAmountStyle("rejected"))
        assertTrue(PaymentAmountStyle.REJECTED.strikethrough)
        assertFalse(PaymentAmountStyle.REJECTED.bold)

        assertEquals(PaymentAmountStyle.PENDING, paymentAmountStyle("pending"))
        assertEquals(PaymentAmountStyle.PENDING, paymentAmountStyle("anything else"))
        assertFalse(PaymentAmountStyle.PENDING.bold)
        assertFalse(PaymentAmountStyle.PENDING.strikethrough)
    }

    @Test fun statusKeyNormalisation() {
        assertEquals("approved", paymentStatusKey(" Approved "))
        assertEquals("rejected", paymentStatusKey("rejected"))
        assertEquals("pending", paymentStatusKey(null))
        assertEquals("pending", paymentStatusKey(""))
    }

    @Test fun typeAndMethodLabels() {
        assertEquals("Room Payment", paymentsTypeLabel("room_payment"))
        assertEquals("Walk-In", paymentsTypeLabel("walk_in_payment"))
        assertEquals("Deposit", paymentsTypeLabel("deposit"))
        assertEquals("Refund", paymentsTypeLabel("refund"))
        assertEquals("Other", paymentsTypeLabel("other"))
        assertEquals("Stay Extension", paymentsTypeLabel("stay_extension"))
        assertEquals("—", paymentsTypeLabel(null))
        assertEquals("Credit Card", paymentsMethodLabel("credit_card"))
        assertEquals("Cash", paymentsMethodLabel("cash"))
        assertEquals("Bank Transfer", paymentsMethodLabel("bank_transfer"))
        assertEquals("—", paymentsMethodLabel(""))
    }

    @Test fun monthHelpers() {
        assertEquals("2026-10", paymentsCurrentMonth(lagos, Instant.parse("2026-10-09T08:00:00Z")))
        assertEquals("2026-10", paymentsCurrentMonth(lagos, Instant.parse("2026-09-30T23:30:00Z")))
        assertEquals("October 2026", paymentsMonthLabel("2026-10"))
        assertEquals("nonsense", paymentsMonthLabel("nonsense"))
    }

    @Test fun onlySuperAdminAndReceptionistMayRecord() {
        assertTrue(paymentsCanRecord(Role.SUPER_ADMIN))
        assertTrue(paymentsCanRecord(Role.RECEPTIONIST))
        assertFalse(paymentsCanRecord(Role.ACCOUNTANT))
        assertFalse(paymentsCanRecord(Role.MANAGER))
        assertFalse(paymentsCanRecord(Role.OPERATIONS_MANAGER))
    }

    // ── the view model ──

    @Test fun theViewShowsTheCurrentMonthFirstAndAllMonthsWhenCleared() = runTest {
        val now = Instant.now()
        val firestore = PaymentsListFirestore(
            Resource.Success(
                listOf(
                    doc("now1", amount = 1_000.0, status = "approved").copy(createdAt = Timestamp(now.epochSecond, 0)),
                    doc("now2", amount = 400.0, status = "pending", guest = "Zed").copy(createdAt = Timestamp(now.epochSecond - 1, 0)),
                    doc("old", amount = 9_000.0, status = "approved", at = "2020-01-05T10:00:00Z")
                )
            )
        )
        val vm = PaymentsViewModel(firestore, PaymentsListAudit(), PaymentsListNotifier(), PaymentsListSession(), ToastController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
        runCurrent()

        assertEquals(paymentsCurrentMonth(lagos), vm.filters.value.month)
        var ready = vm.view.value as PaymentsView.Ready
        assertEquals(listOf("now1", "now2"), ids(ready.rows))
        assertEquals(1_000.0, ready.totals.approved, 0.0001)
        assertEquals(400.0, ready.totals.pending, 0.0001)

        vm.setMonth("")
        runCurrent()
        ready = vm.view.value as PaymentsView.Ready
        assertEquals(listOf("now1", "now2", "old"), ids(ready.rows))
        assertEquals(10_000.0, ready.totals.approved, 0.0001)

        vm.setSearch("zed")
        runCurrent()
        ready = vm.view.value as PaymentsView.Ready
        assertEquals(listOf("now2"), ids(ready.rows))
        assertEquals(0.0, ready.totals.approved, 0.0001)
        assertEquals(400.0, ready.totals.pending, 0.0001)
    }

    @Test fun aLoadErrorBecomesTheErrorState() = runTest {
        val firestore = PaymentsListFirestore(Resource.Error("boom"))
        val vm = PaymentsViewModel(firestore, PaymentsListAudit(), PaymentsListNotifier(), PaymentsListSession(), ToastController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
        runCurrent()
        assertEquals(PaymentsView.Error(MSG_PAYMENTS_LOAD_FAILED), vm.view.value)
    }
}

// ── private test doubles (this file only) ──

private class PaymentsListFirestore(private val result: Resource<List<PaymentDoc>>) : BusinessFirestore {
    override val businessId: String = "biz1"

    override fun collection(name: String): CollectionReference = throw UnsupportedOperationException("collection")
    override fun doc(collection: String, id: String): DocumentReference = throw UnsupportedOperationException("doc")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observeList(
        collection: String,
        clazz: Class<T>,
        query: (Query) -> Query
    ): Flow<Resource<List<T>>> = flowOf(result) as Flow<Resource<List<T>>>

    override fun <T : Any> observeDoc(collection: String, id: String, clazz: Class<T>): Flow<Resource<T?>> =
        throw UnsupportedOperationException("observeDoc")

    override suspend fun <T : Any> add(collection: String, value: T): String = throw UnsupportedOperationException("add")
    override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) = throw UnsupportedOperationException("update")
    override suspend fun set(collection: String, id: String, value: Any, merge: Boolean) = throw UnsupportedOperationException("set")
    override suspend fun delete(collection: String, id: String) = throw UnsupportedOperationException("delete")
    override suspend fun <R> runTransaction(block: suspend (Transaction, BusinessFirestore) -> R): R =
        throw UnsupportedOperationException("runTransaction")
}

private class PaymentsListAudit : AuditLogger {
    override suspend fun log(
        action: String, collection: String, documentId: String,
        previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?
    ) {
    }
}

private class PaymentsListNotifier : Notifier {
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
    }
}

private class PaymentsListSession : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", Role.ACCOUNTANT, "Ola", "o@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(Role.ACCOUNTANT), timezone = "Africa/Lagos"),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}
