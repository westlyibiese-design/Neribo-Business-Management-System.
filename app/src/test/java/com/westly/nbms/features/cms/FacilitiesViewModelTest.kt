package com.westly.nbms.features.cms

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.feature.NavRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A fake repository: keeps the document, applies every list operation with the real rules and re-emits the document. */
internal class FakeCmsSource(initial: List<FacilityItem> = emptyList(), exists: Boolean = true) : CmsSource {
    val flow = MutableStateFlow<Resource<CmsRawDoc>>(Resource.Success(CmsRawDoc(exists, initial.map { it.toMap() })))
    val ops = mutableListOf<ListOp<*>>()
    val auditActions = mutableListOf<String>()
    val limitsSeen = mutableListOf<Int>()
    var failWith: Exception? = null
    var forced: MutateResult? = null
    var gate: CompletableDeferred<Unit>? = null

    val items: List<FacilityItem> get() = FacilityItem.parseList((flow.value as Resource.Success).data.data)

    override fun observeDoc(docId: String): Flow<Resource<CmsRawDoc>> = flow

    override suspend fun saveObject(docId: String, data: Map<String, Any?>) = Unit

    override suspend fun <T> mutateList(
        docId: String,
        op: ListOp<T>,
        codec: ListCodec<T>,
        limit: Int,
        auditAction: String,
        normalize: (List<T>) -> List<T>
    ): MutateResult {
        gate?.await()
        failWith?.let { throw it }
        ops += op
        limitsSeen += limit
        forced?.let { return it }
        val current = codec.parse((flow.value as Resource.Success).data.data)
        return when (val outcome = applyListOp(current, op, codec.idOf, limit)) {
            is ListOpOutcome.Changed -> {
                flow.value = Resource.Success(CmsRawDoc(true, normalize(outcome.list).map(codec.toMap)))
                auditActions += auditAction
                MutateResult.Done
            }
            ListOpOutcome.Unchanged -> MutateResult.Done
            ListOpOutcome.AlreadyChanged -> MutateResult.AlreadyChanged
            ListOpOutcome.LimitReached -> MutateResult.LimitReached
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FacilitiesViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val toast = ToastController()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun vm(source: CmsSource) = FacilitiesViewModel(source, toast, emptySet())

    private fun fac(n: Int) = FacilityItem("id$n", "Facility $n", "", "Description $n")
    private fun facs(n: Int) = List(n) { fac(it) }

    // ── loading ──

    @Test fun showsTheStoredFacilitiesInOrder() = runTest {
        val vm = vm(FakeCmsSource(facs(3)))
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertEquals(listOf("id0", "id1", "id2"), s.facilities.map { it.id })
    }

    @Test fun aMissingDocumentIsAnEmptyListNotAnError() = runTest {
        val source = FakeCmsSource(exists = false)
        source.flow.value = Resource.Success(CmsRawDoc(exists = false, data = null))
        val s = vm(source).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertTrue(s.facilities.isEmpty())
    }

    @Test fun isLoadingUntilTheFirstAnswer() = runTest {
        val source = FakeCmsSource()
        source.flow.value = Resource.Loading
        assertTrue(vm(source).state.value.loading)
    }

    @Test fun aSavedFacilityShowsUpWithoutAReload() = runTest {
        val source = FakeCmsSource(facs(1))
        val vm = vm(source)
        // another phone adds one: the live document changes
        source.flow.value = Resource.Success(CmsRawDoc(true, facs(2).map { it.toMap() }))
        assertEquals(2, vm.state.value.facilities.size)
    }

    // ── load-error guard ──

    @Test fun aLoadErrorBlocksEverySaveWithTheCantSaveYetToast() = runTest {
        val source = FakeCmsSource(facs(2))
        source.flow.value = Resource.Error("offline")
        val vm = vm(source)
        val events = toasts()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)

        val results = mutableListOf<Boolean>()
        vm.saveFacility(null, "Pool", "", "Nice") { results += it }
        vm.saveFacility("id0", "Renamed", "", "Nice") { results += it }
        vm.deleteFacility("id0") { results += it }
        vm.moveFacility("id1", -1) { results += it }

        assertEquals(listOf(false, false, false, false), results)
        assertTrue(source.ops.isEmpty())
        assertEquals(4, events.size)
        events.forEach {
            assertEquals("Can't save yet", it.title)
            assertEquals("Facilities failed to load, so saving now could overwrite them with incomplete data. Reload the page first.", it.message)
            assertEquals(ToastType.Error, it.type)
        }
    }

    @Test fun theLoadErrorClearsWhenTheListLoadsAgainAndSavingWorks() = runTest {
        val source = FakeCmsSource()
        source.flow.value = Resource.Error("offline")
        val vm = vm(source)
        source.flow.value = Resource.Success(CmsRawDoc(true, emptyList<Any>()))
        assertFalse(vm.state.value.loadFailed)
        var ok = false
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertTrue(ok)
    }

    @Test fun savingWhileStillLoadingIsRefusedWithAToast() = runTest {
        val source = FakeCmsSource()
        source.flow.value = Resource.Loading
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
    }

    // ── add ──

    @Test fun addingNeedsANameAndADescription() = runTest {
        val source = FakeCmsSource()
        val vm = vm(source)
        val events = toasts()
        val results = mutableListOf<Boolean>()
        vm.saveFacility(null, "   ", "", "Nice") { results += it }
        vm.saveFacility(null, "Pool", "", "  ") { results += it }
        assertEquals(listOf(false, false), results)
        assertTrue(source.ops.isEmpty())
        assertEquals(2, events.size)
        assertFalse(vm.state.value.saving)
        // a good one still works afterwards (the in-flight guard was released)
        var ok = false
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertTrue(ok)
    }

    @Test fun addingASavesTrimmedFacilityAtTheEndWithAFreshIdAndShowsFacilityAdded() = runTest {
        val source = FakeCmsSource(facs(1))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.saveFacility(null, "  Infinity Pool  ", " https://x/y.jpg ", "  Rooftop view  ") { ok = it }

        assertTrue(ok)
        val added = source.items.last()
        assertEquals(2, source.items.size)
        assertEquals("Infinity Pool", added.name)
        assertEquals("https://x/y.jpg", added.image)
        assertEquals("Rooftop view", added.description)
        assertTrue(Regex("[a-z0-9]{8}").matches(added.id))
        assertEquals(listOf("facilities_updated"), source.auditActions)
        assertEquals(listOf(CmsLimits.FACILITIES), source.limitsSeen)
        assertEquals("Facility Added", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
        assertEquals(2, vm.state.value.facilities.size)
        assertFalse(vm.state.value.saving)
    }

    @Test fun theImageIsOptional() = runTest {
        val source = FakeCmsSource()
        val vm = vm(source)
        var ok = false
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertTrue(ok)
        assertEquals("", source.items.single().image)
    }

    // ── limit ──

    @Test fun theTwentyFirstFacilityIsRefusedWithoutAnyWrite() = runTest {
        val source = FakeCmsSource(facs(20))
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveFacility(null, "One too many", "", "Nope") { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals("You've reached the maximum of 20 facilities. Delete one to add another.", events.single().message)
        assertFalse(vm.state.value.saving)
    }

    @Test fun theTwentiethFacilityIsAccepted() = runTest {
        val source = FakeCmsSource(facs(19))
        var ok = false
        vm(source).saveFacility(null, "Number twenty", "", "Fine") { ok = it }
        assertTrue(ok)
        assertEquals(20, source.items.size)
    }

    @Test fun aLimitAnswerFromTheRepositoryShowsTheLimitToast() = runTest {
        val source = FakeCmsSource(facs(3))
        source.forced = MutateResult.LimitReached
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertFalse(ok)
        assertEquals("You've reached the maximum of 20 facilities. Delete one to add another.", events.single().message)
    }

    @Test fun editingAFullListIsStillAllowed() = runTest {
        val source = FakeCmsSource(facs(20))
        var ok = false
        vm(source).saveFacility("id5", "Renamed", "", "Changed") { ok = it }
        assertTrue(ok)
        assertEquals("Renamed", source.items[5].name)
    }

    // ── edit ──

    @Test fun editingReplacesTheFacilityInPlaceAndShowsFacilityUpdated() = runTest {
        val source = FakeCmsSource(facs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.saveFacility("id1", " New name ", "pic", " New description ") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("id0", "id1", "id2"), source.items.map { it.id })
        assertEquals(FacilityItem("id1", "New name", "pic", "New description"), source.items[1])
        assertEquals("Facility Updated", events.single().message)
        assertTrue(source.ops.single() is ListOp.Replace<*>)
    }

    @Test fun editingAFacilityThatSomeoneElseDeletedShowsTheAlreadyChangedToast() = runTest {
        val source = FakeCmsSource(facs(2))
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveFacility("ghost", "Name", "", "Desc") { ok = it }
        assertFalse(ok)
        assertEquals("That item was already changed by someone else.", events.single().message)
        assertEquals(2, source.items.size)
        assertFalse(vm.state.value.saving)
    }

    // ── delete ──

    @Test fun deletingRemovesOnlyThatFacilityAndShowsFacilityDeleted() = runTest {
        val source = FakeCmsSource(facs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.deleteFacility("id1") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("id0", "id2"), source.items.map { it.id })
        assertEquals("Facility Deleted", events.single().message)
        assertEquals(listOf("facilities_updated"), source.auditActions)
    }

    @Test fun deletingAFacilityThatIsAlreadyGoneShowsTheAlreadyChangedToast() = runTest {
        val source = FakeCmsSource(facs(1))
        val vm = vm(source)
        val events = toasts()
        vm.deleteFacility("ghost")
        assertEquals("That item was already changed by someone else.", events.single().message)
    }

    // ── reorder ──

    @Test fun reorderingIsSilentAndMovesByOnePlace() = runTest {
        val source = FakeCmsSource(facs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.moveFacility("id2", -1) { ok = it }
        assertTrue(ok)
        assertEquals(listOf("id0", "id2", "id1"), source.items.map { it.id })
        vm.moveFacility("id0", 1)
        assertEquals(listOf("id2", "id0", "id1"), source.items.map { it.id })
        assertTrue(events.isEmpty())
        assertEquals(listOf("facilities_updated", "facilities_updated"), source.auditActions)
    }

    @Test fun movingAnEndItemOffTheListChangesNothingAndStaysSilent() = runTest {
        val source = FakeCmsSource(facs(2))
        val vm = vm(source)
        val events = toasts()
        vm.moveFacility("id0", -1)
        vm.moveFacility("id1", 1)
        assertEquals(listOf("id0", "id1"), source.items.map { it.id })
        assertTrue(events.isEmpty())
        assertTrue(source.auditActions.isEmpty())
    }

    // ── busy state and failures ──

    @Test fun whileASaveIsRunningTheStateIsSavingAndASecondTapIsIgnored() = runTest {
        val source = FakeCmsSource(facs(1))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val vm = vm(source)
        val events = toasts()

        val results = mutableListOf<Boolean>()
        vm.saveFacility(null, "Pool", "", "Nice") { results += it }
        assertTrue(vm.state.value.saving)

        vm.saveFacility(null, "Pool", "", "Nice") { results += it }   // double tap
        vm.deleteFacility("id0") { results += it }
        vm.moveFacility("id0", 1) { results += it }
        assertEquals(listOf(false, false, false), results)
        assertTrue(events.isEmpty())

        gate.complete(Unit)
        assertEquals(listOf(false, false, false, true), results)
        assertFalse(vm.state.value.saving)
        assertEquals(1, source.ops.size)
        assertEquals(2, source.items.size)
    }

    @Test fun aFailedSaveShowsTheErrorToastAndFreesTheScreenAgain() = runTest {
        val source = FakeCmsSource(facs(1))
        source.failWith = IllegalStateException("You don't have access to this data.")
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertEquals("You don't have access to this data.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertFalse(vm.state.value.saving)
        assertEquals(1, vm.state.value.facilities.size)

        source.failWith = null
        vm.saveFacility(null, "Pool", "", "Nice") { ok = it }
        assertTrue(ok)
    }

    // ── rules and registration ──

    @Test fun pageTextsMatchTheSpecification() {
        assertEquals("Facilities (0/20)", FacilitiesRules.heading(0))
        assertEquals("Facilities (20/20)", FacilitiesRules.heading(20))
        assertEquals(
            "Are you sure you want to delete \"Pool\"? This will remove it from the public website immediately.",
            FacilitiesRules.deleteBody("Pool")
        )
        assertTrue(FacilitiesRules.canAdd(19))
        assertFalse(FacilitiesRules.canAdd(20))
        assertTrue(FacilitiesRules.isValid("a", "b"))
        assertFalse(FacilitiesRules.isValid("", "b"))
        assertFalse(FacilitiesRules.isValid("a", " "))
    }

    @Test fun theFeatureRegistersOnlyTheFacilitiesRouteForSuperAdminAndManager() {
        val feature = CmsFeature()
        assertEquals("cms", feature.id)
        assertEquals(listOf("facilities"), feature.screens.map { it.route })
        val nav = feature.nav.single()
        assertEquals("facilities", nav.route)
        assertEquals("Facilities", nav.label)
        assertEquals(260, nav.order)
        assertNull(nav.group)
        assertNull(nav.module)
        assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER), nav.roles)
    }

    @Test fun theRouteGuardAgreesWithTheNavEntry() {
        assertTrue(NavRules.canOpen("facilities", Role.MANAGER, emptySet()))
        assertTrue(NavRules.canOpen("facilities", Role.SUPER_ADMIN, emptySet()))
        assertFalse(NavRules.canOpen("facilities", Role.RECEPTIONIST, emptySet()))
        assertFalse(NavRules.canOpen("facilities", Role.ACCOUNTANT, emptySet()))
    }
}
