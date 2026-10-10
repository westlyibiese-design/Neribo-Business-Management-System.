package com.westly.nbms.features.gym

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A fake repository: keeps the stored `data` map, applies every save to it and re-emits the parsed content. */
internal class FakeGymContentSource(initial: GymContent = GymContent()) : GymContentSource {
    val stored: MutableMap<String, Any?> = GymSection.entries.associate { it.key to gymSectionValue(it, initial) }.toMutableMap()
    val flow = MutableStateFlow<Resource<GymContent>>(Resource.Success(GymContent.parse(stored)))
    val saves = mutableListOf<Pair<GymSection, Any>>()
    var failWith: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override fun observe(): Flow<Resource<GymContent>> = flow

    override suspend fun saveSection(section: GymSection, value: Any) {
        gate?.await()
        failWith?.let { throw it }
        saves += section to value
        stored[section.key] = value
        flow.value = Resource.Success(GymContent.parse(stored))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GymContentViewModelTest {
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

    private fun vm(source: GymContentSource) = GymContentViewModel(source, toast, emptySet())

    private fun equip(n: Int) = List(n) { EquipmentItem("e$it", "Item $it", description = "d$it") }
    private fun packs(n: Int) = List(n) { PackageItem("p$it", "Pack $it") }
    private fun progs(n: Int) = List(n) { ProgramItem("g$it", "Prog $it", "d$it") }

    private class Done { var calls = mutableListOf<Boolean>(); val cb: (Boolean) -> Unit = { calls += it } }

    // ── loading ──

    @Test fun startsLoadingThenShowsTheContent() = runTest {
        val source = FakeGymContentSource(GymContent(about = "Hi"))
        source.flow.value = Resource.Loading
        val vm = vm(source)
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)

        source.flow.value = Resource.Success(GymContent(about = "Hi"))
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals("Hi", vm.state.value.content.about)
        assertFalse(vm.state.value.saving)
    }

    @Test fun aMissingDocumentIsEmptyContentNotAFailure() = runTest {
        val vm = vm(FakeGymContentSource())
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(GymContent.DEFAULT_HOURS, vm.state.value.content.hours)
    }

    @Test fun theInjectedImageProvidersAreExposed() = runTest {
        assertTrue(vm(FakeGymContentSource()).imageProviders.isEmpty())
    }

    // ── load-error guard ──

    @Test fun aLoadErrorBlocksEverySaveWithTheCantSaveYetToast() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(2), packages = packs(2), programs = progs(2), gallery = listOf("u")))
        source.flow.value = Resource.Error("denied")
        val vm = vm(source)
        val events = toasts()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)

        val done = Done()
        vm.saveAbout("x", done.cb)
        vm.saveHours(GymContent.DEFAULT_HOURS, done.cb)
        vm.saveEquipmentItem(EquipmentItem("", "A", description = "a"), done.cb)
        vm.deleteEquipmentItem("e0", done.cb)
        vm.moveEquipmentItem("e1", -1, done.cb)
        vm.savePackage(PackageItem("", "A"), done.cb)
        vm.deletePackage("p0", done.cb)
        vm.movePackage("p1", -1, done.cb)
        vm.saveProgram(ProgramItem("", "A", "a"), done.cb)
        vm.deleteProgram("g0", done.cb)
        vm.moveProgram("g1", -1, done.cb)
        vm.addGalleryImage("http://x", done.cb)
        vm.removeGalleryImage(0, done.cb)

        assertEquals(13, done.calls.size)
        assertTrue(done.calls.none { it })
        assertTrue(source.saves.isEmpty())
        assertEquals(13, events.size)
        assertTrue(events.all { it.title == "Can't save yet" && it.type == ToastType.Error })
        assertTrue(events.all { it.message == "Gym content failed to load, so saving now could overwrite it with incomplete data. Reload the page first." })
        assertFalse(vm.state.value.saving)
    }

    @Test fun aFlowThatThrowsCountsAsALoadFailure() = runTest {
        val source = object : GymContentSource {
            override fun observe(): Flow<Resource<GymContent>> = kotlinx.coroutines.flow.flow { throw IllegalStateException("boom") }
            override suspend fun saveSection(section: GymSection, value: Any) = Unit
        }
        val vm = vm(source)
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
    }

    @Test fun theGuardClearsWhenTheContentLoadsLater() = runTest {
        val source = FakeGymContentSource()
        source.flow.value = Resource.Error("denied")
        val vm = vm(source)
        source.flow.value = Resource.Success(GymContent())
        runCurrent()
        assertFalse(vm.state.value.loadFailed)
        val done = Done()
        vm.saveAbout("ok", done.cb)
        assertEquals(listOf(true), done.calls)
    }

    @Test fun nothingIsSavedWhileTheContentIsStillLoading() = runTest {
        val source = FakeGymContentSource()
        source.flow.value = Resource.Loading
        val vm = vm(source)
        val events = toasts()
        val done = Done()
        vm.saveEquipmentItem(EquipmentItem("n", "A", description = "a"), done.cb)
        assertEquals(listOf(false), done.calls)
        assertTrue(source.saves.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
    }

    // ── saving flag, toasts ──

    @Test fun savingShowsTheSectionToastAndCallsOnDoneTrue() = runTest {
        val source = FakeGymContentSource()
        val vm = vm(source)
        val events = toasts()
        val done = Done()
        vm.saveAbout("  Welcome  ", done.cb)
        assertEquals(listOf(GymSection.ABOUT to "Welcome"), source.saves)
        assertEquals(listOf(true), done.calls)
        assertEquals(ToastType.Success, events.single().type)
        assertEquals("About Section Updated", events.single().message)
        assertFalse(vm.state.value.saving)
        assertEquals("Welcome", vm.state.value.content.about)
    }

    @Test fun aFailedSaveShowsTheErrorToastWithTheMessage() = runTest {
        val source = FakeGymContentSource()
        source.failWith = GymException("No access")
        val vm = vm(source)
        val events = toasts()
        val done = Done()
        vm.saveAbout("x", done.cb)
        assertEquals(listOf(false), done.calls)
        assertEquals("Error", events.single().title)
        assertEquals("No access", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertFalse(vm.state.value.saving)
        assertEquals("", vm.state.value.content.about)
    }

    @Test fun aFailureWithoutAMessageStillShowsAnErrorToast() = runTest {
        val source = FakeGymContentSource()
        source.failWith = RuntimeException()
        val vm = vm(source)
        val events = toasts()
        vm.saveAbout("x")
        assertEquals("Error", events.single().title)
        assertTrue(events.single().message.isNotBlank())
    }

    @Test fun whileSavingFurtherCallsAreIgnored() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(2)))
        source.gate = CompletableDeferred()
        val vm = vm(source)
        val events = toasts()
        val first = Done()
        val second = Done()
        val third = Done()

        vm.saveAbout("first", first.cb)
        assertTrue(vm.state.value.saving)
        vm.saveHours(GymContent.DEFAULT_HOURS, second.cb)
        vm.deleteEquipmentItem("e0", third.cb)
        assertEquals(listOf(false), second.calls)
        assertEquals(listOf(false), third.calls)
        assertTrue(first.calls.isEmpty())

        source.gate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(listOf(true), first.calls)
        assertEquals(listOf(GymSection.ABOUT), source.saves.map { it.first })
        assertEquals(listOf("About Section Updated"), events.map { it.message })

        val after = Done()
        vm.deleteEquipmentItem("e0", after.cb)
        assertEquals(listOf(true), after.calls)
    }

    @Test fun theSavingFlagIsClearedAfterAFailureToo() = runTest {
        val source = FakeGymContentSource()
        source.failWith = GymException("bad")
        val vm = vm(source)
        vm.saveAbout("x")
        source.failWith = null
        val done = Done()
        vm.saveAbout("y", done.cb)
        assertEquals(listOf(true), done.calls)
    }

    @Test fun everySectionShowsItsOwnSuccessToast() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(2), packages = packs(2), programs = progs(2), gallery = listOf("a", "b")))
        val vm = vm(source)
        val events = toasts()
        vm.saveAbout("a")
        vm.saveHours(GymContent.DEFAULT_HOURS)
        vm.saveEquipmentItem(EquipmentItem("", "New", description = "d"))
        vm.savePackage(PackageItem("", "New"))
        vm.saveProgram(ProgramItem("", "New", "d"))
        vm.addGalleryImage("http://x/new.jpg")
        assertEquals(
            listOf(
                "About Section Updated", "Operating Hours Updated", "Equipment & Services Updated",
                "Membership Packages Updated", "Programs Updated", "Gym Gallery Updated"
            ),
            events.map { it.message }
        )
    }

    // ── hours ──

    @Test fun hoursNeedExactlySevenRows() = runTest {
        val source = FakeGymContentSource()
        val vm = vm(source)
        val done = Done()
        vm.saveHours(GymContent.DEFAULT_HOURS.take(6), done.cb)
        assertEquals(listOf(false), done.calls)
        assertTrue(source.saves.isEmpty())

        val rows = GymContent.DEFAULT_HOURS.mapIndexed { i, r -> if (i == 6) r.copy(closed = true) else r }
        vm.saveHours(rows, done.cb)
        assertEquals(listOf(false, true), done.calls)
        assertEquals(rows, vm.state.value.content.hours)
        assertEquals(7, (source.saves.single().second as List<*>).size)
    }

    // ── equipment ──

    @Test fun addingEquipmentUsesTheGivenNewIdAndAppends() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(2)))
        val vm = vm(source)
        val done = Done()
        vm.saveEquipmentItem(EquipmentItem("new12345", "  Rower ", " http://x/r.jpg ", "  Cardio  ", "Waves"), done.cb)
        assertEquals(listOf(true), done.calls)
        assertEquals(listOf("e0", "e1", "new12345"), vm.state.value.content.equipment.map { it.id })
        assertEquals(EquipmentItem("new12345", "Rower", "http://x/r.jpg", "Cardio", "Waves"), vm.state.value.content.equipment.last())
        val stored = source.saves.single().second as List<*>
        assertEquals(mapOf("id" to "new12345", "name" to "Rower", "image" to "http://x/r.jpg", "description" to "Cardio", "icon" to "Waves"), stored.last())
    }

    @Test fun aBlankIdGetsANewEightCharacterId() = runTest {
        val vm = vm(FakeGymContentSource())
        vm.saveEquipmentItem(EquipmentItem("", "A", description = "a"))
        assertEquals(8, vm.state.value.content.equipment.single().id.length)
    }

    @Test fun editingEquipmentReplacesItInPlace() = runTest {
        val vm = vm(FakeGymContentSource(GymContent(equipment = equip(3))))
        vm.saveEquipmentItem(EquipmentItem("e1", "Edited", description = "changed", icon = "Zap"))
        assertEquals(listOf("Item 0", "Edited", "Item 2"), vm.state.value.content.equipment.map { it.name })
        assertEquals("Zap", vm.state.value.content.equipment[1].icon)
    }

    @Test fun anUnknownIconIsSavedAsDumbbell() = runTest {
        val vm = vm(FakeGymContentSource())
        vm.saveEquipmentItem(EquipmentItem("a", "A", description = "a", icon = "Rocket"))
        assertEquals("Dumbbell", vm.state.value.content.equipment.single().icon)
    }

    @Test fun equipmentNeedsANameAndADescription() = runTest {
        val source = FakeGymContentSource()
        val vm = vm(source)
        val events = toasts()
        val done = Done()
        vm.saveEquipmentItem(EquipmentItem("a", "  ", description = "d"), done.cb)
        vm.saveEquipmentItem(EquipmentItem("a", "N", description = " "), done.cb)
        assertEquals(listOf(false, false), done.calls)
        assertTrue(source.saves.isEmpty())
        assertTrue(events.all { it.type == ToastType.Error })
        assertFalse(vm.state.value.saving)
    }

    @Test fun equipmentIsLimitedToTwentyButEditsStillWork() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(20)))
        val vm = vm(source)
        val events = toasts()
        val refused = Done()
        vm.saveEquipmentItem(EquipmentItem("extra", "One more", description = "d"), refused.cb)
        assertEquals(listOf(false), refused.calls)
        assertTrue(source.saves.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
        assertFalse(vm.state.value.saving)

        val edit = Done()
        vm.saveEquipmentItem(EquipmentItem("e5", "Edited", description = "d"), edit.cb)
        assertEquals(listOf(true), edit.calls)
        assertEquals(20, vm.state.value.content.equipment.size)

        val nineteen = vm(FakeGymContentSource(GymContent(equipment = equip(19))))
        nineteen.saveEquipmentItem(EquipmentItem("extra", "Twentieth", description = "d"))
        assertEquals(20, nineteen.state.value.content.equipment.size)
    }

    @Test fun deletingEquipmentById() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(3)))
        val vm = vm(source)
        val done = Done()
        vm.deleteEquipmentItem("e1", done.cb)
        assertEquals(listOf("e0", "e2"), vm.state.value.content.equipment.map { it.id })
        assertEquals(listOf(true), done.calls)

        vm.deleteEquipmentItem("nope", done.cb)
        assertEquals(listOf(true, false), done.calls)
        assertEquals(1, source.saves.size)
    }

    @Test fun movingEquipmentUpAndDownSavesButTheEndsDoNothing() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(3)))
        val vm = vm(source)
        val done = Done()
        vm.moveEquipmentItem("e2", -1, done.cb)
        assertEquals(listOf("e0", "e2", "e1"), vm.state.value.content.equipment.map { it.id })
        vm.moveEquipmentItem("e0", 1, done.cb)
        assertEquals(listOf("e2", "e0", "e1"), vm.state.value.content.equipment.map { it.id })
        assertEquals(listOf(true, true), done.calls)

        vm.moveEquipmentItem("e2", -1, done.cb)
        vm.moveEquipmentItem("e1", 1, done.cb)
        vm.moveEquipmentItem("ghost", 1, done.cb)
        assertEquals(listOf(true, true, false, false, false), done.calls)
        assertEquals(2, source.saves.size)
    }

    // ── packages ──

    @Test fun aNewPackageDefaultsToPriceZeroMonthlyNoFeaturesNotPopular() = runTest {
        val source = FakeGymContentSource()
        val vm = vm(source)
        vm.savePackage(PackageItem("", "  Gold "))
        val p = vm.state.value.content.packages.single()
        assertEquals("Gold", p.name)
        assertEquals(0.0, p.price, 0.0)
        assertEquals("Monthly", p.duration)
        assertTrue(p.features.isEmpty())
        assertFalse(p.popular)
        assertEquals(8, p.id.length)
        assertEquals(setOf("id", "name", "price", "duration", "features", "popular"), ((source.saves.single().second as List<*>).single() as Map<*, *>).keys)
    }

    @Test fun packageFieldsAreCleanedBeforeSaving() = runTest {
        val vm = vm(FakeGymContentSource())
        vm.savePackage(PackageItem("p", "Gold", -5.0, "  ", listOf(" Pool ", "", "  ", "Sauna"), true))
        val p = vm.state.value.content.packages.single()
        assertEquals(0.0, p.price, 0.0)
        assertEquals("Monthly", p.duration)
        assertEquals(listOf("Pool", "Sauna"), p.features)
        assertTrue(p.popular)

        vm.savePackage(PackageItem("p", "Gold", Double.NaN))
        assertEquals(0.0, vm.state.value.content.packages.single().price, 0.0)
        vm.savePackage(PackageItem("p", "Gold", 15_000.0, "Quarterly"))
        assertEquals(15_000.0, vm.state.value.content.packages.single().price, 0.0)
        assertEquals("Quarterly", vm.state.value.content.packages.single().duration)
    }

    @Test fun aPackageNeedsANameAndIsLimitedToTwelve() = runTest {
        val source = FakeGymContentSource(GymContent(packages = packs(12)))
        val vm = vm(source)
        val done = Done()
        vm.savePackage(PackageItem("x", "  "), done.cb)
        vm.savePackage(PackageItem("new", "13th"), done.cb)
        assertEquals(listOf(false, false), done.calls)
        assertTrue(source.saves.isEmpty())
        vm.savePackage(PackageItem("p3", "Renamed"), done.cb)
        assertEquals(listOf(false, false, true), done.calls)
        assertEquals("Renamed", vm.state.value.content.packages[3].name)
        assertEquals(12, vm.state.value.content.packages.size)

        val eleven = vm(FakeGymContentSource(GymContent(packages = packs(11))))
        eleven.savePackage(PackageItem("new", "12th"))
        assertEquals(12, eleven.state.value.content.packages.size)
    }

    @Test fun deletingAndMovingPackages() = runTest {
        val source = FakeGymContentSource(GymContent(packages = packs(3)))
        val vm = vm(source)
        val done = Done()
        vm.movePackage("p0", 1, done.cb)
        assertEquals(listOf("p1", "p0", "p2"), vm.state.value.content.packages.map { it.id })
        vm.movePackage("p1", -1, done.cb)
        assertEquals(listOf(true, false), done.calls)
        vm.deletePackage("p0", done.cb)
        assertEquals(listOf("p1", "p2"), vm.state.value.content.packages.map { it.id })
        vm.deletePackage("p0", done.cb)
        assertEquals(listOf(true, false, true, false), done.calls)
        assertEquals(listOf(GymSection.PACKAGES, GymSection.PACKAGES), source.saves.map { it.first })
    }

    // ── programs ──

    @Test fun programsNeedANameAndADescriptionAndAreLimitedToTwenty() = runTest {
        val source = FakeGymContentSource(GymContent(programs = progs(20)))
        val vm = vm(source)
        val done = Done()
        vm.saveProgram(ProgramItem("x", "", "d"), done.cb)
        vm.saveProgram(ProgramItem("x", "N", " "), done.cb)
        vm.saveProgram(ProgramItem("new", "21st", "d"), done.cb)
        assertEquals(listOf(false, false, false), done.calls)
        assertTrue(source.saves.isEmpty())
        vm.saveProgram(ProgramItem("g0", " Renamed ", " new text ", " http://x/i.jpg "), done.cb)
        assertEquals(true, done.calls.last())
        assertEquals(ProgramItem("g0", "Renamed", "new text", "http://x/i.jpg"), vm.state.value.content.programs[0])
    }

    @Test fun deletingAndMovingPrograms() = runTest {
        val source = FakeGymContentSource(GymContent(programs = progs(3)))
        val vm = vm(source)
        val done = Done()
        vm.moveProgram("g1", -1, done.cb)
        assertEquals(listOf("g1", "g0", "g2"), vm.state.value.content.programs.map { it.id })
        vm.moveProgram("g1", -1, done.cb)
        vm.deleteProgram("g2", done.cb)
        vm.deleteProgram("zzz", done.cb)
        assertEquals(listOf("g1", "g0"), vm.state.value.content.programs.map { it.id })
        assertEquals(listOf(true, false, true, false), done.calls)
    }

    // ── gallery ──

    @Test fun addingAGalleryImageTrimsItAndIgnoresBlankOnes() = runTest {
        val source = FakeGymContentSource(GymContent(gallery = listOf("a")))
        val vm = vm(source)
        val events = toasts()
        val done = Done()
        vm.addGalleryImage("  http://x/b.jpg ", done.cb)
        assertEquals(listOf("a", "http://x/b.jpg"), vm.state.value.content.gallery)
        vm.addGalleryImage("   ", done.cb)
        vm.addGalleryImage("", done.cb)
        assertEquals(listOf(true, false, false), done.calls)
        assertEquals(1, source.saves.size)
        assertEquals(1, events.size) // blank adds are ignored without a toast
    }

    @Test fun theGalleryIsLimitedToTwentyFour() = runTest {
        val source = FakeGymContentSource(GymContent(gallery = List(24) { "u$it" }))
        val vm = vm(source)
        val done = Done()
        vm.addGalleryImage("http://x/25.jpg", done.cb)
        assertEquals(listOf(false), done.calls)
        assertTrue(source.saves.isEmpty())
        assertEquals(24, vm.state.value.content.gallery.size)

        val twentyThree = vm(FakeGymContentSource(GymContent(gallery = List(23) { "u$it" })))
        twentyThree.addGalleryImage("http://x/24.jpg")
        assertEquals(24, twentyThree.state.value.content.gallery.size)
    }

    @Test fun removingAGalleryImageByIndex() = runTest {
        val source = FakeGymContentSource(GymContent(gallery = listOf("a", "b", "c")))
        val vm = vm(source)
        val done = Done()
        vm.removeGalleryImage(1, done.cb)
        assertEquals(listOf("a", "c"), vm.state.value.content.gallery)
        vm.removeGalleryImage(5, done.cb)
        vm.removeGalleryImage(-1, done.cb)
        assertEquals(listOf(true, false, false), done.calls)
        assertEquals(1, source.saves.size)
    }

    // ── only the edited section is written ──

    @Test fun eachChangeSavesOnlyItsOwnSection() = runTest {
        val source = FakeGymContentSource(GymContent(equipment = equip(1), packages = packs(1), programs = progs(1), gallery = listOf("a")))
        val vm = vm(source)
        vm.saveAbout("x")
        vm.deleteEquipmentItem("e0")
        vm.deletePackage("p0")
        vm.deleteProgram("g0")
        vm.removeGalleryImage(0)
        assertEquals(
            listOf(GymSection.ABOUT, GymSection.EQUIPMENT, GymSection.PACKAGES, GymSection.PROGRAMS, GymSection.GALLERY),
            source.saves.map { it.first }
        )
        assertEquals(emptyList<Any>(), source.saves[1].second)
        assertEquals(emptyList<Any>(), source.saves[4].second)
    }
}
