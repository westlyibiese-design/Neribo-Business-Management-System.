package com.westly.nbms.features.cms

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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A fake repository for the gallery: keeps the document, applies every list operation with the real rules and re-emits it. */
internal class FakeGallerySource(initial: List<GalleryItem> = emptyList(), exists: Boolean = true) : CmsSource {
    val flow = MutableStateFlow<Resource<CmsRawDoc>>(Resource.Success(CmsRawDoc(exists, initial.map { it.toMap() })))
    val ops = mutableListOf<ListOp<*>>()
    val docIds = mutableListOf<String>()
    val auditActions = mutableListOf<String>()
    val limitsSeen = mutableListOf<Int>()
    var failWith: Exception? = null
    var forced: MutateResult? = null
    var gate: CompletableDeferred<Unit>? = null

    val items: List<GalleryItem> get() = GalleryItem.parseList((flow.value as Resource.Success).data.data)

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
        docIds += docId
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
class GalleryViewModelTest {
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

    private fun vm(source: CmsSource) = GalleryViewModel(source, toast, emptySet())

    private fun img(n: Int) = GalleryItem("id$n", "Image $n", "Caption $n", "https://x/$n.jpg")
    private fun imgs(n: Int) = List(n) { img(it) }

    // ── loading ──

    @Test fun showsTheStoredImagesInStoredOrder() = runTest {
        val s = vm(FakeGallerySource(imgs(3))).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertEquals(listOf("id0", "id1", "id2"), s.images.map { it.id })
    }

    @Test fun aMissingDocumentIsAnEmptyGalleryNotAnError() = runTest {
        val source = FakeGallerySource()
        source.flow.value = Resource.Success(CmsRawDoc(exists = false, data = null))
        val s = vm(source).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertTrue(s.images.isEmpty())
    }

    @Test fun isLoadingUntilTheFirstAnswer() = runTest {
        val source = FakeGallerySource()
        source.flow.value = Resource.Loading
        assertTrue(vm(source).state.value.loading)
    }

    @Test fun aGalleryItemSavedOnAnotherPhoneShowsUpWithoutAReload() = runTest {
        val source = FakeGallerySource(imgs(1))
        val vm = vm(source)
        source.flow.value = Resource.Success(CmsRawDoc(true, imgs(2).map { it.toMap() }))
        assertEquals(2, vm.state.value.images.size)
    }

    @Test fun badStoredEntriesAreDroppedWhenReading() = runTest {
        val source = FakeGallerySource()
        source.flow.value = Resource.Success(
            CmsRawDoc(
                true,
                listOf(
                    img(1).toMap(),
                    "not an object",
                    mapOf("title" to "No id", "imageUrl" to "u"),
                    mapOf("id" to "x", "imageUrl" to "u"),
                    mapOf("id" to "y", "title" to "No image")
                )
            )
        )
        val images = vm(source).state.value.images
        assertEquals(listOf("id1", "y"), images.map { it.id })
        assertEquals("", images.last().imageUrl)
    }

    // ── load-error guard ──

    @Test fun aLoadErrorBlocksEverySaveWithTheGalleryDescription() = runTest {
        val source = FakeGallerySource(imgs(2))
        source.flow.value = Resource.Error("offline")
        val vm = vm(source)
        val events = toasts()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)

        val results = mutableListOf<Boolean>()
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { results += it }
        vm.saveImage("id0", "Renamed", "https://x/p.jpg", "") { results += it }
        vm.deleteImage("id0") { results += it }

        assertEquals(listOf(false, false, false), results)
        assertTrue(source.ops.isEmpty())
        assertEquals(3, events.size)
        events.forEach {
            assertEquals("Can't save yet", it.title)
            assertEquals("The gallery failed to load, so saving now could overwrite it with incomplete data. Reload the page first.", it.message)
            assertEquals(ToastType.Error, it.type)
        }
    }

    @Test fun theLoadErrorClearsWhenTheGalleryLoadsAgainAndSavingWorks() = runTest {
        val source = FakeGallerySource()
        source.flow.value = Resource.Error("offline")
        val vm = vm(source)
        source.flow.value = Resource.Success(CmsRawDoc(true, emptyList<Any>()))
        assertFalse(vm.state.value.loadFailed)
        var ok = false
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertTrue(ok)
    }

    @Test fun savingWhileStillLoadingIsRefusedWithAToast() = runTest {
        val source = FakeGallerySource()
        source.flow.value = Resource.Loading
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
    }

    // ── add ──

    @Test fun aTitleAndAnImageAreBothRequired() = runTest {
        val source = FakeGallerySource()
        val vm = vm(source)
        val events = toasts()
        val results = mutableListOf<Boolean>()
        vm.saveImage(null, "   ", "https://x/p.jpg", "caption") { results += it }
        vm.saveImage(null, "Pool", "  ", "caption") { results += it }
        vm.saveImage(null, "", "", "") { results += it }
        assertEquals(listOf(false, false, false), results)
        assertTrue(source.ops.isEmpty())
        assertEquals(3, events.size)
        events.forEach { assertEquals("Title and image are required.", it.message) }
        assertFalse(vm.state.value.saving)
        var ok = false
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertTrue(ok)
    }

    @Test fun theCaptionIsOptional() = runTest {
        val source = FakeGallerySource()
        var ok = false
        vm(source).saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertTrue(ok)
        assertEquals("", source.items.single().caption)
    }

    @Test fun addingSavesATrimmedImageAtTheEndWithAFreshIdAndShowsImageAdded() = runTest {
        val source = FakeGallerySource(imgs(1))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.saveImage(null, "  Poolside at sunset  ", " https://x/y.jpg ", "  Golden hour  ") { ok = it }

        assertTrue(ok)
        val added = source.items.last()
        assertEquals(2, source.items.size)
        assertEquals("Poolside at sunset", added.title)
        assertEquals("https://x/y.jpg", added.imageUrl)
        assertEquals("Golden hour", added.caption)
        assertTrue(Regex("[a-z0-9]{8}").matches(added.id))
        assertEquals(listOf("gallery"), source.docIds)
        assertEquals(listOf("gallery_updated"), source.auditActions)
        assertEquals(listOf(CmsLimits.GALLERY), source.limitsSeen)
        assertTrue(source.ops.single() is ListOp.Add<*>)
        assertEquals("Image Added", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
        assertEquals(2, vm.state.value.images.size)
        assertFalse(vm.state.value.saving)
    }

    // ── limit ──

    @Test fun theHundredAndFirstImageIsRefusedWithoutAnyWrite() = runTest {
        val source = FakeGallerySource(imgs(100))
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage(null, "One too many", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals("You've reached the maximum of 100 images. Delete one to add another.", events.single().message)
        assertFalse(vm.state.value.saving)
    }

    @Test fun theHundredthImageIsAccepted() = runTest {
        val source = FakeGallerySource(imgs(99))
        var ok = false
        vm(source).saveImage(null, "Number hundred", "https://x/p.jpg", "") { ok = it }
        assertTrue(ok)
        assertEquals(100, source.items.size)
    }

    @Test fun aLimitAnswerFromTheRepositoryShowsTheLimitToast() = runTest {
        val source = FakeGallerySource(imgs(3))
        source.forced = MutateResult.LimitReached
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertEquals("You've reached the maximum of 100 images. Delete one to add another.", events.single().message)
    }

    @Test fun editingAFullGalleryIsStillAllowed() = runTest {
        val source = FakeGallerySource(imgs(100))
        var ok = false
        vm(source).saveImage("id5", "Renamed", "https://x/p.jpg", "Changed") { ok = it }
        assertTrue(ok)
        assertEquals("Renamed", source.items[5].title)
    }

    // ── edit ──

    @Test fun editingReplacesTheImageInPlaceAndShowsImageUpdated() = runTest {
        val source = FakeGallerySource(imgs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.saveImage("id1", " New title ", " https://x/new.jpg ", " New caption ") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("id0", "id1", "id2"), source.items.map { it.id })
        assertEquals(GalleryItem("id1", "New title", "New caption", "https://x/new.jpg"), source.items[1])
        assertEquals("Image Updated", events.single().message)
        assertTrue(source.ops.single() is ListOp.Replace<*>)
    }

    @Test fun editingNeedsATitleAndAnImageToo() = runTest {
        val source = FakeGallerySource(imgs(2))
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage("id0", "", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertEquals("Title and image are required.", events.single().message)
        assertTrue(source.ops.isEmpty())
    }

    @Test fun editingAnImageThatSomeoneElseDeletedShowsTheAlreadyChangedToast() = runTest {
        val source = FakeGallerySource(imgs(2))
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage("ghost", "Title", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertEquals("That item was already changed by someone else.", events.single().message)
        assertEquals(2, source.items.size)
        assertFalse(vm.state.value.saving)
    }

    // ── delete ──

    @Test fun deletingRemovesOnlyThatImageAndShowsImageDeleted() = runTest {
        val source = FakeGallerySource(imgs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.deleteImage("id1") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("id0", "id2"), source.items.map { it.id })
        assertEquals("Image Deleted", events.single().message)
        assertEquals(listOf("gallery_updated"), source.auditActions)
        assertTrue(source.ops.single() is ListOp.Remove<*>)
    }

    @Test fun deletingAnImageThatIsAlreadyGoneShowsTheAlreadyChangedToast() = runTest {
        val source = FakeGallerySource(imgs(1))
        val vm = vm(source)
        val events = toasts()
        vm.deleteImage("ghost")
        assertEquals("That item was already changed by someone else.", events.single().message)
    }

    // ── busy state and failures ──

    @Test fun whileASaveIsRunningTheStateIsSavingAndASecondTapIsIgnored() = runTest {
        val source = FakeGallerySource(imgs(1))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val vm = vm(source)
        val events = toasts()

        val results = mutableListOf<Boolean>()
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { results += it }
        assertTrue(vm.state.value.saving)

        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { results += it }   // double tap
        vm.deleteImage("id0") { results += it }
        assertEquals(listOf(false, false), results)
        assertTrue(events.isEmpty())

        gate.complete(Unit)
        assertEquals(listOf(false, false, true), results)
        assertFalse(vm.state.value.saving)
        assertEquals(1, source.ops.size)
        assertEquals(2, source.items.size)
    }

    @Test fun aFailedSaveShowsTheErrorToastAndFreesTheScreenAgain() = runTest {
        val source = FakeGallerySource(imgs(1))
        source.failWith = IllegalStateException("You don't have access to this data.")
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertEquals("You don't have access to this data.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertFalse(vm.state.value.saving)
        assertEquals(1, vm.state.value.images.size)

        source.failWith = null
        vm.saveImage(null, "Pool", "https://x/p.jpg", "") { ok = it }
        assertTrue(ok)
    }

    // ── rules ──

    @Test fun pageTextsMatchTheSpecification() {
        assertEquals("Photos (0/100)", GalleryRules.heading(0))
        assertEquals("Photos (100/100)", GalleryRules.heading(100))
        assertEquals(
            "Are you sure you want to delete \"Pool\"? This will remove it from the public website immediately.",
            GalleryRules.deleteBody("Pool")
        )
        assertTrue(GalleryRules.canAdd(99))
        assertFalse(GalleryRules.canAdd(100))
        assertTrue(GalleryRules.isValid("a", "b"))
        assertFalse(GalleryRules.isValid("", "b"))
        assertFalse(GalleryRules.isValid("a", " "))
        assertEquals("Gallery Management", GALLERY_TITLE)
        assertEquals("No gallery images yet. Add your first one above.", GALLERY_EMPTY)
        assertEquals("Gallery failed to load. Reload before adding or editing.", GALLERY_LOAD_FAILED)
        assertEquals("Loading gallery…", GALLERY_LOADING)
        assertEquals("gallery", GALLERY_IMAGE_FOLDER)
    }
}
