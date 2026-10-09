package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.NotificationType
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RecordExpensePayloadTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun form(
        title: String = "  Electricity ",
        amount: String = "25000",
        date: LocalDate = LocalDate.of(2026, 10, 9),
        category: String = "utilities",
        method: String = "bank_transfer",
        notes: String = ""
    ) = RecordExpenseForm(title, amount, date, category, method, notes)

    private class Rig {
        val firestore = ExpenseFakeFirestore()
        val audit = ExpenseFakeAudit()
        val notifier = ExpenseFakeNotifier()
        val session = ExpenseFakeSession(Role.ACCOUNTANT)
        val toast = ToastController()
        val events = mutableListOf<ToastEvent>()
        val vm = ExpensesViewModel(firestore, audit, notifier, session, toast)
    }

    private fun TestScope.listenToToasts(rig: Rig) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.toast.events.collect { rig.events += it } }
    }

    // ── the document ──

    @Test fun theNewDocumentHasEveryField() {
        val rig = Rig()
        val payload = buildExpensePayload(form(notes = " paid by transfer "), rig.session.signedIn.user, lagos)
        assertEquals(
            mapOf<String, Any?>(
                "title" to "Electricity",
                "amount" to 25_000.0,
                "category" to "utilities",
                // 9 Oct 00:00 in Lagos (UTC+1) is 8 Oct 23:00 UTC
                "date" to Timestamp(java.time.Instant.parse("2026-10-08T23:00:00Z").epochSecond, 0),
                "description" to "paid by transfer",
                "paymentMethod" to "bank_transfer",
                "recordedBy" to "u1",
                "recordedByName" to "Ngozi",
                "isDeleted" to false
            ),
            payload
        )
        // createdAt is stamped by BusinessFirestore.add (server time) because it is not in the map.
        assertFalse(payload.containsKey("createdAt"))
    }

    @Test fun blankNotesAreStoredAsNull() {
        val rig = Rig()
        val payload = buildExpensePayload(form(notes = "   "), rig.session.signedIn.user, lagos)
        assertNull(payload["description"])
        assertTrue(payload.containsKey("description"))
    }

    @Test fun theDateIsTheChosenDayAtMidnightInTheBusinessZone() {
        val user = Rig().session.signedIn.user
        val lagosDate = buildExpensePayload(form(), user, lagos)["date"] as Timestamp
        val utcDate = buildExpensePayload(form(), user, ZoneId.of("UTC"))["date"] as Timestamp
        assertEquals(java.time.Instant.parse("2026-10-08T23:00:00Z").epochSecond, lagosDate.seconds)
        assertEquals(java.time.Instant.parse("2026-10-09T00:00:00Z").epochSecond, utcDate.seconds)
    }

    @Test fun unknownKeysFallBackToOtherAndCash() {
        val user = Rig().session.signedIn.user
        val payload = buildExpensePayload(form(category = "weird", method = "crypto"), user, lagos)
        assertEquals("other", payload["category"])
        assertEquals("cash", payload["paymentMethod"])
    }

    @Test fun everyCategoryAndPaymentMethodHasTheStoredKeyFromTheSpec() {
        assertEquals(
            listOf("utilities", "maintenance", "supplies", "payroll", "marketing", "food_beverage", "equipment", "other"),
            EXPENSE_CATEGORY_OPTIONS.map { it.key }
        )
        assertEquals(
            listOf("Utilities", "Maintenance", "Supplies", "Payroll", "Marketing", "Food Beverage", "Equipment", "Other"),
            EXPENSE_CATEGORY_OPTIONS.map { it.label }
        )
        assertEquals(listOf("cash", "bank_transfer", "card", "check"), EXPENSE_PAYMENT_METHOD_OPTIONS.map { it.key })
        assertEquals(listOf("Cash", "Bank Transfer", "Card", "Check"), EXPENSE_PAYMENT_METHOD_OPTIONS.map { it.label })
    }

    // ── the form checks ──

    @Test fun titleAndAmountAreRequired() {
        assertTrue(validateRecordExpenseForm(form(title = "   ")).title != null)
        assertTrue(validateRecordExpenseForm(form(amount = "")).amount != null)
        assertTrue(validateRecordExpenseForm(form(amount = "abc")).amount != null)
        assertTrue(validateRecordExpenseForm(form(amount = "-5")).amount != null)
        assertFalse(validateRecordExpenseForm(form()).any)
    }

    @Test fun zeroIsAnAllowedAmountAndDecimalsParse() {
        assertEquals(0.0, parseExpenseAmount("0")!!, 0.0)
        assertEquals(2_500.5, parseExpenseAmount("2500.5")!!, 0.0)
        assertNull(parseExpenseAmount(" "))
    }

    @Test fun amountTypingKeepsDigitsAndOneDot() {
        assertEquals("1250.50", filterExpenseAmountInput("1,250.5.0"))
        assertEquals("12", filterExpenseAmountInput("-1a2"))
        assertEquals("", filterExpenseAmountInput("abc"))
    }

    // ── saving through the view model ──

    @Test fun savingWritesTheDocumentAuditsAndShowsTheToast() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        var saved = 0
        rig.vm.save(form(), rig.session.signedIn) { saved++ }

        val (collection, value) = rig.firestore.adds.single()
        assertEquals("expenses", collection)
        @Suppress("UNCHECKED_CAST")
        val payload = value as Map<String, Any?>
        assertEquals("Electricity", payload["title"])
        assertEquals(false, payload["isDeleted"])

        val entry = rig.audit.entries.single()
        assertEquals("expense_recorded", entry.action)
        assertEquals("expenses", entry.collection)
        assertEquals("exp1", entry.documentId)
        assertNull(entry.previous)
        assertEquals(mapOf<String, Any?>("amount" to 25_000.0, "title" to "Electricity"), entry.new)

        val toast = rig.events.single()
        assertEquals("Expense Recorded", toast.title)
        assertEquals("₦25,000 saved.", toast.message)
        assertEquals(ToastType.Success, toast.type)
        assertEquals(1, saved)
        assertFalse(rig.vm.saving.value)
    }

    // ── the Large Expense alert ──

    @Test fun anAmountOf1000OrMoreSendsTheLargeExpenseAlert() = runTest {
        val rig = Rig()
        rig.vm.save(form(amount = "1000"), rig.session.signedIn) {}
        val alert = rig.notifier.calls.single()
        assertEquals(NotificationType.EXPENSE_RECORDED, alert.type)
        assertEquals("Large Expense Recorded", alert.title)
        assertTrue(alert.message.contains("Electricity"))
        assertTrue(alert.message.contains("₦1,000"))
        assertTrue(alert.message.contains("Ngozi"))
    }

    @Test fun anAmountUnder1000SendsNoAlert() = runTest {
        val rig = Rig()
        rig.vm.save(form(amount = "999.99"), rig.session.signedIn) {}
        assertEquals(1, rig.firestore.adds.size)
        assertEquals(1, rig.audit.entries.size)
        assertTrue(rig.notifier.calls.isEmpty())
    }

    @Test fun aFailingAlertNeverTurnsASavedExpenseIntoAnError() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.notifier.fail = true
        var saved = 0
        rig.vm.save(form(amount = "50000"), rig.session.signedIn) { saved++ }
        assertEquals(1, saved)
        assertEquals("Expense Recorded", rig.events.single().title)
    }

    // ── failures and the save guard ──

    @Test fun aFailedSaveShowsTheErrorAndKeepsTheDialogOpen() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.firestore.failWith = IllegalStateException("No permission")
        var saved = 0
        rig.vm.save(form(amount = "5000"), rig.session.signedIn) { saved++ }

        assertEquals(0, saved)
        assertTrue(rig.audit.entries.isEmpty())
        assertTrue(rig.notifier.calls.isEmpty())
        val toast = rig.events.single()
        assertEquals("Error", toast.title)
        assertEquals("No permission", toast.message)
        assertEquals(ToastType.Error, toast.type)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun anInvalidFormIsNeverWritten() = runTest {
        val rig = Rig()
        listenToToasts(rig)
        rig.vm.save(form(title = " "), rig.session.signedIn) {}
        assertTrue(rig.firestore.adds.isEmpty())
        assertEquals("Error", rig.events.single().title)
        assertFalse(rig.vm.saving.value)
    }

    @Test fun aSecondTapWhileSavingDoesNothing() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.firestore.gate = gate
        var saved = 0
        rig.vm.save(form(), rig.session.signedIn) { saved++ }
        assertTrue(rig.vm.saving.value)
        rig.vm.save(form(), rig.session.signedIn) { saved++ }
        assertEquals(1, rig.firestore.adds.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, rig.firestore.adds.size)
        assertEquals(1, saved)
        assertFalse(rig.vm.saving.value)
    }
}
