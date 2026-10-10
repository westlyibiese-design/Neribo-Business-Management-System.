package com.westly.nbms.features.cms

import com.westly.nbms.core.util.Validators

/** The six tabs of the Website CMS page, in display order. */
enum class CmsTab(val label: String) {
    HERO("Hero"),
    ABOUT("About"),
    BANNERS("Page Banners"),
    CONTACT("Contact"),
    TESTIMONIALS("Testimonials"),
    FAQS("FAQs")
}

/** Inline errors of the Contact tab; null means the field is fine. */
data class ContactErrors(val email: String? = null, val mapEmbedUrl: String? = null) {
    val any: Boolean get() = email != null || mapEmbedUrl != null
}

/** What the live banner preview shows. [subtitle], [description] and [buttonText] are null when they are not shown. */
data class BannerPreviewText(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val buttonText: String?
)

internal fun HeroContent.trimmed(): HeroContent = copy(
    headline = headline.trim(), subheadline = subheadline.trim(), ctaText = ctaText.trim(),
    ctaLink = ctaLink.trim(), backgroundImage = backgroundImage.trim()
)

internal fun AboutContent.trimmed(): AboutContent = copy(
    title = title.trim(), description = description.trim(), mission = mission.trim(),
    founded = founded.trim(), image = image.trim()
)

internal fun ContactContent.trimmed(): ContactContent = copy(
    address = address.trim(), phone = phone.trim(), email = email.trim(), checkInTime = checkInTime.trim(),
    checkOutTime = checkOutTime.trim(), mapEmbedUrl = mapEmbedUrl.trim()
)

internal fun PageHeroContent.trimmed(): PageHeroContent = copy(
    title = title.trim(), subtitle = subtitle.trim(), description = description.trim(),
    buttonText = buttonText.trim(), buttonLink = buttonLink.trim(), image = image.trim()
)

/** The plain (UI-free) rules of the Website CMS page, so they can be tested alone. */
object CmsFormRules {
    val TABS: List<CmsTab> = CmsTab.entries
    val DEFAULT_TAB: CmsTab = CmsTab.HERO

    const val FOUNDED_MAX_LENGTH = 4

    const val MSG_BAD_URL = "Enter a full web address starting with https://"
    const val MSG_BAD_EMAIL = "Enter a valid email address."

    private val WEB_ADDRESS = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    // ── Contact ──

    /** Blank is fine. Otherwise the text must look like an email address. */
    fun emailError(email: String): String? {
        val t = email.trim()
        return if (t.isEmpty() || Validators.email(t)) null else MSG_BAD_EMAIL
    }

    /** Blank is fine. Otherwise only a full `http://` or `https://` address is accepted. */
    fun mapUrlError(url: String): String? {
        val t = url.trim()
        return if (t.isEmpty() || WEB_ADDRESS.matches(t)) null else MSG_BAD_URL
    }

    fun isHttpUrl(url: String): Boolean = WEB_ADDRESS.matches(url.trim())

    fun contactErrors(contact: ContactContent): ContactErrors =
        ContactErrors(email = emailError(contact.email), mapEmbedUrl = mapUrlError(contact.mapEmbedUrl))

    // ── About ──

    /** Year Founded accepts digits only, at most four. */
    fun filterFounded(input: String): String = input.filter { it in '0'..'9' }.take(FOUNDED_MAX_LENGTH)

    // ── "changed?" flags (whitespace at the ends does not count as a change) ──

    fun heroChanged(draft: HeroContent, saved: HeroContent): Boolean = draft.trimmed() != saved.trimmed()
    fun aboutChanged(draft: AboutContent, saved: AboutContent): Boolean = draft.trimmed() != saved.trimmed()
    fun contactChanged(draft: ContactContent, saved: ContactContent): Boolean = draft.trimmed() != saved.trimmed()

    // ── Page banner preview ──

    /** The title falls back to the section label; subtitle and description only when typed; the button only when the section has one. */
    fun bannerPreview(content: PageHeroContent, section: PageHeroSection): BannerPreviewText {
        val c = content.trimmed()
        return BannerPreviewText(
            title = c.title.ifEmpty { section.label },
            subtitle = c.subtitle.ifEmpty { null },
            description = c.description.ifEmpty { null },
            buttonText = if (section.supportsButton && c.buttonText.isNotEmpty()) c.buttonText else null
        )
    }

    fun bannerHelper(section: PageHeroSection): String = "Changes go live on ${section.usedOn} as soon as you save."
    fun bannerPublishedMessage(section: PageHeroSection): String = "${section.label} is now live on the website."

    // ── Testimonials ──

    fun testimonialsHeading(count: Int): String = "Testimonials ($count)"
    fun canAddTestimonial(count: Int): Boolean = count < CmsLimits.TESTIMONIALS
    fun isValidTestimonial(author: String, text: String): Boolean = author.isNotBlank() && text.isNotBlank()
    fun deleteTestimonialBody(author: String): String = deleteBody(author)

    // ── FAQs ──

    fun faqsHeading(count: Int): String = "FAQ Items ($count)"
    fun canAddFaq(count: Int): Boolean = count < CmsLimits.FAQS
    fun isValidFaq(question: String, answer: String): Boolean = question.isNotBlank() && answer.isNotBlank()
    fun deleteFaqBody(question: String): String = deleteBody(question)

    private fun deleteBody(name: String): String =
        "Are you sure you want to delete \"$name\"? This will remove it from the public website immediately."
}
