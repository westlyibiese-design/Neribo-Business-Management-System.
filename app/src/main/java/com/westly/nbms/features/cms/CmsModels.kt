package com.westly.nbms.features.cms

import java.security.SecureRandom

/**
 * Phase 31A1: every content model of the website CMS, written once for all the CMS pages (Facilities now; Gallery,
 * Guest Reviews and the Website CMS page later). Documents live at `businesses/{bid}/cms_content/{docId}`:
 * object documents are `{ data: { … }, updatedAt }`, list documents are `{ data: [ … ], updatedAt }`.
 * The field names written by `toMap()` are a contract with the public website — do not rename them.
 *
 * Reading is tolerant: a missing, null or wrongly typed field becomes an empty string; a list entry that is not an
 * object, or has no usable id / primary text, is dropped. Nothing here ever throws on bad stored data.
 */

internal const val CMS_COLLECTION = "cms_content"

// ───────────────────────── tolerant reading helpers ─────────────────────────

/** A map whose keys are all text, or null for anything else (a list, a string, null…). */
internal fun cmsStringKeyed(raw: Any?): Map<String, Any?>? {
    val map = raw as? Map<*, *> ?: return null
    return map.entries.filter { it.key is String }.associate { (k, v) -> (k as String) to v }
}

/** The text stored under [key], or "" when it is missing, null or not text. */
internal fun Map<String, Any?>.cmsText(key: String): String = (this[key] as? String) ?: ""

/** A whole number stored under [key] (any stored number type), or null when it is missing or not a finite number. */
internal fun Map<String, Any?>.cmsInt(key: String): Int? {
    val n = this[key] as? Number ?: return null
    val d = n.toDouble()
    if (d.isNaN() || d.isInfinite()) return null
    return n.toInt()
}

/** The list entries of [raw] that are objects (as string-keyed maps); anything that is not a list gives no entries. */
private fun cmsEntries(raw: Any?): List<Map<String, Any?>> =
    (raw as? List<*>)?.mapNotNull { cmsStringKeyed(it) } ?: emptyList()

// ───────────────────────── object documents ─────────────────────────

/** `cms_content/hero` → `data`. */
data class HeroContent(
    val headline: String = "",
    val subheadline: String = "",
    val ctaText: String = "",
    val ctaLink: String = "",
    val backgroundImage: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "headline" to headline,
        "subheadline" to subheadline,
        "ctaText" to ctaText,
        "ctaLink" to ctaLink,
        "backgroundImage" to backgroundImage
    )

    companion object {
        fun parse(raw: Any?): HeroContent {
            val m = cmsStringKeyed(raw) ?: return HeroContent()
            return HeroContent(
                headline = m.cmsText("headline"),
                subheadline = m.cmsText("subheadline"),
                ctaText = m.cmsText("ctaText"),
                ctaLink = m.cmsText("ctaLink"),
                backgroundImage = m.cmsText("backgroundImage")
            )
        }
    }
}

/** `cms_content/about` → `data`. */
data class AboutContent(
    val title: String = "",
    val description: String = "",
    val mission: String = "",
    val founded: String = "",
    val image: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "description" to description,
        "mission" to mission,
        "founded" to founded,
        "image" to image
    )

    companion object {
        fun parse(raw: Any?): AboutContent {
            val m = cmsStringKeyed(raw) ?: return AboutContent()
            return AboutContent(
                title = m.cmsText("title"),
                description = m.cmsText("description"),
                mission = m.cmsText("mission"),
                founded = m.cmsText("founded"),
                image = m.cmsText("image")
            )
        }
    }
}

/** `cms_content/contact` → `data`. */
data class ContactContent(
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val checkInTime: String = "",
    val checkOutTime: String = "",
    val mapEmbedUrl: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "address" to address,
        "phone" to phone,
        "email" to email,
        "checkInTime" to checkInTime,
        "checkOutTime" to checkOutTime,
        "mapEmbedUrl" to mapEmbedUrl
    )

    companion object {
        fun parse(raw: Any?): ContactContent {
            val m = cmsStringKeyed(raw) ?: return ContactContent()
            return ContactContent(
                address = m.cmsText("address"),
                phone = m.cmsText("phone"),
                email = m.cmsText("email"),
                checkInTime = m.cmsText("checkInTime"),
                checkOutTime = m.cmsText("checkOutTime"),
                mapEmbedUrl = m.cmsText("mapEmbedUrl")
            )
        }
    }
}

/** The page banner documents (`contact_hero`, `faq_hero`, … see [PAGE_HERO_SECTIONS]) → `data`. */
data class PageHeroContent(
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val buttonText: String = "",
    val buttonLink: String = "",
    val image: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "subtitle" to subtitle,
        "description" to description,
        "buttonText" to buttonText,
        "buttonLink" to buttonLink,
        "image" to image
    )

    companion object {
        fun parse(raw: Any?): PageHeroContent {
            val m = cmsStringKeyed(raw) ?: return PageHeroContent()
            return PageHeroContent(
                title = m.cmsText("title"),
                subtitle = m.cmsText("subtitle"),
                description = m.cmsText("description"),
                buttonText = m.cmsText("buttonText"),
                buttonLink = m.cmsText("buttonLink"),
                image = m.cmsText("image")
            )
        }
    }
}

/** One editable page banner: which document, what it is called, where it shows, where its picture is uploaded. */
data class PageHeroSection(
    val docId: String,
    val label: String,
    val usedOn: String,
    val imageFolder: String,
    val supportsButton: Boolean
)

/** The eight page banners of the Website CMS "Page Banners" tab, in display order. */
val PAGE_HERO_SECTIONS: List<PageHeroSection> = listOf(
    PageHeroSection("contact_hero", "Contact Page Hero Banner", "/contact", "cms-contact-hero", true),
    PageHeroSection("faq_hero", "FAQ Page Background", "/faq", "cms-faq-hero", false),
    PageHeroSection("facilities_hero", "Facilities Page Hero Banner", "/facilities", "cms-facilities-hero", true),
    PageHeroSection("rooms_hero", "Room List Page Hero Banner", "/rooms", "cms-rooms-hero", true),
    PageHeroSection("restaurant_hero", "Restaurant Menu Page Hero Banner", "/restaurant", "cms-restaurant-hero", true),
    PageHeroSection("venue_hero", "Venue Page Hero Banner", "/venues", "cms-venue-hero", true),
    PageHeroSection("gym_hero", "Gym Page Hero Banner", "/gym", "cms-gym-hero", true),
    PageHeroSection("testimonials_hero", "Testimonials Section Background", "/testimonials", "cms-testimonials-hero", false)
)

// ───────────────────────── list documents ─────────────────────────

/** An entry needs a non-blank text id and a non-blank primary text, otherwise it is dropped. */
private fun Map<String, Any?>.usableId(): String? = (this["id"] as? String)?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.requiredText(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

/** One entry of `cms_content/testimonials` → `data`. Rating is 1–5. */
data class TestimonialItem(
    val id: String,
    val author: String,
    val role: String = "",
    val text: String,
    val rating: Int = 5
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "author" to author,
        "role" to role,
        "text" to text,
        "rating" to rating
    )

    companion object {
        fun parseList(raw: Any?): List<TestimonialItem> = cmsEntries(raw).mapNotNull { m ->
            val id = m.usableId() ?: return@mapNotNull null
            val author = m.requiredText("author") ?: return@mapNotNull null
            TestimonialItem(
                id = id,
                author = author,
                role = m.cmsText("role"),
                text = m.cmsText("text"),
                rating = (m.cmsInt("rating") ?: 5).coerceIn(1, 5)
            )
        }
    }
}

/** One entry of `cms_content/faqs` → `data`. `order` is renumbered to position + 1 on every save. */
data class FaqItem(
    val id: String,
    val question: String,
    val answer: String,
    val order: Int = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "question" to question,
        "answer" to answer,
        "order" to order
    )

    companion object {
        fun parseList(raw: Any?): List<FaqItem> = cmsEntries(raw).mapNotNull { m ->
            val id = m.usableId() ?: return@mapNotNull null
            val question = m.requiredText("question") ?: return@mapNotNull null
            FaqItem(id = id, question = question, answer = m.cmsText("answer"), order = m.cmsInt("order") ?: 0)
        }
    }
}

/** One entry of `cms_content/facilities` → `data`. */
data class FacilityItem(
    val id: String,
    val name: String,
    val image: String = "",
    val description: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "image" to image,
        "description" to description
    )

    companion object {
        fun parseList(raw: Any?): List<FacilityItem> = cmsEntries(raw).mapNotNull { m ->
            val id = m.usableId() ?: return@mapNotNull null
            val name = m.requiredText("name") ?: return@mapNotNull null
            FacilityItem(id = id, name = name, image = m.cmsText("image"), description = m.cmsText("description"))
        }
    }
}

/** One entry of `cms_content/gallery` → `data`. */
data class GalleryItem(
    val id: String,
    val title: String,
    val caption: String = "",
    val imageUrl: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "caption" to caption,
        "imageUrl" to imageUrl
    )

    companion object {
        fun parseList(raw: Any?): List<GalleryItem> = cmsEntries(raw).mapNotNull { m ->
            val id = m.usableId() ?: return@mapNotNull null
            val title = m.requiredText("title") ?: return@mapNotNull null
            GalleryItem(id = id, title = title, caption = m.cmsText("caption"), imageUrl = m.cmsText("imageUrl"))
        }
    }
}

// ───────────────────────── limits and ids ─────────────────────────

/** How many entries each list document may hold. */
object CmsLimits {
    const val FACILITIES = 20
    const val GALLERY = 100
    const val TESTIMONIALS = 50
    const val FAQS = 50
}

object CmsIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 8
    private val random = SecureRandom()

    /** A new list-item id: 8 random lowercase letters and digits. */
    fun newId(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
