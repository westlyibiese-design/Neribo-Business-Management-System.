package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

// ---- fakes private to this file ----

/** A signed-in session that counts sign-outs. */
private class RecSession(role: Role = Role.RECEPTIONIST, usesPin: Boolean = false) : SessionManager {
    var signOutCount = 0
    override val state: StateFlow<SessionState> = MutableStateFlow<SessionState>(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Rita", "r@x.com", null, "active", usesPin),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = "Africa/Lagos"),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {
        signOutCount++
    }

    override suspend fun refresh() {}
}

private class RecAudit : AuditLogger {
    data class Entry(
        val action: String,
        val collection: String,
        val documentId: String,
        val previous: Map<String, Any?>?,
        val new: Map<String, Any?>?
    )

    val entries = mutableListOf<Entry>()
    var fail = false

    override suspend fun log(
        action: String, collection: String, documentId: String,
        previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?
    ) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

private class RecNavigator : ShellNavigator {
    val opened = mutableListOf<String>()
    override fun open(link: String) {
        opened += link
    }
}

/** Wraps the shared fake store so a test can hold a write open and see how many writes overlap. */
private class RecGatedStore(private val inner: AttFakeStore) : AttendanceStore by inner {
    var gate: CompletableDeferred<Unit>? = null
    var delayMs = 0L
    var active = 0
    var maxActive = 0
    val started = mutableListOf<String>()

    override suspend fun merge(id: String, fields: Map<String, Any?>) {
        started += id
        active++
        maxActive = maxOf(maxActive, active)
        try {
            gate?.await()
            if (delayMs > 0) delay(delayMs)
            inner.merge(id, fields)
        } finally {
            active--
        }
    }
}

private fun List<ToastEvent>.simple() = map { Triple(it.title, it.message, it.type) }

@OptIn(ExperimentalCoroutinesApi::class)
class RecordAttendanceViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val store = AttFakeStore()
    private val gated = RecGatedStore(store)
    private val audit = RecAudit()
    private val toast = ToastController()
    private val nav = RecNavigator()

    /** 07:05 UTC is 08:05 in Lagos, on 2026-10-09. */
    private var nowInstant: Instant = Instant.parse("2026-10-09T07:05:00Z")
    private val today = "2026-10-09"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        // Like the real app: nothing has loaded yet when the page opens.
        store.users.value = Resource.Loading
        store.records.value = Resource.Loading
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(session: RecSession = RecSession()) = RecordAttendanceViewModel(
        AttendanceRepository(gated, session), session, audit, toast, nav, { nowInstant }, RECORD_PIN_SIGN_OUT_DELAY_MS
    )

    private fun TestScope.keepActive(vm: RecordAttendanceViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { } }
    }

    private fun TestScope.collectToasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun TestScope.load(users: List<AttendanceUser>, records: List<AttendanceRecord> = emptyList()) {
        store.users.value = Resource.Success(users)
        store.records.value = Resource.Success(records)
        runCurrent()
    }

    private fun RecordAttendanceViewModel.row(id: String): RecordRowUi = ui.value.rows.first { it.staff.id == id }

    private val ada get() = attUser("a", "Ada")
    private val ben get() = attUser("b", "Ben")
    private val cleo get() = attUser("c", "Cleo")

    // ---- load-error guard ----

    @Test fun aFailedStaffLoadRefusesToSaveWithTheCantSaveToast() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        store.users.value = Resource.Error("down")
        store.records.value = Resource.Success(emptyList())
        runCurrent()

        assertTrue(vm.ui.value.loadFailed)
        assertFalse(vm.saveRow("a"))
        assertTrue(store.merges.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertEquals(
            listOf(
                Triple(
                    "Can't save yet",
                    "Staff or attendance data failed to load. Reload the page before saving to avoid overwriting existing records.",
                    ToastType.Error
                )
            ),
            toasts.simple()
        )
    }

    @Test fun aFailedAttendanceLoadRefusesToSaveToo() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        store.users.value = Resource.Success(listOf(ada))
        store.records.value = Resource.Error("down")
        runCurrent()

        assertTrue(vm.ui.value.loadFailed)
        assertFalse(vm.saveRow("a"))
        assertTrue(store.merges.isEmpty())
        assertEquals(listOf("Can't save yet"), toasts.map { it.title })
    }

    @Test fun theErrorBoxTextIsTheWestlyText() {
        assertEquals(
            "Staff or attendance data failed to load. Reload before saving, or existing records may be overwritten.",
            MSG_RECORD_LOAD_FAILED
        )
    }

    @Test fun saveAllEditedRowsWithALoadErrorGivesOneToastAndWritesNothing() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        store.users.value = Resource.Success(listOf(ada, ben))
        store.records.value = Resource.Error("down")
        runCurrent()
        vm.setNotes("a", "x")
        vm.setNotes("b", "y")

        vm.saveAllEditedRows()

        assertTrue(store.merges.isEmpty())
        assertEquals(listOf("Can't save yet"), toasts.map { it.title })
        assertFalse(vm.ui.value.savingAll)
    }

    @Test fun savingWhileStillLoadingWritesNothing() = runTest {
        val vm = viewModel().also { keepActive(it) }
        assertTrue(vm.ui.value.loading)
        assertFalse(vm.saveRow("a"))
        assertTrue(store.merges.isEmpty())
    }

    // ---- saveRow: new vs existing ----

    @Test fun savingANewRowWritesTheMergeAuditsRecordedAndToasts() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        vm.setStatus("a", AttendanceStatus.LATE)
        vm.setNotes("a", "  traffic ")

        assertTrue(vm.saveRow("a"))

        val (id, fields) = store.merges.single()
        assertEquals("a__$today", id)
        assertEquals("a", fields["staffId"])
        assertEquals("Ada", fields["staffName"])
        assertEquals("receptionist", fields["staffRole"])
        assertEquals(today, fields["dateKey"])
        assertEquals("late", fields["status"])
        assertNull(fields["clockIn"])
        assertNull(fields["clockOut"])
        assertEquals("traffic", fields["notes"])
        assertEquals("u1", fields["recordedBy"])
        assertEquals("Rita", fields["recordedByName"])
        assertEquals(false, fields["isDeleted"])
        assertTrue("a new record gets createdAt", fields.containsKey("createdAt"))
        assertTrue(fields.containsKey("updatedAt"))
        assertTrue(fields["date"] is Timestamp)

        assertEquals(
            RecAudit.Entry(
                "attendance_recorded", "attendance", "a__$today", null,
                mapOf("status" to "late", "clockIn" to null, "clockOut" to null, "date" to today)
            ),
            audit.entries.single()
        )
        assertEquals(listOf(Triple("Attendance Recorded", "Ada — Oct 9, 2026", ToastType.Success)), toasts.simple())
    }

    @Test fun savingAnExistingRowUpdatesAuditsTheOldValuesAndSkipsCreatedAt() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada), listOf(att("a", "Ada", today, status = "present", clockIn = "08:00", notes = "on time")))

        assertTrue(vm.saveRow("a", AttendanceRowOverrides(clockOut = "17:00")))

        val (id, fields) = store.merges.single()
        assertEquals("a__$today", id)
        assertFalse("an existing record keeps its createdAt", fields.containsKey("createdAt"))
        assertEquals("08:00", fields["clockIn"])
        assertEquals("17:00", fields["clockOut"])
        assertEquals("on time", fields["notes"])
        assertEquals(
            RecAudit.Entry(
                "attendance_updated", "attendance", "a__$today",
                mapOf("status" to "present", "clockIn" to "08:00", "clockOut" to null),
                mapOf("status" to "present", "clockIn" to "08:00", "clockOut" to "17:00", "date" to today)
            ),
            audit.entries.single()
        )
        assertEquals(listOf(Triple("Attendance Updated", "Ada — Oct 9, 2026", ToastType.Success)), toasts.simple())
    }

    @Test fun whatThePersonTypedWinsOverTheSavedRecord() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada), listOf(att("a", "Ada", today, status = "present", clockIn = "08:00")))
        vm.setStatus("a", AttendanceStatus.HALF_DAY)
        vm.setClockIn("a", "09:30")

        assertTrue(vm.saveRow("a"))

        val fields = store.merges.single().second
        assertEquals("half_day", fields["status"])
        assertEquals("09:30", fields["clockIn"])
    }

    @Test fun theRowIsUpdatedAfterASave() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        assertFalse(vm.row("a").touched)

        vm.setNotes("a", "late bus")
        vm.saveRow("a")

        val row = vm.row("a")
        assertTrue(row.touched)
        assertEquals("late bus", row.row.notes)
        assertFalse(row.saving)
    }

    // ---- check in / check out ----

    @Test fun checkInKeepsTheRowsCurrentStatusAndStampsTheBusinessTime() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, ben))
        vm.setStatus("a", AttendanceStatus.LATE)

        vm.checkInNow("a")
        vm.checkInNow("b")

        val (first, second) = store.merges
        assertEquals("late", first.second["status"])
        assertEquals("08:05", first.second["clockIn"])
        assertNull(first.second["clockOut"])
        assertEquals("present", second.second["status"])
        assertEquals("08:05", second.second["clockIn"])
    }

    @Test fun checkOutKeepsTheClockInAndTheStatus() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada), listOf(att("a", "Ada", today, status = "half_day", clockIn = "08:00")))

        vm.checkOutNow("a")

        val fields = store.merges.single().second
        assertEquals("half_day", fields["status"])
        assertEquals("08:00", fields["clockIn"])
        assertEquals("08:05", fields["clockOut"])
    }

    @Test fun checkingInThenOutOnTheSameDayWritesToOneDocumentWithBothTimes() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))

        vm.checkInNow("a")
        // The realtime listener delivers the record that was just saved.
        store.records.value = Resource.Success(listOf(att("a", "Ada", today, clockIn = "08:05")))
        runCurrent()
        nowInstant = Instant.parse("2026-10-09T16:30:00Z") // 17:30 in Lagos
        vm.checkOutNow("a")

        assertEquals(listOf("a__$today", "a__$today"), store.merges.map { it.first })
        val (first, second) = store.merges.map { it.second }
        assertTrue(first.containsKey("createdAt"))
        assertFalse(second.containsKey("createdAt"))
        assertEquals("08:05", second["clockIn"])
        assertEquals("17:30", second["clockOut"])
        assertEquals(listOf("attendance_recorded", "attendance_updated"), audit.entries.map { it.action })
        assertEquals(listOf("Attendance Recorded", "Attendance Updated"), toasts.map { it.title })
    }

    // ---- double tap ----

    @Test fun aSecondTapOnTheSameRowIsBlockedWhileTheSaveRuns() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        gated.gate = CompletableDeferred()

        val first = async { vm.saveRow("a") }
        runCurrent()
        assertTrue(vm.row("a").saving)

        assertFalse("the second tap does nothing", vm.saveRow("a"))

        gated.gate!!.complete(Unit)
        runCurrent()
        assertTrue(first.await())
        assertEquals(1, store.merges.size)
        assertEquals(1, audit.entries.size)
        assertFalse(vm.row("a").saving)
    }

    @Test fun anotherRowIsNotBlockedByARowThatIsSaving() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, ben))
        gated.gate = CompletableDeferred()

        val first = async { vm.saveRow("a") }
        runCurrent()
        val second = async { vm.saveRow("b") }
        runCurrent()
        assertTrue(vm.row("a").saving)
        assertTrue(vm.row("b").saving)

        gated.gate!!.complete(Unit)
        runCurrent()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(setOf("a__$today", "b__$today"), store.merges.map { it.first }.toSet())
    }

    // ---- Save All Edited Rows ----

    @Test fun saveAllSavesOnlyTouchedRowsOneAfterAnotherWithPerRowToasts() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        // Ada has a record (so her row is seeded and counts as touched), Ben is edited, Cleo is left alone.
        load(listOf(ada, ben, cleo), listOf(att("a", "Ada", today, status = "late")))
        vm.setNotes("b", "left early")
        gated.delayMs = 100

        val run = launch { vm.saveAllEditedRows() }
        runCurrent()
        assertTrue(vm.ui.value.savingAll)
        assertEquals(listOf("a__$today"), gated.started)

        advanceTimeBy(100)
        runCurrent()
        assertEquals("the second save starts only after the first finished", listOf("a__$today", "b__$today"), gated.started)

        advanceUntilIdle()
        run.join()

        assertEquals(1, gated.maxActive)
        assertEquals(listOf("a__$today", "b__$today"), store.merges.map { it.first })
        assertEquals(
            listOf(
                Triple("Attendance Updated", "Ada — Oct 9, 2026", ToastType.Success),
                Triple("Attendance Recorded", "Ben — Oct 9, 2026", ToastType.Success)
            ),
            toasts.simple()
        )
        assertFalse(vm.ui.value.savingAll)
    }

    @Test fun saveAllOnlyUsesTheRowsTheSearchLeavesOnScreen() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, ben))
        vm.setNotes("a", "x")
        vm.setNotes("b", "y")
        vm.setSearch("ben")

        vm.saveAllEditedRows()

        assertEquals(listOf("b__$today"), store.merges.map { it.first })
    }

    @Test fun saveAllWithNothingEditedDoesNothing() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, ben))

        vm.saveAllEditedRows()

        assertTrue(store.merges.isEmpty())
        assertTrue(toasts.isEmpty())
        assertFalse(vm.ui.value.savingAll)
    }

    @Test fun aSecondSaveAllWhileOneRunsDoesNothing() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        vm.setNotes("a", "x")
        gated.gate = CompletableDeferred()

        val first = launch { vm.saveAllEditedRows() }
        runCurrent()
        vm.saveAllEditedRows() // returns at once
        gated.gate!!.complete(Unit)
        runCurrent()
        first.join()

        assertEquals(1, store.merges.size)
    }

    // ---- re-seeding ----

    @Test fun rowsAreSeededWhenTheAttendanceDataFinishesItsFirstLoad() = runTest {
        val vm = viewModel().also { keepActive(it) }
        assertTrue(vm.ui.value.loading)

        load(listOf(ada, ben), listOf(att("a", "Ada", today, status = "late", clockIn = "08:00", notes = "bus")))

        val a = vm.row("a")
        assertTrue(a.touched)
        assertEquals(AttendanceStatus.LATE, a.row.status)
        assertEquals("08:00", a.row.clockIn)
        assertEquals("bus", a.row.notes)
        assertTrue("a green check shows when a record exists", a.existing != null)
        val b = vm.row("b")
        assertFalse(b.touched)
        assertNull(b.existing)
        assertEquals(AttendanceRowState(), b.row)
    }

    @Test fun editedRowsAreNotReseededOnEveryRealtimeUpdate() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, ben), listOf(att("a", "Ada", today, status = "late", clockIn = "08:00")))
        vm.setStatus("a", AttendanceStatus.ABSENT)
        vm.setNotes("b", "typing…")

        // A realtime update: Ada's record changes elsewhere and Ben's record arrives.
        store.records.value = Resource.Success(
            listOf(att("a", "Ada", today, status = "present", clockIn = "07:00"), att("b", "Ben", today, status = "leave"))
        )
        runCurrent()

        assertEquals("what was typed stays", AttendanceStatus.ABSENT, vm.row("a").row.status)
        assertEquals("typing…", vm.row("b").row.notes)
        assertTrue(vm.row("b").existing != null)

        // A new person joins the staff list: still no re-seed.
        store.users.value = Resource.Success(listOf(ada, ben, cleo))
        runCurrent()
        assertEquals(AttendanceStatus.ABSENT, vm.row("a").row.status)
        assertEquals("typing…", vm.row("b").row.notes)
    }

    @Test fun changingTheDateReseedsAndPickingTheSameDateKeepsTheEdits() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(
            listOf(ada, ben),
            listOf(att("a", "Ada", today, status = "late"), att("a", "Ada", "2026-10-08", status = "leave", clockIn = "09:00"))
        )
        vm.setStatus("a", AttendanceStatus.ABSENT)

        vm.setDate(LocalDate.of(2026, 10, 9)) // same day: nothing is wiped
        assertEquals(AttendanceStatus.ABSENT, vm.row("a").row.status)

        vm.setDate(LocalDate.of(2026, 10, 8))
        assertEquals("2026-10-08", vm.ui.value.dateKey)
        assertEquals(AttendanceStatus.LEAVE, vm.row("a").row.status)
        assertEquals("09:00", vm.row("a").row.clockIn)
        assertFalse("Ben has no record on that day", vm.row("b").touched)

        vm.setDate(LocalDate.of(2026, 10, 9))
        assertEquals("seeded again from the saved record, not from the old edit", AttendanceStatus.LATE, vm.row("a").row.status)
    }

    @Test fun aRowSavedForAnotherDayIsWrittenToThatDaysDocument() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        vm.setDate(LocalDate.of(2026, 10, 8))

        assertTrue(vm.saveRow("a"))

        assertEquals("a__2026-10-08", store.merges.single().first)
        assertEquals("2026-10-08", store.merges.single().second["dateKey"])
    }

    @Test fun pickingAnotherDayWhileASaveRunsDoesNotLeakTheSavedRowIntoTheNewDay() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        gated.gate = CompletableDeferred()

        val saving = async { vm.saveRow("a") }
        runCurrent()
        vm.setDate(LocalDate.of(2026, 10, 8))
        gated.gate!!.complete(Unit)
        runCurrent()
        assertTrue(saving.await())

        assertFalse(vm.row("a").touched)
        assertFalse(vm.row("a").justSaved)
    }

    // ---- the saved tick ----

    @Test fun theGreenCheckShowsForTwoSecondsAfterASave() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))

        vm.saveRow("a")
        assertTrue(vm.row("a").justSaved)

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(vm.row("a").justSaved)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.row("a").justSaved)
    }

    // ---- PIN sessions ----

    @Test fun aPinSessionEndsOnlyAfterASuccessfulCheckIn() = runTest {
        val session = RecSession(Role.RECEPTIONIST, usesPin = true)
        val vm = viewModel(session).also { keepActive(it) }
        load(listOf(ada, ben))

        // A plain Save and Save All never end the session.
        vm.save("a")
        vm.setNotes("b", "x")
        vm.saveAllEditedRows()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, session.signOutCount)
        assertFalse(vm.pinEnding.value)

        vm.checkInNow("b")
        assertTrue(vm.pinEnding.value)
        advanceTimeBy(2_499)
        runCurrent()
        assertEquals(0, session.signOutCount)
        assertTrue(vm.pinEnding.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, session.signOutCount)
        assertFalse(vm.pinEnding.value)
    }

    @Test fun aPinSessionEndsAfterASuccessfulCheckOutToo() = runTest {
        val session = RecSession(Role.RECEPTIONIST, usesPin = true)
        val vm = viewModel(session).also { keepActive(it) }
        load(listOf(ada))

        vm.checkOutNow("a")
        assertTrue(vm.pinEnding.value)
        advanceTimeBy(2_500)
        runCurrent()

        assertEquals(1, session.signOutCount)
        assertFalse(vm.pinEnding.value)
    }

    @Test fun aFailedCheckInKeepsAPinSessionOpen() = runTest {
        val session = RecSession(Role.RECEPTIONIST, usesPin = true)
        val toasts = collectToasts()
        val vm = viewModel(session).also { keepActive(it) }
        load(listOf(ada))
        store.failWith = IllegalStateException("offline")

        vm.checkInNow("a")
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(vm.pinEnding.value)
        assertEquals(0, session.signOutCount)
        assertEquals(listOf(Triple("Error", "offline", ToastType.Error)), toasts.simple())
    }

    @Test fun aBlockedDoubleTapDoesNotEndAPinSessionEither() = runTest {
        val session = RecSession(Role.RECEPTIONIST, usesPin = true)
        val vm = viewModel(session).also { keepActive(it) }
        load(listOf(ada))
        gated.gate = CompletableDeferred()

        vm.checkInNow("a") // holds the write open
        runCurrent()
        vm.checkOutNow("a") // blocked: the same row is saving
        runCurrent()
        assertFalse(vm.pinEnding.value)

        gated.gate!!.complete(Unit)
        runCurrent()
        assertTrue("only the check-in that saved ends the session", vm.pinEnding.value)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, session.signOutCount)
    }

    @Test fun aSessionWithoutAPinNeverShowsTheEndingScreen() = runTest {
        val session = RecSession(Role.SUPER_ADMIN, usesPin = false)
        val vm = viewModel(session).also { keepActive(it) }
        load(listOf(ada))

        vm.checkInNow("a")
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(vm.pinEnding.value)
        assertEquals(0, session.signOutCount)
        assertEquals(1, store.merges.size)
    }

    // ---- failures and permissions ----

    @Test fun aFailedSaveToastsTheErrorAndLetsThePersonTryAgain() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        store.failWith = IllegalStateException("boom")

        assertFalse(vm.saveRow("a"))

        assertEquals(listOf(Triple("Error", "boom", ToastType.Error)), toasts.simple())
        assertTrue(audit.entries.isEmpty())
        assertFalse(vm.row("a").saving)
        assertFalse(vm.row("a").justSaved)
        assertFalse(vm.row("a").touched)

        store.failWith = null
        assertTrue("the row is not stuck as saving", vm.saveRow("a"))
        assertEquals(1, store.merges.size)
    }

    @Test fun aFailedAuditEntryDoesNotTurnASavedRowIntoAnError() = runTest {
        val toasts = collectToasts()
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada))
        audit.fail = true

        assertTrue(vm.saveRow("a"))

        assertEquals(1, store.merges.size)
        assertEquals(listOf("Attendance Recorded"), toasts.map { it.title })
    }

    @Test fun aManagerCannotRecord() = runTest {
        val vm = viewModel(RecSession(Role.MANAGER)).also { keepActive(it) }
        load(listOf(ada))

        assertFalse(vm.saveRow("a"))
        vm.checkInNow("a")
        vm.saveAllEditedRows()

        assertTrue(store.merges.isEmpty())
    }

    @Test fun aSuperAdminAndAReceptionistCanRecord() = runTest {
        listOf(Role.SUPER_ADMIN, Role.RECEPTIONIST).forEach { role ->
            val local = AttFakeStore()
            local.users.value = Resource.Success(listOf(ada))
            local.records.value = Resource.Success(emptyList())
            val session = RecSession(role)
            val vm = RecordAttendanceViewModel(
                AttendanceRepository(local, session), session, audit, toast, nav, { nowInstant }, RECORD_PIN_SIGN_OUT_DELAY_MS
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { } }
            runCurrent()
            assertTrue("$role may record", vm.saveRow("a"))
        }
    }

    @Test fun aStaffMemberWhoIsNoLongerActiveIsNotSaved() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada, attUser("s", "Sam", status = "suspended")))

        assertFalse(vm.saveRow("s"))
        assertFalse(vm.saveRow("nobody"))
        assertTrue(store.merges.isEmpty())
    }

    // ---- navigation ----

    @Test fun theFooterButtonOpensTheRegister() = runTest {
        val vm = viewModel()
        vm.openRegister()
        assertEquals(listOf("attendance"), nav.opened)
    }

    // ---- what the screen shows ----

    @Test fun rowsAreActiveStaffByNameAndTheSearchFiltersThem() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(cleo, attUser("d", "Dan", deleted = true), ben, attUser("s", "Sam", status = "suspended"), ada))

        assertEquals(listOf("Ada", "Ben", "Cleo"), vm.ui.value.rows.map { it.staff.name })
        assertEquals(3, vm.ui.value.activeStaffCount)

        vm.setSearch("  BE ")
        assertEquals(listOf("Ben"), vm.ui.value.rows.map { it.staff.name })
        assertEquals(3, vm.ui.value.activeStaffCount)
    }

    @Test fun anOlderRecordWithOnlyADateStampStillCountsForThatDay() = runTest {
        val vm = viewModel().also { keepActive(it) }
        // 2026-10-09 00:00 in Lagos is 2026-10-08 23:00 UTC.
        val stamp = Timestamp(Instant.parse("2026-10-08T23:00:00Z").epochSecond, 0)
        load(listOf(ada), listOf(att("a", "Ada", dateKey = null, date = stamp, status = "late", id = "old-a")))

        assertTrue(vm.row("a").existing != null)
        assertEquals(AttendanceStatus.LATE, vm.row("a").row.status)
    }

    @Test fun aRecordOfAnotherDayDoesNotShowAsExisting() = runTest {
        val vm = viewModel().also { keepActive(it) }
        load(listOf(ada), listOf(att("a", "Ada", "2026-10-08", status = "late"), att("a", "Ada", today, status = "absent", deleted = true)))

        assertNull(vm.row("a").existing)
        assertFalse(vm.row("a").touched)
    }

    // ---- pure helpers ----

    @Test fun toastDayAndDescriptionUseTheShortMonthForm() {
        assertEquals("Mar 5, 2026", attendanceToastDay("2026-03-05"))
        assertEquals("not-a-date", attendanceToastDay("not-a-date"))
        assertEquals("Ada — Mar 5, 2026", attendanceToastDescription("Ada", "2026-03-05"))
    }

    @Test fun timesParseAndFormatAsHhMm() {
        assertEquals(LocalTime(8, 5), attendanceParseTime("08:05"))
        assertEquals(LocalTime(17, 30), attendanceParseTime(" 17:30 "))
        assertNull(attendanceParseTime(""))
        assertNull(attendanceParseTime("soon"))
        assertEquals("08:05", attendanceFormatTime(LocalTime(8, 5)))
        assertEquals("00:00", attendanceFormatTime(LocalTime(0, 0)))
    }

    @Test fun auditValuesMatchTheWestlyShape() {
        assertNull(attendanceAuditOld(null))
        assertEquals(
            mapOf("status" to "late", "clockIn" to "08:00", "clockOut" to null),
            attendanceAuditOld(att("a", status = "late", clockIn = "08:00"))
        )
        assertEquals(
            mapOf("status" to "leave", "clockIn" to null, "clockOut" to "17:00", "date" to "2026-10-09"),
            attendanceAuditNew(AttendanceRowState(AttendanceStatus.LEAVE, clockIn = " ", clockOut = "17:00"), "2026-10-09")
        )
    }

    @Test fun staffFilterIgnoresCaseAndSpaces() {
        val staff = listOf(attUser("a", "Ada Obi"), attUser("b", "Ben Ade"))
        assertEquals(listOf("a", "b"), filterRecordStaff(staff, "").map { it.id })
        assertEquals(listOf("a", "b"), filterRecordStaff(staff, "  ").map { it.id })
        assertEquals(listOf("a", "b"), filterRecordStaff(staff, "ad").map { it.id })
        assertEquals(listOf("b"), filterRecordStaff(staff, " BEN").map { it.id })
        assertTrue(filterRecordStaff(staff, "zzz").isEmpty())
    }

    @Test fun buildRecordUiShowsLoadingFailureAndRows() {
        val day = LocalDate.of(2026, 10, 9)
        fun ui(a: Resource<List<AttendanceRecord>>, u: Resource<List<AttendanceUser>>) =
            buildRecordUi(a, u, day, "", emptyMap(), emptySet(), emptySet(), false, LAGOS)

        val loading = ui(Resource.Loading, Resource.Success(emptyList()))
        assertTrue(loading.loading)
        assertFalse(loading.loadFailed)

        val failed = ui(Resource.Loading, Resource.Error("x"))
        assertFalse("an error is shown instead of the spinner", failed.loading)
        assertTrue(failed.loadFailed)

        val ready = ui(Resource.Success(listOf(att("a", "Ada", today))), Resource.Success(listOf(ada)))
        assertFalse(ready.loading)
        assertFalse(ready.loadFailed)
        assertEquals("2026-10-09", ready.dateKey)
        assertEquals(1, ready.rows.size)
        assertTrue(ready.rows.single().existing != null)
    }
}
