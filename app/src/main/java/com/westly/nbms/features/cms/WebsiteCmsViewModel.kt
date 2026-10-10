package com.westly.nbms.features.cms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal const val DOC_HERO = "hero"
internal const val DOC_ABOUT = "about"
internal const val DOC_CONTACT = "contact"
internal const val DOC_TESTIMONIALS = "testimonials"
internal const val DOC_FAQS = "faqs"

internal const val IMAGE_FOLDER_HERO = "cms-hero"
internal const val IMAGE_FOLDER_ABOUT = "cms-about"

internal const val AUDIT_TESTIMONIALS = "cms_updated:testimonials"
internal const val AUDIT_FAQS = "cms_updated:faqs"

internal const val TOAST_CONTENT_SAVED = "Content Saved"
internal const val MSG_CMS_LOAD_FAILED_SAVE =
    "Some content failed to load. Reload the page before saving to avoid overwriting existing content."
internal const val MSG_CMS_LOAD_FAILED_BANNER =
    "Some website content failed to load. Reload the page before making changes — saving now could overwrite existing content with blanks."
internal const val MSG_CMS_STILL_LOADING = "This content is still loading. Try again in a moment."
internal const val MSG_CONTACT_FIX_ERRORS = "Fix the highlighted fields first."
internal const val MSG_TESTIMONIAL_REQUIRED = "Guest name and review text are required."
internal const val MSG_FAQ_REQUIRED = "Question and answer are required."
internal const val MSG_TESTIMONIALS_LIMIT = "You've reached the maximum of 50 testimonials."
internal const val MSG_FAQS_LIMIT = "You've reached the maximum of 50 FAQs."
internal const val MSG_SOMETHING_WRONG = "Something went wrong. Please try again."

internal val TESTIMONIAL_CODEC: ListCodec<TestimonialItem> = ListCodec(
    parse = { TestimonialItem.parseList(it) },
    toMap = { it.toMap() },
    idOf = { it.id }
)

internal val FAQ_CODEC: ListCodec<FaqItem> = ListCodec(
    parse = { FaqItem.parseList(it) },
    toMap = { it.toMap() },
    idOf = { it.id }
)

/** Where one section of the page is in its loading. */
enum class SectionLoad { LOADING, READY, FAILED }

/** The open Add / Edit testimonial form. [id] is null for a new testimonial. */
data class TestimonialDraft(
    val id: String? = null,
    val author: String = "",
    val role: String = "",
    val text: String = "",
    val rating: Int = 5
)

/** The open Add / Edit FAQ form. [id] is null for a new FAQ. */
data class FaqDraft(
    val id: String? = null,
    val question: String = "",
    val answer: String = ""
)

/**
 * Everything the Website CMS page shows. The text typed in each object tab lives here (the `…Draft` fields), next to the last
 * saved copy (the `…Saved` fields), so switching tabs never loses typing and a save resets only its own tab's "changed" flag.
 */
data class WebsiteCmsUiState(
    val tab: CmsTab = CmsFormRules.DEFAULT_TAB,
    val heroLoad: SectionLoad = SectionLoad.LOADING,
    val aboutLoad: SectionLoad = SectionLoad.LOADING,
    val contactLoad: SectionLoad = SectionLoad.LOADING,
    val testimonialsLoad: SectionLoad = SectionLoad.LOADING,
    val faqsLoad: SectionLoad = SectionLoad.LOADING,
    val heroDraft: HeroContent = HeroContent(),
    val heroSaved: HeroContent = HeroContent(),
    val aboutDraft: AboutContent = AboutContent(),
    val aboutSaved: AboutContent = AboutContent(),
    val contactDraft: ContactContent = ContactContent(),
    val contactSaved: ContactContent = ContactContent(),
    val testimonials: List<TestimonialItem> = emptyList(),
    val faqs: List<FaqItem> = emptyList(),
    val testimonialDraft: TestimonialDraft? = null,
    val faqDraft: FaqDraft? = null,
    val saving: Boolean = false
) {
    /** Any section that failed to load (a missing document is NOT a failure). */
    val loadFailed: Boolean
        get() = listOf(heroLoad, aboutLoad, contactLoad, testimonialsLoad, faqsLoad).any { it == SectionLoad.FAILED }

    val heroChanged: Boolean get() = CmsFormRules.heroChanged(heroDraft, heroSaved)
    val aboutChanged: Boolean get() = CmsFormRules.aboutChanged(aboutDraft, aboutSaved)
    val contactChanged: Boolean get() = CmsFormRules.contactChanged(contactDraft, contactSaved)

    /** The tabs that hold typing that has not been saved yet (Page Banners, Testimonials and FAQs save per item). */
    val changedTabs: Set<CmsTab>
        get() = buildSet {
            if (heroChanged) add(CmsTab.HERO)
            if (aboutChanged) add(CmsTab.ABOUT)
            if (contactChanged) add(CmsTab.CONTACT)
        }

    fun loadOf(tab: CmsTab): SectionLoad = when (tab) {
        CmsTab.HERO -> heroLoad
        CmsTab.ABOUT -> aboutLoad
        CmsTab.CONTACT -> contactLoad
        CmsTab.TESTIMONIALS -> testimonialsLoad
        CmsTab.FAQS -> faqsLoad
        CmsTab.BANNERS -> SectionLoad.READY // every banner loads its own document
    }

    /** The inline errors of the Contact tab: a non-blank email that is not an email, a non-blank map link that is not http(s). */
    val contactErrors: ContactErrors get() = CmsFormRules.contactErrors(contactDraft)
}

/**
 * The state and every change of the Website CMS page (Hero, About, Contact, Testimonials, FAQs; the Page Banners tab has its own
 * [PageBannersViewModel]). UI-free. Object tabs are saved whole with [CmsSource.saveObject]; the two lists change one operation at
 * a time with [CmsSource.mutateList]. A refused call (a section failed to load, still loading, already saving, bad input, limit
 * reached) writes nothing and calls `onDone(false)`.
 */
@HiltViewModel
class WebsiteCmsViewModel internal constructor(
    private val source: CmsSource,
    private val toast: ToastController,
    val imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    @Inject
    constructor(
        repository: CmsRepository,
        toast: ToastController,
        imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
    ) : this(repository as CmsSource, toast, imageProviders)

    private val _state = MutableStateFlow(WebsiteCmsUiState())
    val state: StateFlow<WebsiteCmsUiState> = _state.asStateFlow()

    private val guard = CmsSaveGuard()

    init {
        watch(DOC_HERO) { r -> _state.update { s -> s.withHero(r) } }
        watch(DOC_ABOUT) { r -> _state.update { s -> s.withAbout(r) } }
        watch(DOC_CONTACT) { r -> _state.update { s -> s.withContact(r) } }
        watch(DOC_TESTIMONIALS) { r -> _state.update { s -> s.withTestimonials(r) } }
        watch(DOC_FAQS) { r -> _state.update { s -> s.withFaqs(r) } }
    }

    private fun watch(docId: String, onEach: (Resource<CmsRawDoc>) -> Unit) {
        viewModelScope.launch {
            val live: Flow<Resource<CmsRawDoc>> = source.observeDoc(docId)
            live
                .catch { onEach(Resource.Error(it.message ?: "Failed to load")) }
                .collect { onEach(it) }
        }
    }

    // ── reading the live documents ──
    // A document the person has not typed into follows the live copy; one they have changed keeps their typing.

    private fun WebsiteCmsUiState.withHero(r: Resource<CmsRawDoc>): WebsiteCmsUiState = when (r) {
        is Resource.Loading -> this
        is Resource.Error -> copy(heroLoad = SectionLoad.FAILED)
        is Resource.Success -> {
            val live = HeroContent.parse(r.data.data)
            copy(heroLoad = SectionLoad.READY, heroDraft = if (heroChanged) heroDraft else live, heroSaved = live)
        }
    }

    private fun WebsiteCmsUiState.withAbout(r: Resource<CmsRawDoc>): WebsiteCmsUiState = when (r) {
        is Resource.Loading -> this
        is Resource.Error -> copy(aboutLoad = SectionLoad.FAILED)
        is Resource.Success -> {
            val live = AboutContent.parse(r.data.data)
            copy(aboutLoad = SectionLoad.READY, aboutDraft = if (aboutChanged) aboutDraft else live, aboutSaved = live)
        }
    }

    private fun WebsiteCmsUiState.withContact(r: Resource<CmsRawDoc>): WebsiteCmsUiState = when (r) {
        is Resource.Loading -> this
        is Resource.Error -> copy(contactLoad = SectionLoad.FAILED)
        is Resource.Success -> {
            val live = ContactContent.parse(r.data.data)
            copy(contactLoad = SectionLoad.READY, contactDraft = if (contactChanged) contactDraft else live, contactSaved = live)
        }
    }

    private fun WebsiteCmsUiState.withTestimonials(r: Resource<CmsRawDoc>): WebsiteCmsUiState = when (r) {
        is Resource.Loading -> this
        is Resource.Error -> copy(testimonialsLoad = SectionLoad.FAILED)
        is Resource.Success -> copy(testimonialsLoad = SectionLoad.READY, testimonials = TestimonialItem.parseList(r.data.data))
    }

    private fun WebsiteCmsUiState.withFaqs(r: Resource<CmsRawDoc>): WebsiteCmsUiState = when (r) {
        is Resource.Loading -> this
        is Resource.Error -> copy(faqsLoad = SectionLoad.FAILED)
        is Resource.Success -> copy(faqsLoad = SectionLoad.READY, faqs = FaqItem.parseList(r.data.data))
    }

    // ── tabs and typing ──

    fun selectTab(tab: CmsTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun updateHero(transform: (HeroContent) -> HeroContent) {
        _state.update { it.copy(heroDraft = transform(it.heroDraft)) }
    }

    fun updateAbout(transform: (AboutContent) -> AboutContent) {
        _state.update {
            val next = transform(it.aboutDraft)
            it.copy(aboutDraft = next.copy(founded = CmsFormRules.filterFounded(next.founded)))
        }
    }

    fun updateContact(transform: (ContactContent) -> ContactContent) {
        _state.update { it.copy(contactDraft = transform(it.contactDraft)) }
    }

    // ── the checks every change goes through ──

    private fun refuse(title: String, message: String, onDone: (Boolean) -> Unit) {
        toast.show(message = message, type = ToastType.Error, title = title)
        onDone(false)
    }

    /** Load-error guard, still loading, already saving — in that order. True means the caller has taken the saving flag. */
    private fun begin(tab: CmsTab, onDone: (Boolean) -> Unit): Boolean {
        val s = _state.value
        if (s.loadFailed) {
            refuse(TITLE_CANT_SAVE_YET, MSG_CMS_LOAD_FAILED_SAVE, onDone)
            return false
        }
        if (s.loadOf(tab) == SectionLoad.LOADING) {
            refuse(TITLE_NOT_SAVED, MSG_CMS_STILL_LOADING, onDone)
            return false
        }
        if (!guard.tryStart()) {
            onDone(false)
            return false
        }
        _state.update { it.copy(saving = true) }
        return true
    }

    private fun release() {
        _state.update { it.copy(saving = false) }
        guard.finish()
    }

    // ── object tabs ──

    private fun sendObject(docId: String, data: Map<String, Any?>, onSaved: () -> Unit, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            try {
                source.saveObject(docId, data)
                onSaved()
                toast.show(message = TOAST_CONTENT_SAVED, type = ToastType.Success)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_SOMETHING_WRONG, type = ToastType.Error, title = TITLE_ERROR)
            } finally {
                release()
            }
            onDone(ok)
        }
    }

    fun saveHero(onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.HERO, onDone)) return
        val clean = _state.value.heroDraft.trimmed()
        sendObject(DOC_HERO, clean.toMap(), onSaved = { _state.update { it.copy(heroSaved = clean) } }, onDone = onDone)
    }

    fun saveAbout(onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.ABOUT, onDone)) return
        val clean = _state.value.aboutDraft.trimmed().let { it.copy(founded = CmsFormRules.filterFounded(it.founded)) }
        sendObject(DOC_ABOUT, clean.toMap(), onSaved = { _state.update { it.copy(aboutSaved = clean) } }, onDone = onDone)
    }

    fun saveContact(onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.CONTACT, onDone)) return
        val clean = _state.value.contactDraft.trimmed()
        if (CmsFormRules.contactErrors(clean).any) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_CONTACT_FIX_ERRORS, onDone)
            return
        }
        sendObject(
            DOC_CONTACT,
            clean.toMap(),
            onSaved = { _state.update { it.copy(contactSaved = clean) } },
            onDone = onDone
        )
    }

    // ── list tabs ──

    private fun <T> sendList(
        docId: String,
        op: ListOp<T>,
        codec: ListCodec<T>,
        limit: Int,
        audit: String,
        limitMessage: String,
        successToast: String?,
        normalize: (List<T>) -> List<T>,
        onSaved: () -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            var ok = false
            try {
                when (source.mutateList(docId, op, codec, limit, audit, normalize)) {
                    MutateResult.Done -> {
                        onSaved()
                        if (successToast != null) toast.show(message = successToast, type = ToastType.Success)
                        ok = true
                    }
                    MutateResult.AlreadyChanged -> toast.show(message = MSG_ALREADY_CHANGED, type = ToastType.Error)
                    MutateResult.LimitReached -> toast.show(message = limitMessage, type = ToastType.Error, title = TITLE_NOT_SAVED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_SOMETHING_WRONG, type = ToastType.Error, title = TITLE_ERROR)
            } finally {
                release()
            }
            onDone(ok)
        }
    }

    // testimonials

    fun openTestimonialForm(draft: TestimonialDraft = TestimonialDraft()) {
        _state.update { it.copy(testimonialDraft = draft) }
    }

    fun updateTestimonialDraft(transform: (TestimonialDraft) -> TestimonialDraft) {
        _state.update { s -> s.testimonialDraft?.let { s.copy(testimonialDraft = transform(it)) } ?: s }
    }

    fun closeTestimonialForm() {
        _state.update { it.copy(testimonialDraft = null) }
    }

    /** Saves the open testimonial form: an Add when it has no id, otherwise a Replace of that testimonial. */
    fun saveTestimonial(onDone: (Boolean) -> Unit = {}) {
        val draft = _state.value.testimonialDraft
        if (draft == null) {
            onDone(false)
            return
        }
        if (!begin(CmsTab.TESTIMONIALS, onDone)) return
        val author = draft.author.trim()
        val text = draft.text.trim()
        if (!CmsFormRules.isValidTestimonial(author, text)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_TESTIMONIAL_REQUIRED, onDone)
            return
        }
        val isNew = draft.id.isNullOrBlank()
        if (isNew && !CmsFormRules.canAddTestimonial(_state.value.testimonials.size)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_TESTIMONIALS_LIMIT, onDone)
            return
        }
        val item = TestimonialItem(
            id = if (isNew) CmsIds.newId() else draft.id!!.trim(),
            author = author,
            role = draft.role.trim(),
            text = text,
            rating = draft.rating.coerceIn(1, 5)
        )
        val op: ListOp<TestimonialItem> = if (isNew) ListOp.Add(item) else ListOp.Replace(item)
        sendList(
            docId = DOC_TESTIMONIALS,
            op = op,
            codec = TESTIMONIAL_CODEC,
            limit = CmsLimits.TESTIMONIALS,
            audit = AUDIT_TESTIMONIALS,
            limitMessage = MSG_TESTIMONIALS_LIMIT,
            successToast = TOAST_CONTENT_SAVED,
            normalize = { it },
            onSaved = { closeTestimonialForm() },
            onDone = onDone
        )
    }

    fun deleteTestimonial(id: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.TESTIMONIALS, onDone)) return
        sendList(
            docId = DOC_TESTIMONIALS,
            op = ListOp.Remove(id),
            codec = TESTIMONIAL_CODEC,
            limit = CmsLimits.TESTIMONIALS,
            audit = AUDIT_TESTIMONIALS,
            limitMessage = MSG_TESTIMONIALS_LIMIT,
            successToast = TOAST_CONTENT_SAVED,
            normalize = { it },
            onSaved = {},
            onDone = onDone
        )
    }

    // FAQs

    fun openFaqForm(draft: FaqDraft = FaqDraft()) {
        _state.update { it.copy(faqDraft = draft) }
    }

    fun updateFaqDraft(transform: (FaqDraft) -> FaqDraft) {
        _state.update { s -> s.faqDraft?.let { s.copy(faqDraft = transform(it)) } ?: s }
    }

    fun closeFaqForm() {
        _state.update { it.copy(faqDraft = null) }
    }

    /** Saves the open FAQ form: an Add when it has no id, otherwise a Replace. Every save renumbers `order` = position + 1. */
    fun saveFaq(onDone: (Boolean) -> Unit = {}) {
        val draft = _state.value.faqDraft
        if (draft == null) {
            onDone(false)
            return
        }
        if (!begin(CmsTab.FAQS, onDone)) return
        val question = draft.question.trim()
        val answer = draft.answer.trim()
        if (!CmsFormRules.isValidFaq(question, answer)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_FAQ_REQUIRED, onDone)
            return
        }
        val isNew = draft.id.isNullOrBlank()
        if (isNew && !CmsFormRules.canAddFaq(_state.value.faqs.size)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_FAQS_LIMIT, onDone)
            return
        }
        val item = FaqItem(
            id = if (isNew) CmsIds.newId() else draft.id!!.trim(),
            question = question,
            answer = answer,
            order = _state.value.faqs.size + 1
        )
        val op: ListOp<FaqItem> = if (isNew) ListOp.Add(item) else ListOp.Replace(item)
        sendFaq(op, TOAST_CONTENT_SAVED, { closeFaqForm() }, onDone)
    }

    fun deleteFaq(id: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.FAQS, onDone)) return
        sendFaq(ListOp.Remove(id), TOAST_CONTENT_SAVED, {}, onDone)
    }

    /** Moves a FAQ one place up ([delta] = -1) or down (+1). Silent: no success toast. */
    fun moveFaq(id: String, delta: Int, onDone: (Boolean) -> Unit = {}) {
        if (!begin(CmsTab.FAQS, onDone)) return
        sendFaq(ListOp.Move(id, delta), null, {}, onDone)
    }

    private fun sendFaq(op: ListOp<FaqItem>, successToast: String?, onSaved: () -> Unit, onDone: (Boolean) -> Unit) {
        sendList(
            docId = DOC_FAQS,
            op = op,
            codec = FAQ_CODEC,
            limit = CmsLimits.FAQS,
            audit = AUDIT_FAQS,
            limitMessage = MSG_FAQS_LIMIT,
            successToast = successToast,
            normalize = { CmsListRules.renumberFaqs(it) },
            onSaved = onSaved,
            onDone = onDone
        )
    }
}
