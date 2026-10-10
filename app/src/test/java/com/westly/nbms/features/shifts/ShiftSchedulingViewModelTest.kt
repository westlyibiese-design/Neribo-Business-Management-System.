package com.westly.nbms.features.shifts

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

private class VmFakeShiftsStore : ShiftsStore {
    val live = MutableStateFlow<Resource<List<Shift>>>(Resource.Success(emptyList()))
    val stored = mutableListOf<Shift>()
    val observed = mutableListOf<Triple<String, String, String>>()
    val batches = mutableListOf<List<Map<String, Any?>>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val batchUpdates = mutableListOf<Pair<List<String>, Map<String, Any?>>>()
    var failWrites: Exception? = null

    override fun observe(roleKey: String, fromKey: String, toKey: String): Flow<Resource<List<Shift>>> {
        observed += Triple(roleKey, fromKey, toKey)
        return live
    }
    override suspend fun byStaff(staffId: String) = stored.filter { it.staffId == staffId }
    override suspend fun bySeries(seriesId: String) = stored.filter { it.seriesId == seriesId }
    override suspend fun createBatch(docs: List<Map<String, Any?>>): List<String> {
        failWrites?.let { throw it }
        batches += docs
        return docs.indices.map { "new$it" }
    }
    override suspend fun update(id: String, fields: Map<String, Any?>) {
        failWrites?.let { throw it }
        updates += id to fields
    }
    override suspend fun updateBatch(ids: List<String>, fields: Map<String, Any?>) {
        failWrites?.let { throw it }
        batchUpdates += ids to fields
    }
}

private class VmFakeAudit : AuditLogger {
    val actions = mutableListOf<String>()
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        actions += action
    }
}

private class VmFakeNotifier : Notifier {
    val types = mutableListOf<String>()
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        types += type
    }
}

private class VmFakeStaff : ShiftStaffSource {
    val live = MutableStateFlow<Resource<List<ShiftStaff>>>(Resource.Success(emptyList()))
    val roles = mutableListOf<Role>()
    override fun observe(role: Role): Flow<Resource<List<ShiftStaff>>> {
        roles += role
        return live
    }
}

private class VmFakeSession(signedIn: Boolean = true) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("boss1", "biz1", Role.MANAGER, "Boss", "b@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(Role.MANAGER), timezone = "Africa/Lagos"),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftSchedulingViewModelTest {

    private val store = VmFakeShiftsStore()
    private val audit = VmFakeAudit()
    private val notifier = VmFakeNotifier()
    private val staffSource = VmFakeStaff()
    private val toast = ToastController()
    private val ada = ShiftStaff("ada", "Ada")
    private val bola = ShiftStaff("bola", "Bola")

    /** Wednesday 4 March 2026, 09:00 in the business time zone. */
    private val now = LocalDateTime.parse("2026-03-04T09:00:00")

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(signedIn: Boolean = true): ShiftSchedulingViewModel = ShiftSchedulingViewModel(
        ShiftsRepository(store, audit, notifier), staffSource, toast, VmFakeSession(signedIn), flowOf(Unit)
    ) { now }

    private fun TestScope.watch(vm: ShiftSchedulingViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.view.collect { } }
    }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun form(
        staffId: String? = "ada", label: String = "Morning Shift", date: String = "2026-03-04",
        start: String = "08:00", end: String = "16:00", type: RecurrenceType = RecurrenceType.NONE,
        weekdays: Set<Int> = emptySet(), until: String? = null
    ) = ShiftFormState(
        staffId = staffId, label = label, date = LocalDate.parse(date), startTime = start, endTime = end,
        recurrenceType = type, weekdays = weekdays, until = until?.let(LocalDate::parse)
    )

    private fun shift(
        id: String, date: String = "2026-03-04", start: String = "08:00", end: String = "16:00",
        staffId: String = "ada", name: String = "Ada", label: String = "Morning", seriesId: String? = null
    ) = Shift(
        id = id, role = "receptionist", staffId = staffId, staffName = name, date = date, startTime = start,
        endTime = end, label = label, seriesId = seriesId, status = "scheduled"
    )

    private fun TestScope.ready(vm: ShiftSchedulingViewModel): ShiftBoard {
        watch(vm)
        return (vm.view.value as ShiftsView.Ready).board
    }

    // ---- calendar ----

    @Test fun startsOnTheWeekOfTodayForReceptionists() = runTest {
        staffSource.live.value = Resource.Success(listOf(ada, bola))
        val vm = vm()
        val board = ready(vm)
        assertEquals(Role.RECEPTIONIST, vm.selection.value.role)
        assertEquals(ShiftViewMode.WEEK, vm.selection.value.mode)
        assertEquals(LocalDate.parse("2026-03-04"), vm.selection.value.anchor)
        assertEquals(2, board.stats.onRoster)
        // One extra day before the range brings in yesterday's overnight shifts.
        assertEquals(Triple("receptionist", "2026-02-28", "2026-03-07"), store.observed.last())
    }

    @Test fun showsLoadingUntilBothSourcesAnswer() = runTest {
        staffSource.live.value = Resource.Loading
        val vm = vm()
        watch(vm)
        assertEquals(ShiftsView.Loading, vm.view.value)
        staffSource.live.value = Resource.Success(listOf(ada))
        assertTrue(vm.view.value is ShiftsView.Ready)
    }

    @Test fun aLoadFailureShowsTheSpecMessage() = runTest {
        store.live.value = Resource.Error("boom")
        val vm = vm()
        watch(vm)
        assertEquals(ShiftsView.Error("We couldn't load the shift schedule."), vm.view.value)
    }

    @Test fun cancelledShiftsDoNotCountAndLiveShiftsDo() = runTest {
        staffSource.live.value = Resource.Success(listOf(ada))
        store.live.value = Resource.Success(
            listOf(shift("a"), shift("b").copy(status = "cancelled"))
        )
        val board = ready(vm())
        assertEquals(1, board.stats.inView)
        assertEquals(1, board.stats.onDutyNow)
    }

    @Test fun changingRoleModeOrDateResubscribes() = runTest {
        staffSource.live.value = Resource.Success(listOf(ada))
        val vm = vm()
        watch(vm)
        vm.setRole(Role.HOUSEKEEPING)
        assertEquals(Triple("housekeeping", "2026-02-28", "2026-03-07"), store.observed.last())
        assertEquals(Role.HOUSEKEEPING, staffSource.roles.last())
        vm.setMode(ShiftViewMode.DAY)
        assertEquals(Triple("housekeeping", "2026-03-03", "2026-03-04"), store.observed.last())
        vm.next()
        assertEquals(Triple("housekeeping", "2026-03-04", "2026-03-05"), store.observed.last())
        vm.previous()
        vm.previous()
        assertEquals(Triple("housekeeping", "2026-03-02", "2026-03-03"), store.observed.last())
        vm.goToToday()
        assertEquals(LocalDate.parse("2026-03-04"), vm.selection.value.anchor)
        vm.setMode(ShiftViewMode.MONTH)
        assertEquals(Triple("housekeeping", "2026-02-28", "2026-03-31"), store.observed.last())
    }

    // ---- scheduling a new shift ----

    @Test fun schedulingASingleShiftWritesItAndClosesTheSheet() = runTest {
        val events = toasts()
        val vm = vm()
        var done = false
        vm.submitNew(form(), Role.RECEPTIONIST, listOf(ada, bola)) { done = true }
        assertTrue(done)
        assertFalse(vm.saving.value)
        assertNull(vm.conflicts.value)
        assertEquals(1, store.batches.single().size)
        assertEquals("Ada", store.batches.single().single()["staffName"])
        assertEquals(listOf("Shift Scheduled"), events.map { it.message })
        assertNull(events.single().title)
        assertEquals(ToastType.Success, events.single().type)
        assertEquals(listOf("shift_created"), audit.actions)
        assertEquals(1, notifier.types.size)
    }

    @Test fun aWeeklySeriesReportsHowManyShiftsWereCreated() = runTest {
        val events = toasts()
        val vm = vm()
        // Mon-Fri from Wed 4 Mar to Fri 13 Mar: Wed, Thu, Fri, Mon, Tue, Wed, Thu, Fri = 8
        vm.submitNew(
            form(type = RecurrenceType.WEEKLY, weekdays = setOf(1, 2, 3, 4, 5), until = "2026-03-13"),
            Role.RECEPTIONIST, listOf(ada)
        ) {}
        assertEquals(8, store.batches.single().size)
        assertEquals("Shift Scheduled", events.single().title)
        assertEquals("8 shifts created", events.single().message)
    }

    @Test fun theFirstPersonIsUsedWhenNobodyWasPicked() = runTest {
        toasts()
        val vm = vm()
        vm.submitNew(form(staffId = null), Role.RECEPTIONIST, listOf(bola, ada)) {}
        assertEquals("Bola", store.batches.single().single()["staffName"])
    }

    @Test fun aConflictShowsTheRedBoxAndWritesNothing() = runTest {
        val events = toasts()
        store.stored += shift("old", date = "2026-03-04", start = "10:00", end = "14:00", label = "Midday")
        val vm = vm()
        var done = false
        vm.submitNew(form(), Role.RECEPTIONIST, listOf(ada)) { done = true }
        assertFalse(done)
        assertFalse(vm.saving.value)
        assertTrue(store.batches.isEmpty())
        assertTrue(events.isEmpty())
        val box = vm.conflicts.value
        assertNotNull(box)
        assertEquals("Scheduling conflict — Ada is already booked", box!!.heading)
        assertEquals(listOf("2026-03-04: overlaps \"Midday\" (10:00–14:00)"), box.lines)
        vm.clearConflicts()
        assertNull(vm.conflicts.value)
    }

    @Test fun anOvernightShiftFromYesterdayBlocksTheEarlyHoursOfToday() = runTest {
        toasts()
        store.stored += shift("night", date = "2026-03-03", start = "22:00", end = "06:00", label = "Night").copy(endsNextDay = true)
        val vm = vm()
        vm.submitNew(form(start = "05:00", end = "09:00"), Role.RECEPTIONIST, listOf(ada)) {}
        assertNotNull(vm.conflicts.value)
        assertTrue(store.batches.isEmpty())
    }

    @Test fun aNewAttemptClearsTheOldConflictBox() = runTest {
        toasts()
        store.stored += shift("old", date = "2026-03-04", start = "10:00", end = "14:00")
        val vm = vm()
        vm.submitNew(form(), Role.RECEPTIONIST, listOf(ada)) {}
        assertNotNull(vm.conflicts.value)
        vm.submitNew(form(start = "16:00", end = "20:00"), Role.RECEPTIONIST, listOf(ada)) {}
        assertNull(vm.conflicts.value)
        assertEquals(1, store.batches.size)
    }

    // ---- validation toasts ----

    @Test fun noStaffGivesTheSelectStaffToastAndNothingElse() = runTest {
        val events = toasts()
        val vm = vm()
        var done = false
        vm.submitNew(form(staffId = null), Role.RECEPTIONIST, emptyList()) { done = true }
        assertFalse(done)
        assertEquals("Select a staff member", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertTrue(store.batches.isEmpty())
    }

    @Test fun aBlankLabelGivesTheLabelToastWithItsHint() = runTest {
        val events = toasts()
        vm().submitNew(form(label = "  "), Role.RECEPTIONIST, listOf(ada)) {}
        assertEquals("Give the shift a label", events.single().title)
        assertEquals("e.g. Morning Shift, Night Shift", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
    }

    @Test fun weeklyWithoutADayGivesTheWeekdayToast() = runTest {
        val events = toasts()
        vm().submitNew(form(type = RecurrenceType.WEEKLY), Role.RECEPTIONIST, listOf(ada)) {}
        assertEquals("Pick at least one weekday", events.single().message)
        assertTrue(store.batches.isEmpty())
    }

    // ---- failures ----

    @Test fun aWriteFailureShowsTheErrorToastAndKeepsTheSheetOpen() = runTest {
        val events = toasts()
        store.failWrites = IllegalStateException("Permission denied")
        val vm = vm()
        var done = false
        vm.submitNew(form(), Role.RECEPTIONIST, listOf(ada)) { done = true }
        assertFalse(done)
        assertFalse(vm.saving.value)
        assertEquals("Error", events.single().title)
        assertEquals("Permission denied", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
    }

    @Test fun signedOutShowsNotSignedIn() = runTest {
        val events = toasts()
        vm(signedIn = false).submitNew(form(), Role.RECEPTIONIST, listOf(ada)) {}
        assertEquals("Error", events.single().title)
        assertEquals("Not signed in", events.single().message)
        assertTrue(store.batches.isEmpty())
    }

    // ---- editing ----

    @Test fun savingAnEditUpdatesTheShiftAndShowsShiftUpdated() = runTest {
        val events = toasts()
        val original = shift("s1")
        store.stored += original
        val vm = vm()
        var done = false
        vm.submitEdit(original, form(label = "Early", start = "06:00", end = "14:00"), listOf(ada), { done = true })
        assertTrue(done)
        assertEquals("s1", store.updates.single().first)
        assertEquals("Early", store.updates.single().second["label"])
        assertEquals("06:00", store.updates.single().second["startTime"])
        assertEquals("Shift Updated", events.single().message)
        assertEquals(listOf("shift_updated"), audit.actions)
    }

    @Test fun anEditDoesNotConflictWithItself() = runTest {
        toasts()
        val original = shift("s1", start = "08:00", end = "16:00")
        store.stored += original
        val vm = vm()
        vm.submitEdit(original, form(start = "09:00", end = "17:00"), listOf(ada)) {}
        assertNull(vm.conflicts.value)
        assertEquals(1, store.updates.size)
    }

    @Test fun anEditThatClashesWithAnotherShiftShowsTheBox() = runTest {
        val events = toasts()
        val original = shift("s1", start = "08:00", end = "12:00")
        store.stored += original
        store.stored += shift("s2", start = "13:00", end = "18:00", label = "Evening")
        val vm = vm()
        var done = false
        vm.submitEdit(original, form(start = "08:00", end = "15:00"), listOf(ada)) { done = true }
        assertFalse(done)
        assertTrue(store.updates.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(listOf("2026-03-04: overlaps \"Evening\" (13:00–18:00)"), vm.conflicts.value!!.lines)
    }

    @Test fun anEditIgnoresTheRecurrenceChoice() = runTest {
        toasts()
        val original = shift("s1")
        store.stored += original
        vm().submitEdit(original, form(type = RecurrenceType.WEEKLY), listOf(ada)) {}
        assertEquals(1, store.updates.size)
    }

    // ---- cancelling ----

    @Test fun cancellingOneShiftShowsShiftCancelled() = runTest {
        val events = toasts()
        var done = false
        vm().cancelThisShift(shift("s1")) { done = true }
        assertTrue(done)
        assertEquals("cancelled", store.updates.single().second["status"])
        assertEquals("Shift Cancelled", events.single().message)
        assertEquals(listOf("shift_cancelled"), audit.actions)
    }

    @Test fun cancellingTheSeriesCancelsThisAndFutureShifts() = runTest {
        val events = toasts()
        store.stored += shift("a", date = "2026-03-02", seriesId = "ser")
        store.stored += shift("b", date = "2026-03-04", seriesId = "ser")
        store.stored += shift("c", date = "2026-03-05", seriesId = "ser")
        store.stored += shift("x", date = "2026-03-05", seriesId = "other")
        var done = false
        vm().cancelSeries(shift("b", date = "2026-03-04", seriesId = "ser")) { done = true }
        assertTrue(done)
        assertEquals(listOf("b", "c"), store.batchUpdates.single().first)
        assertEquals("Series Cancelled", events.single().title)
        assertEquals("This and every future shift in the series was cancelled.", events.single().message)
        assertEquals(listOf("shift_series_cancelled"), audit.actions)
    }

    @Test fun cancelFailureShowsTheErrorToast() = runTest {
        val events = toasts()
        store.failWrites = IllegalStateException("offline")
        var done = false
        vm().cancelThisShift(shift("s1")) { done = true }
        assertFalse(done)
        assertEquals("Error", events.single().title)
        assertEquals("offline", events.single().message)
    }

    // ---- Cancel Series offer ----

    @Test fun cancelSeriesIsOfferedOnlyWhenTheSeriesHasMoreThanOneShift() = runTest {
        store.stored += shift("a", seriesId = "many")
        store.stored += shift("b", date = "2026-03-05", seriesId = "many")
        store.stored += shift("solo", seriesId = "one")
        val vm = vm()

        vm.checkSeries(shift("a", seriesId = "many"))
        assertEquals("a", vm.seriesOfferFor.value)

        vm.checkSeries(shift("solo", seriesId = "one"))
        assertNull(vm.seriesOfferFor.value)

        vm.checkSeries(shift("plain", seriesId = null))
        assertNull(vm.seriesOfferFor.value)

        vm.checkSeries(shift("a", seriesId = "many"))
        vm.clearSeriesOffer()
        assertNull(vm.seriesOfferFor.value)
    }

    @Test fun cancelledShiftsDoNotCountTowardsTheSeriesOffer() = runTest {
        store.stored += shift("a", seriesId = "s")
        store.stored += shift("b", date = "2026-03-05", seriesId = "s").copy(status = "cancelled")
        val vm = vm()
        vm.checkSeries(shift("a", seriesId = "s"))
        assertNull(vm.seriesOfferFor.value)
    }
}
