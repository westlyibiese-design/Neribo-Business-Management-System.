package com.westly.nbms.features.cms

import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageHeroEditorModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val toast = ToastController()
    private val gym = PAGE_HERO_SECTIONS.first { it.docId == "gym_hero" }
    private val faq = PAGE_HERO_SECTIONS.first { it.docId == "faq_hero" }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun model(source: FakeWebsiteCmsSource, section: PageHeroSection = gym) =
        PageHeroEditorModel(source, toast, section, CoroutineScope(UnconfinedTestDispatcher(scheduler)))

    private val sixKeys = setOf("title", "subtitle", "description", "buttonText", "buttonLink", "image")

    @Test fun readsItsOwnDocumentByTheSectionId() = runTest {
        val source = FakeWebsiteCmsSource()
        source.put("gym_hero", PageHeroContent(title = "Get Fit", buttonText = "Join").toMap())
        source.put("faq_hero", PageHeroContent(title = "Not me").toMap())
        val s = model(source).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertEquals("Get Fit", s.draft.title)
        assertEquals("Join", s.draft.buttonText)
        assertFalse(s.changed)
    }

    @Test fun aMissingDocumentIsAnEmptyBannerNotAnError() = runTest {
        val s = model(FakeWebsiteCmsSource()).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertEquals(PageHeroContent(), s.draft)
    }

    @Test fun savePublishesTheSixTrimmedFieldsToItsOwnDocumentAndShowsPublished() = runTest {
        val source = FakeWebsiteCmsSource()
        val events = toasts()
        val model = model(source)
        model.update { PageHeroContent(" Get Fit ", " Strong ", " Body text ", " Join ", " /gym/join ", " https://x/y.jpg ") }
        assertTrue(model.state.value.changed)
        var ok = false
        model.save { ok = it }

        assertTrue(ok)
        val (docId, data) = source.savedObjects.single()
        assertEquals("gym_hero", docId)
        assertEquals(
            mapOf(
                "title" to "Get Fit", "subtitle" to "Strong", "description" to "Body text",
                "buttonText" to "Join", "buttonLink" to "/gym/join", "image" to "https://x/y.jpg"
            ),
            data
        )
        assertEquals(listOf("cms_updated:gym_hero"), source.auditActions)
        assertEquals("Published", events.single().title)
        assertEquals("Gym Page Hero Banner is now live on the website.", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
        assertFalse(model.state.value.changed)
        assertFalse(model.state.value.saving)
    }

    @Test fun aBannerWithoutAButtonStillWritesAllSixFields() = runTest {
        val source = FakeWebsiteCmsSource()
        val model = model(source, faq)
        model.update { it.copy(title = "Questions") }
        model.save()
        val (docId, data) = source.savedObjects.single()
        assertEquals("faq_hero", docId)
        assertEquals(sixKeys, data.keys)
        assertEquals("", data["buttonText"])
        assertEquals("", data["image"])
    }

    @Test fun aLoadErrorBlocksTheSaveWithTheCantSaveYetToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.failToLoad("gym_hero")
        val events = toasts()
        val model = model(source)
        assertTrue(model.state.value.loadFailed)
        assertFalse(model.state.value.loading)
        model.update { it.copy(title = "x") }
        var ok = true
        model.save { ok = it }
        assertFalse(ok)
        assertTrue(source.savedObjects.isEmpty())
        assertEquals("Can't save yet", events.single().title)
        assertEquals(ToastType.Error, events.single().type)
        assertEquals("This section's content failed to load.", MSG_BANNER_LOAD_FAILED)
    }

    @Test fun theLoadErrorClearsWhenTheDocumentLoadsAgain() = runTest {
        val source = FakeWebsiteCmsSource()
        source.failToLoad("gym_hero")
        val model = model(source)
        source.put("gym_hero", PageHeroContent(title = "Back").toMap())
        assertFalse(model.state.value.loadFailed)
        assertEquals("Back", model.state.value.draft.title)
        var ok = false
        model.save { ok = it }
        assertTrue(ok)
    }

    @Test fun savingWhileStillLoadingIsRefused() = runTest {
        val source = FakeWebsiteCmsSource()
        source.stayLoading("gym_hero")
        val events = toasts()
        val model = model(source)
        assertTrue(model.state.value.loading)
        var ok = true
        model.save { ok = it }
        assertFalse(ok)
        assertTrue(source.savedObjects.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
    }

    @Test fun aDoubleTapSavesOnce() = runTest {
        val source = FakeWebsiteCmsSource()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val model = model(source)
        model.update { it.copy(title = "x") }
        val results = mutableListOf<Boolean>()
        model.save { results += it }
        assertTrue(model.state.value.saving)
        model.save { results += it }
        assertEquals(listOf(false), results)
        gate.complete(Unit)
        assertEquals(listOf(false, true), results)
        assertEquals(1, source.savedObjects.size)
        assertFalse(model.state.value.saving)
    }

    @Test fun aFailedSaveShowsTheErrorToastAndKeepsTheTyping() = runTest {
        val source = FakeWebsiteCmsSource()
        source.failWith = IllegalStateException("You don't have access to this data.")
        val events = toasts()
        val model = model(source)
        model.update { it.copy(title = "Keep me") }
        var ok = true
        model.save { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertEquals("You don't have access to this data.", events.single().message)
        assertEquals("Keep me", model.state.value.draft.title)
        assertTrue(model.state.value.changed)
        assertFalse(model.state.value.saving)
    }

    @Test fun typingIsKeptWhenAnotherPhoneSavesAndAnUntouchedFormFollows() = runTest {
        val source = FakeWebsiteCmsSource()
        source.put("gym_hero", PageHeroContent(title = "One").toMap())
        val model = model(source)
        source.put("gym_hero", PageHeroContent(title = "Two").toMap())
        assertEquals("Two", model.state.value.draft.title)
        model.update { it.copy(title = "Mine") }
        source.put("gym_hero", PageHeroContent(title = "Three").toMap())
        assertEquals("Mine", model.state.value.draft.title)
        assertEquals("Three", model.state.value.saved.title)
    }
}
