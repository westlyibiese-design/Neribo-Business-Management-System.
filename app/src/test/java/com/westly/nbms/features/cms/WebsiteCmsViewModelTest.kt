package com.westly.nbms.features.cms

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.NavRules
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class WebsiteCmsViewModelTest {
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

    private fun vm(source: FakeWebsiteCmsSource) = WebsiteCmsViewModel(source, toast, emptySet())

    private fun tm(n: Int) = TestimonialItem("t$n", "Guest $n", "Stay $n", "Lovely $n", 4)
    private fun tms(n: Int) = List(n) { tm(it) }
    private fun faq(n: Int) = FaqItem("f$n", "Question $n", "Answer $n", n + 1)
    private fun faqs(n: Int) = List(n) { faq(it) }

    private fun FakeWebsiteCmsSource.seedLists(testimonials: List<TestimonialItem> = emptyList(), faqs: List<FaqItem> = emptyList()) {
        put(DOC_TESTIMONIALS, testimonials.map { it.toMap() })
        put(DOC_FAQS, faqs.map { it.toMap() })
    }

    private val cantSaveMessage = "Some content failed to load. Reload the page before saving to avoid overwriting existing content."

    // ── loading ──

    @Test fun showsTheStoredContentOfEveryObjectTab() = runTest {
        val source = FakeWebsiteCmsSource()
        source.put(DOC_HERO, HeroContent(headline = "Welcome", ctaText = "Book").toMap())
        source.put(DOC_ABOUT, AboutContent(title = "Our story", founded = "1999").toMap())
        source.put(DOC_CONTACT, ContactContent(phone = "0800").toMap())
        val s = vm(source).state.value
        assertEquals(CmsTab.HERO, s.tab)
        assertEquals("Welcome", s.heroDraft.headline)
        assertEquals("Book", s.heroDraft.ctaText)
        assertEquals("Our story", s.aboutDraft.title)
        assertEquals("1999", s.aboutDraft.founded)
        assertEquals("0800", s.contactDraft.phone)
        assertFalse(s.loadFailed)
        assertTrue(s.changedTabs.isEmpty())
    }

    @Test fun aMissingDocumentIsEmptyContentNotAnError() = runTest {
        val s = vm(FakeWebsiteCmsSource()).state.value
        assertEquals(SectionLoad.READY, s.heroLoad)
        assertEquals(SectionLoad.READY, s.faqsLoad)
        assertFalse(s.loadFailed)
        assertEquals(HeroContent(), s.heroDraft)
        assertTrue(s.testimonials.isEmpty())
        assertTrue(s.faqs.isEmpty())
    }

    @Test fun aSectionIsLoadingUntilItsFirstAnswer() = runTest {
        val source = FakeWebsiteCmsSource()
        source.stayLoading(DOC_ABOUT)
        val s = vm(source).state.value
        assertEquals(SectionLoad.LOADING, s.aboutLoad)
        assertEquals(SectionLoad.LOADING, s.loadOf(CmsTab.ABOUT))
        assertEquals(SectionLoad.READY, s.heroLoad)
        assertEquals(SectionLoad.READY, s.loadOf(CmsTab.BANNERS))
    }

    @Test fun listsAreReadTolerantlyAndInOrder() = runTest {
        val source = FakeWebsiteCmsSource()
        source.put(DOC_TESTIMONIALS, listOf(tm(1).toMap(), "junk", mapOf("id" to "x"), tm(2).toMap()))
        source.put(DOC_FAQS, faqs(3).map { it.toMap() })
        val s = vm(source).state.value
        assertEquals(listOf("t1", "t2"), s.testimonials.map { it.id })
        assertEquals(listOf("f0", "f1", "f2"), s.faqs.map { it.id })
    }

    // ── tabs and "changed" flags ──

    @Test fun typingMarksOnlyThatTabChanged() = runTest {
        val vm = vm(FakeWebsiteCmsSource())
        vm.updateHero { it.copy(headline = "Hi") }
        assertEquals(setOf(CmsTab.HERO), vm.state.value.changedTabs)
        vm.updateContact { it.copy(phone = "1") }
        assertEquals(setOf(CmsTab.HERO, CmsTab.CONTACT), vm.state.value.changedTabs)
        assertTrue(vm.state.value.heroChanged)
        assertFalse(vm.state.value.aboutChanged)
        assertTrue(vm.state.value.contactChanged)
    }

    @Test fun typingOnlySpacesIsNotAChange() = runTest {
        val vm = vm(FakeWebsiteCmsSource())
        vm.updateHero { it.copy(headline = "   ") }
        assertTrue(vm.state.value.changedTabs.isEmpty())
    }

    @Test fun unsavedTextIsKeptWhileSwitchingTabs() = runTest {
        val vm = vm(FakeWebsiteCmsSource())
        vm.updateHero { it.copy(headline = "Typed on Hero") }
        vm.selectTab(CmsTab.ABOUT)
        vm.updateAbout { it.copy(title = "Typed on About") }
        vm.selectTab(CmsTab.CONTACT)
        vm.selectTab(CmsTab.TESTIMONIALS)
        vm.selectTab(CmsTab.HERO)
        val s = vm.state.value
        assertEquals(CmsTab.HERO, s.tab)
        assertEquals("Typed on Hero", s.heroDraft.headline)
        assertEquals("Typed on About", s.aboutDraft.title)
        assertEquals(setOf(CmsTab.HERO, CmsTab.ABOUT), s.changedTabs)
    }

    @Test fun aSuccessfulSaveResetsOnlyThatTabsFlagAndKeepsTheOthers() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        vm.updateHero { it.copy(headline = "Hero text") }
        vm.updateAbout { it.copy(title = "About text") }
        var ok = false
        vm.saveHero { ok = it }
        assertTrue(ok)
        val s = vm.state.value
        assertEquals(setOf(CmsTab.ABOUT), s.changedTabs)
        assertEquals("About text", s.aboutDraft.title)
        assertEquals("Hero text", s.heroDraft.headline)
        assertEquals("Hero text", s.heroSaved.headline)
    }

    @Test fun aFailedSaveLeavesTheChangedFlagOn() = runTest {
        val source = FakeWebsiteCmsSource()
        source.failWith = IllegalStateException("You don't have access to this data.")
        val vm = vm(source)
        val events = toasts()
        vm.updateHero { it.copy(headline = "Hero text") }
        var ok = true
        vm.saveHero { ok = it }
        assertFalse(ok)
        assertTrue(vm.state.value.heroChanged)
        assertFalse(vm.state.value.saving)
        assertEquals("Error", events.single().title)
        assertEquals("You don't have access to this data.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
    }

    @Test fun aLiveChangeFromAnotherPhoneFollowsUntilYouStartTyping() = runTest {
        val source = FakeWebsiteCmsSource()
        source.put(DOC_HERO, HeroContent(headline = "One").toMap())
        val vm = vm(source)
        source.put(DOC_HERO, HeroContent(headline = "Two").toMap())
        assertEquals("Two", vm.state.value.heroDraft.headline)

        vm.updateHero { it.copy(headline = "Mine") }
        source.put(DOC_HERO, HeroContent(headline = "Three").toMap())
        assertEquals("Mine", vm.state.value.heroDraft.headline)
        assertEquals("Three", vm.state.value.heroSaved.headline)
        assertTrue(vm.state.value.heroChanged)
    }

    // ── saving the object tabs ──

    @Test fun savingHeroWritesTheFiveFieldsTrimmedAndShowsContentSaved() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        val events = toasts()
        vm.updateHero { HeroContent(" Welcome ", " Sub ", " Book now ", " /booking ", " https://x/y.jpg ") }
        vm.saveHero()
        val (docId, data) = source.savedObjects.single()
        assertEquals("hero", docId)
        assertEquals(
            mapOf(
                "headline" to "Welcome", "subheadline" to "Sub", "ctaText" to "Book now",
                "ctaLink" to "/booking", "backgroundImage" to "https://x/y.jpg"
            ),
            data
        )
        assertEquals(listOf("cms_updated:hero"), source.auditActions)
        assertEquals("Content Saved", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
        assertFalse(vm.state.value.saving)
    }

    @Test fun savingAboutKeepsTheYearAsATextOfFourDigits() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        vm.updateAbout { it.copy(title = "Our story", founded = "20a2-4x5") }
        assertEquals("2024", vm.state.value.aboutDraft.founded)
        vm.saveAbout()
        val (docId, data) = source.savedObjects.single()
        assertEquals("about", docId)
        assertEquals("2024", data["founded"])
        assertEquals(setOf("title", "description", "mission", "founded", "image"), data.keys)
    }

    @Test fun savingContactWritesTheSixFields() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        vm.updateContact {
            ContactContent("1 Main St", "0800", "info@hotel.com", "2:00 PM", "11:00 AM", "https://maps.google.com/embed?x=1")
        }
        var ok = false
        vm.saveContact { ok = it }
        assertTrue(ok)
        val (docId, data) = source.savedObjects.single()
        assertEquals("contact", docId)
        assertEquals(setOf("address", "phone", "email", "checkInTime", "checkOutTime", "mapEmbedUrl"), data.keys)
        assertEquals("info@hotel.com", data["email"])
        assertFalse(vm.state.value.contactChanged)
    }

    @Test fun blankEmailAndBlankMapAreAllowed() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        vm.updateContact { it.copy(phone = "0800") }
        var ok = false
        vm.saveContact { ok = it }
        assertTrue(ok)
        assertEquals(1, source.savedObjects.size)
    }

    @Test fun aBadEmailOrMapAddressIsRefusedWithoutAnyWrite() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        val events = toasts()
        vm.updateContact { it.copy(email = "not-an-email", mapEmbedUrl = "ftp://maps") }
        assertEquals("Enter a valid email address.", vm.state.value.contactErrors.email)
        assertEquals("Enter a full web address starting with https://", vm.state.value.contactErrors.mapEmbedUrl)
        var ok = true
        vm.saveContact { ok = it }
        assertFalse(ok)
        assertTrue(source.savedObjects.isEmpty())
        assertEquals("Not saved", events.single().title)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.contactChanged)

        // fixed text saves afterwards (the in-flight guard was released)
        vm.updateContact { it.copy(email = "a@b.com", mapEmbedUrl = "https://maps.example.com") }
        assertFalse(vm.state.value.contactErrors.any)
        vm.saveContact { ok = it }
        assertTrue(ok)
    }

    // ── load-error guard ──

    @Test fun aLoadErrorBlocksEverySaveWithTheCantSaveYetToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(2), faqs(2))
        source.failToLoad(DOC_CONTACT)
        val vm = vm(source)
        val events = toasts()
        assertTrue(vm.state.value.loadFailed)

        vm.updateHero { it.copy(headline = "x") }
        vm.openTestimonialForm(TestimonialDraft(author = "Jane", text = "Nice"))
        vm.openFaqForm(FaqDraft(question = "Q", answer = "A"))
        val results = mutableListOf<Boolean>()
        vm.saveHero { results += it }
        vm.saveAbout { results += it }
        vm.saveContact { results += it }
        vm.saveTestimonial { results += it }
        vm.deleteTestimonial("t0") { results += it }
        vm.saveFaq { results += it }
        vm.deleteFaq("f0") { results += it }
        vm.moveFaq("f1", -1) { results += it }

        assertEquals(List(8) { false }, results)
        assertTrue(source.savedObjects.isEmpty())
        assertTrue(source.ops.isEmpty())
        assertEquals(8, events.size)
        events.forEach {
            assertEquals("Can't save yet", it.title)
            assertEquals(cantSaveMessage, it.message)
            assertEquals(ToastType.Error, it.type)
        }
    }

    @Test fun theErrorBannerTextAndThePageGuardTextMatchTheSpecification() {
        assertEquals(
            "Some website content failed to load. Reload the page before making changes — saving now could overwrite existing content with blanks.",
            MSG_CMS_LOAD_FAILED_BANNER
        )
        assertEquals(cantSaveMessage, MSG_CMS_LOAD_FAILED_SAVE)
    }

    @Test fun theLoadErrorClearsWhenTheDocumentLoadsAgain() = runTest {
        val source = FakeWebsiteCmsSource()
        source.failToLoad(DOC_HERO)
        val vm = vm(source)
        assertTrue(vm.state.value.loadFailed)
        source.put(DOC_HERO, HeroContent(headline = "Back").toMap())
        assertFalse(vm.state.value.loadFailed)
        assertEquals("Back", vm.state.value.heroDraft.headline)
        vm.updateHero { it.copy(headline = "Edited") }
        var ok = false
        vm.saveHero { ok = it }
        assertTrue(ok)
    }

    @Test fun savingWhileTheTabIsStillLoadingIsRefusedWithAToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.stayLoading(DOC_ABOUT)
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.saveAbout { ok = it }
        assertFalse(ok)
        assertTrue(source.savedObjects.isEmpty())
        assertEquals(ToastType.Error, events.single().type)
    }

    // ── in-flight guard ──

    @Test fun whileASaveIsRunningEveryOtherSaveIsIgnored() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(1), faqs(2))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val vm = vm(source)
        val events = toasts()
        vm.updateHero { it.copy(headline = "x") }

        val results = mutableListOf<Boolean>()
        vm.saveHero { results += it }
        assertTrue(vm.state.value.saving)

        vm.saveHero { results += it }                 // double tap
        vm.saveAbout { results += it }
        vm.deleteTestimonial("t0") { results += it }
        vm.moveFaq("f1", -1) { results += it }
        assertEquals(listOf(false, false, false, false), results)
        assertTrue(events.isEmpty())

        gate.complete(Unit)
        assertEquals(listOf(false, false, false, false, true), results)
        assertFalse(vm.state.value.saving)
        assertEquals(1, source.savedObjects.size)
        assertTrue(source.ops.isEmpty())
    }

    // ── testimonials ──

    @Test fun addingATestimonialSavesItTrimmedAtTheEndWithAFreshIdAndClosesTheForm() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(1))
        val vm = vm(source)
        val events = toasts()
        vm.openTestimonialForm()
        assertEquals(5, vm.state.value.testimonialDraft?.rating)
        vm.updateTestimonialDraft { it.copy(author = "  Jane Doe ", role = " Honeymoon ", text = "  Wonderful stay  ", rating = 4) }
        var ok = false
        vm.saveTestimonial { ok = it }

        assertTrue(ok)
        val added = source.testimonials().last()
        assertEquals(2, source.testimonials().size)
        assertEquals("Jane Doe", added.author)
        assertEquals("Honeymoon", added.role)
        assertEquals("Wonderful stay", added.text)
        assertEquals(4, added.rating)
        assertTrue(Regex("[a-z0-9]{8}").matches(added.id))
        assertEquals(listOf("cms_updated:testimonials"), source.auditActions)
        assertEquals(listOf(CmsLimits.TESTIMONIALS), source.limitsSeen)
        assertEquals("Content Saved", events.single().message)
        assertNull(vm.state.value.testimonialDraft)
        assertEquals(2, vm.state.value.testimonials.size)
    }

    @Test fun aTestimonialNeedsANameAndReviewText() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        val events = toasts()
        vm.openTestimonialForm(TestimonialDraft(author = " ", text = "Nice"))
        val results = mutableListOf<Boolean>()
        vm.saveTestimonial { results += it }
        vm.updateTestimonialDraft { it.copy(author = "Jane", text = " ") }
        vm.saveTestimonial { results += it }
        assertEquals(listOf(false, false), results)
        assertTrue(source.ops.isEmpty())
        assertEquals(2, events.size)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.testimonialDraft != null)   // the form stays open with the typing
    }

    @Test fun editingATestimonialReplacesItInPlace() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(3))
        val vm = vm(source)
        vm.openTestimonialForm(TestimonialDraft("t1", "Guest 1", "Stay 1", "Lovely 1", 4))
        vm.updateTestimonialDraft { it.copy(text = "Changed", rating = 2) }
        var ok = false
        vm.saveTestimonial { ok = it }
        assertTrue(ok)
        assertEquals(listOf("t0", "t1", "t2"), source.testimonials().map { it.id })
        assertEquals(TestimonialItem("t1", "Guest 1", "Stay 1", "Changed", 2), source.testimonials()[1])
        assertTrue(source.ops.single() is ListOp.Replace<*>)
        assertNull(vm.state.value.testimonialDraft)
    }

    @Test fun editingATestimonialThatSomeoneElseDeletedShowsTheAlreadyChangedToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(2))
        val vm = vm(source)
        val events = toasts()
        vm.openTestimonialForm(TestimonialDraft("ghost", "Nobody", "", "Gone", 3))
        var ok = true
        vm.saveTestimonial { ok = it }
        assertFalse(ok)
        assertEquals("That item was already changed by someone else.", events.single().message)
        assertEquals(2, source.testimonials().size)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.testimonialDraft != null)
    }

    @Test fun deletingATestimonialRemovesOnlyThatOne() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.deleteTestimonial("t1") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("t0", "t2"), source.testimonials().map { it.id })
        assertEquals("Content Saved", events.single().message)
        assertEquals(listOf("cms_updated:testimonials"), source.auditActions)
    }

    @Test fun deletingATestimonialThatIsAlreadyGoneShowsTheAlreadyChangedToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(1))
        val vm = vm(source)
        val events = toasts()
        vm.deleteTestimonial("ghost")
        assertEquals("That item was already changed by someone else.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
    }

    @Test fun theFiftyFirstTestimonialIsRefusedWithoutAnyWrite() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(50))
        val vm = vm(source)
        val events = toasts()
        vm.openTestimonialForm(TestimonialDraft(author = "One too many", text = "Nope"))
        var ok = true
        vm.saveTestimonial { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals("You've reached the maximum of 50 testimonials.", events.single().message)
        assertFalse(CmsFormRules.canAddTestimonial(vm.state.value.testimonials.size))
    }

    @Test fun theFiftiethTestimonialIsAcceptedAndEditingAFullListIsStillAllowed() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(49))
        val vm = vm(source)
        vm.openTestimonialForm(TestimonialDraft(author = "Number fifty", text = "Fine"))
        var ok = false
        vm.saveTestimonial { ok = it }
        assertTrue(ok)
        assertEquals(50, source.testimonials().size)

        vm.openTestimonialForm(TestimonialDraft("t3", "Renamed", "", "Changed", 5))
        ok = false
        vm.saveTestimonial { ok = it }
        assertTrue(ok)
        assertEquals("Renamed", source.testimonials()[3].author)
    }

    @Test fun aLimitAnswerFromTheRepositoryShowsTheLimitToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(tms(3))
        source.forced = MutateResult.LimitReached
        val vm = vm(source)
        val events = toasts()
        vm.openTestimonialForm(TestimonialDraft(author = "Jane", text = "Nice"))
        var ok = true
        vm.saveTestimonial { ok = it }
        assertFalse(ok)
        assertEquals("You've reached the maximum of 50 testimonials.", events.single().message)
    }

    @Test fun cancellingTheFormDropsItsText() = runTest {
        val vm = vm(FakeWebsiteCmsSource())
        vm.openTestimonialForm()
        vm.updateTestimonialDraft { it.copy(author = "Jane") }
        vm.closeTestimonialForm()
        assertNull(vm.state.value.testimonialDraft)
    }

    // ── FAQs ──

    @Test fun addingAFaqRenumbersEveryItemAndAuditsIt() = runTest {
        val source = FakeWebsiteCmsSource()
        // the stored numbers are wrong on purpose: every save renumbers
        source.seedLists(faqs = listOf(FaqItem("a", "Q1", "A1", 7), FaqItem("b", "Q2", "A2", 7)))
        val vm = vm(source)
        val events = toasts()
        vm.openFaqForm()
        vm.updateFaqDraft { it.copy(question = " What time? ", answer = " 2 PM ") }
        var ok = false
        vm.saveFaq { ok = it }
        assertTrue(ok)
        assertEquals(listOf(1, 2, 3), source.faqs().map { it.order })
        val added = source.faqs().last()
        assertEquals("What time?", added.question)
        assertEquals("2 PM", added.answer)
        assertTrue(Regex("[a-z0-9]{8}").matches(added.id))
        assertEquals(listOf("cms_updated:faqs"), source.auditActions)
        assertEquals(listOf(CmsLimits.FAQS), source.limitsSeen)
        assertEquals("Content Saved", events.single().message)
        assertNull(vm.state.value.faqDraft)
    }

    @Test fun aFaqNeedsAQuestionAndAnAnswer() = runTest {
        val source = FakeWebsiteCmsSource()
        val vm = vm(source)
        val events = toasts()
        vm.openFaqForm(FaqDraft(question = "Q", answer = "  "))
        var ok = true
        vm.saveFaq { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals("Not saved", events.single().title)
        assertFalse(vm.state.value.saving)
    }

    @Test fun movingAFaqUpOrDownRenumbersAndIsSilent() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(3))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.moveFaq("f2", -1) { ok = it }
        assertTrue(ok)
        assertEquals(listOf("f0", "f2", "f1"), source.faqs().map { it.id })
        assertEquals(listOf(1, 2, 3), source.faqs().map { it.order })
        vm.moveFaq("f0", 1)
        assertEquals(listOf("f2", "f0", "f1"), source.faqs().map { it.id })
        assertEquals(listOf(1, 2, 3), source.faqs().map { it.order })
        assertTrue(events.isEmpty())
        assertEquals(listOf("cms_updated:faqs", "cms_updated:faqs"), source.auditActions)
        assertTrue(source.ops.all { it is ListOp.Move<*> })
        assertEquals(listOf("f2", "f0", "f1"), vm.state.value.faqs.map { it.id })
    }

    @Test fun movingAnEndFaqOffTheListChangesNothingAndStaysSilent() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(2))
        val vm = vm(source)
        val events = toasts()
        vm.moveFaq("f0", -1)
        vm.moveFaq("f1", 1)
        assertEquals(listOf("f0", "f1"), source.faqs().map { it.id })
        assertTrue(events.isEmpty())
        assertTrue(source.auditActions.isEmpty())
    }

    @Test fun movingAFaqThatIsGoneShowsTheAlreadyChangedToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(2))
        val vm = vm(source)
        val events = toasts()
        vm.moveFaq("ghost", 1)
        assertEquals("That item was already changed by someone else.", events.single().message)
    }

    @Test fun deletingAFaqRenumbersTheRest() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(3))
        val vm = vm(source)
        var ok = false
        vm.deleteFaq("f0") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("f1", "f2"), source.faqs().map { it.id })
        assertEquals(listOf(1, 2), source.faqs().map { it.order })
    }

    @Test fun editingAFaqKeepsItsPlaceAndRenumbers() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(3))
        val vm = vm(source)
        vm.openFaqForm(FaqDraft("f1", "Question 1", "Answer 1"))
        vm.updateFaqDraft { it.copy(answer = "New answer") }
        var ok = false
        vm.saveFaq { ok = it }
        assertTrue(ok)
        assertEquals(listOf("f0", "f1", "f2"), source.faqs().map { it.id })
        assertEquals("New answer", source.faqs()[1].answer)
        assertEquals(listOf(1, 2, 3), source.faqs().map { it.order })
    }

    @Test fun editingAFaqThatSomeoneElseDeletedShowsTheAlreadyChangedToast() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(1))
        val vm = vm(source)
        val events = toasts()
        vm.openFaqForm(FaqDraft("ghost", "Q", "A"))
        var ok = true
        vm.saveFaq { ok = it }
        assertFalse(ok)
        assertEquals("That item was already changed by someone else.", events.single().message)
    }

    @Test fun theFiftyFirstFaqIsRefusedWithoutAnyWrite() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(50))
        val vm = vm(source)
        val events = toasts()
        vm.openFaqForm(FaqDraft(question = "One too many", answer = "Nope"))
        var ok = true
        vm.saveFaq { ok = it }
        assertFalse(ok)
        assertTrue(source.ops.isEmpty())
        assertEquals("You've reached the maximum of 50 FAQs.", events.single().message)
    }

    @Test fun aFailedListSaveShowsTheErrorToastAndFreesTheScreenAgain() = runTest {
        val source = FakeWebsiteCmsSource()
        source.seedLists(faqs = faqs(1))
        source.failWith = IllegalStateException("You don't have access to this data.")
        val vm = vm(source)
        val events = toasts()
        vm.openFaqForm(FaqDraft(question = "Q", answer = "A"))
        var ok = true
        vm.saveFaq { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.faqDraft != null)

        source.failWith = null
        vm.saveFaq { ok = it }
        assertTrue(ok)
    }

    // ── registration (31B replaces CmsFeature.kt) ──

    @Test fun theFeatureRegistersAllFourRoutes() {
        val feature = CmsFeature()
        assertEquals("cms", feature.id)
        assertEquals(setOf("cms", "facilities", "gallery", "reviews"), feature.screens.map { it.route }.toSet())
        assertEquals(setOf("cms", "facilities", "gallery", "reviews"), feature.nav.map { it.route }.toSet())
    }

    @Test fun websiteCmsIsSuperAdminOnlyAndTheOtherThreeAreUnchanged() {
        val nav = CmsFeature().nav.associateBy { it.route }
        val cms = nav.getValue("cms")
        assertEquals("Website CMS", cms.label)
        assertEquals(250, cms.order)
        assertNull(cms.group)
        assertNull(cms.module)
        assertEquals(setOf(Role.SUPER_ADMIN), cms.roles)

        val both = setOf(Role.SUPER_ADMIN, Role.MANAGER)
        listOf("facilities" to 260, "gallery" to 280, "reviews" to 290).forEach { (route, order) ->
            assertEquals(order, nav.getValue(route).order)
            assertEquals(both, nav.getValue(route).roles)
            assertNull(nav.getValue(route).group)
            assertNull(nav.getValue(route).module)
        }
        assertEquals("Facilities", nav.getValue("facilities").label)
        assertEquals("Gallery", nav.getValue("gallery").label)
        assertEquals("Guest Reviews", nav.getValue("reviews").label)
    }

    @Test fun theRouteGuardAgreesWithTheNavEntries() {
        assertTrue(NavRules.canOpen("cms", Role.SUPER_ADMIN, emptySet()))
        assertFalse(NavRules.canOpen("cms", Role.MANAGER, emptySet()))
        assertFalse(NavRules.canOpen("cms", Role.RECEPTIONIST, emptySet()))
        assertTrue(NavRules.canOpen("facilities", Role.MANAGER, emptySet()))
        assertTrue(NavRules.canOpen("gallery", Role.MANAGER, emptySet()))
        assertTrue(NavRules.canOpen("reviews", Role.MANAGER, emptySet()))
    }
}
