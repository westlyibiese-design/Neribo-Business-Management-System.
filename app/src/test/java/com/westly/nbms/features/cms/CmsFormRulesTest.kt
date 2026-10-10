package com.westly.nbms.features.cms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsFormRulesTest {

    // ── tab list ──

    @Test fun theTabsAreInTheSpecifiedOrderAndHeroIsTheDefault() {
        assertEquals(listOf("Hero", "About", "Page Banners", "Contact", "Testimonials", "FAQs"), CmsFormRules.TABS.map { it.label })
        assertEquals(CmsTab.HERO, CmsFormRules.DEFAULT_TAB)
    }

    // ── contact validation ──

    @Test fun aBlankEmailIsFineAndARealOneIsFine() {
        assertNull(CmsFormRules.emailError(""))
        assertNull(CmsFormRules.emailError("   "))
        assertNull(CmsFormRules.emailError("info@hotel.com"))
        assertNull(CmsFormRules.emailError("  info@hotel.com  "))
    }

    @Test fun anEmailThatIsNotAnEmailShowsTheInlineError() {
        listOf("hotel", "info@", "@hotel.com", "info@hotel", "in fo@hotel.com").forEach {
            assertEquals("Enter a valid email address.", CmsFormRules.emailError(it))
        }
    }

    @Test fun aBlankMapAddressIsFine() {
        assertNull(CmsFormRules.mapUrlError(""))
        assertNull(CmsFormRules.mapUrlError("  "))
    }

    @Test fun onlyHttpAndHttpsMapAddressesAreAccepted() {
        assertNull(CmsFormRules.mapUrlError("https://www.google.com/maps/embed?pb=abc"))
        assertNull(CmsFormRules.mapUrlError("http://maps.example.com/x"))
        assertNull(CmsFormRules.mapUrlError("HTTPS://MAPS.EXAMPLE.COM"))
        assertNull(CmsFormRules.mapUrlError("  https://maps.example.com  "))
        val message = "Enter a full web address starting with https://"
        listOf("ftp://maps.example.com", "www.google.com/maps", "javascript:alert(1)", "https://", "https:// x", "maps").forEach {
            assertEquals("for $it", message, CmsFormRules.mapUrlError(it))
        }
    }

    @Test fun contactErrorsCollectsBothFields() {
        val none = CmsFormRules.contactErrors(ContactContent())
        assertFalse(none.any)
        val both = CmsFormRules.contactErrors(ContactContent(email = "x", mapEmbedUrl = "y"))
        assertTrue(both.any)
        assertEquals("Enter a valid email address.", both.email)
        assertEquals("Enter a full web address starting with https://", both.mapEmbedUrl)
    }

    // ── founded-year filter ──

    @Test fun foundedKeepsDigitsOnlyAndAtMostFour() {
        assertEquals("2024", CmsFormRules.filterFounded("2024"))
        assertEquals("2024", CmsFormRules.filterFounded("20a2-4x5"))
        assertEquals("1999", CmsFormRules.filterFounded("19998"))
        assertEquals("", CmsFormRules.filterFounded("abc"))
        assertEquals("", CmsFormRules.filterFounded(""))
        assertEquals("19", CmsFormRules.filterFounded(" 1 9"))
    }

    // ── changed flags ──

    @Test fun anObjectIsChangedOnlyWhenTheTrimmedTextDiffers() {
        val saved = HeroContent(headline = "Welcome")
        assertFalse(CmsFormRules.heroChanged(saved, saved))
        assertFalse(CmsFormRules.heroChanged(saved.copy(headline = "  Welcome "), saved))
        assertTrue(CmsFormRules.heroChanged(saved.copy(headline = "Welcome!"), saved))
        assertTrue(CmsFormRules.aboutChanged(AboutContent(founded = "2001"), AboutContent()))
        assertFalse(CmsFormRules.aboutChanged(AboutContent(), AboutContent()))
        assertTrue(CmsFormRules.contactChanged(ContactContent(phone = "1"), ContactContent()))
        assertFalse(CmsFormRules.contactChanged(ContactContent(phone = " "), ContactContent()))
    }

    // ── banner preview ──

    private val withButton = PAGE_HERO_SECTIONS.first { it.docId == "gym_hero" }
    private val noButton = PAGE_HERO_SECTIONS.first { it.docId == "faq_hero" }

    @Test fun thePreviewTitleFallsBackToTheSectionLabel() {
        assertEquals("Gym Page Hero Banner", CmsFormRules.bannerPreview(PageHeroContent(), withButton).title)
        assertEquals("Gym Page Hero Banner", CmsFormRules.bannerPreview(PageHeroContent(title = "   "), withButton).title)
        assertEquals("Get Fit", CmsFormRules.bannerPreview(PageHeroContent(title = " Get Fit "), withButton).title)
    }

    @Test fun subtitleAndDescriptionShowOnlyWhenTyped() {
        val empty = CmsFormRules.bannerPreview(PageHeroContent(), withButton)
        assertNull(empty.subtitle)
        assertNull(empty.description)
        val typed = CmsFormRules.bannerPreview(PageHeroContent(subtitle = " Strong ", description = " Body "), withButton)
        assertEquals("Strong", typed.subtitle)
        assertEquals("Body", typed.description)
    }

    @Test fun theButtonPillShowsOnlyForASectionWithAButtonAndTypedText() {
        assertEquals("Join", CmsFormRules.bannerPreview(PageHeroContent(buttonText = " Join "), withButton).buttonText)
        assertNull(CmsFormRules.bannerPreview(PageHeroContent(buttonText = ""), withButton).buttonText)
        assertNull(CmsFormRules.bannerPreview(PageHeroContent(buttonText = "  "), withButton).buttonText)
        // a section without a button never shows the pill, even if the document holds some button text
        assertNull(CmsFormRules.bannerPreview(PageHeroContent(buttonText = "Join"), noButton).buttonText)
    }

    @Test fun bannerTextsMatchTheSpecification() {
        assertEquals("Changes go live on /gym as soon as you save.", CmsFormRules.bannerHelper(withButton))
        assertEquals("Gym Page Hero Banner is now live on the website.", CmsFormRules.bannerPublishedMessage(withButton))
    }

    @Test fun theEightBannersMatchTheSpecification() {
        assertEquals(
            listOf("contact_hero", "faq_hero", "facilities_hero", "rooms_hero", "restaurant_hero", "venue_hero", "gym_hero", "testimonials_hero"),
            PAGE_HERO_SECTIONS.map { it.docId }
        )
        assertEquals(listOf(false), PAGE_HERO_SECTIONS.filter { it.docId == "faq_hero" }.map { it.supportsButton })
        assertEquals(listOf(false), PAGE_HERO_SECTIONS.filter { it.docId == "testimonials_hero" }.map { it.supportsButton })
        assertEquals(6, PAGE_HERO_SECTIONS.count { it.supportsButton })
    }

    // ── lists ──

    @Test fun testimonialRules() {
        assertEquals("Testimonials (3)", CmsFormRules.testimonialsHeading(3))
        assertTrue(CmsFormRules.canAddTestimonial(49))
        assertFalse(CmsFormRules.canAddTestimonial(50))
        assertTrue(CmsFormRules.isValidTestimonial("Jane", "Great"))
        assertFalse(CmsFormRules.isValidTestimonial(" ", "Great"))
        assertFalse(CmsFormRules.isValidTestimonial("Jane", ""))
        assertEquals(
            "Are you sure you want to delete \"Jane\"? This will remove it from the public website immediately.",
            CmsFormRules.deleteTestimonialBody("Jane")
        )
        assertEquals("You've reached the maximum of 50 testimonials.", MSG_TESTIMONIALS_LIMIT)
    }

    @Test fun faqRules() {
        assertEquals("FAQ Items (0)", CmsFormRules.faqsHeading(0))
        assertTrue(CmsFormRules.canAddFaq(49))
        assertFalse(CmsFormRules.canAddFaq(50))
        assertTrue(CmsFormRules.isValidFaq("Q", "A"))
        assertFalse(CmsFormRules.isValidFaq("Q", " "))
        assertFalse(CmsFormRules.isValidFaq("", "A"))
        assertEquals(
            "Are you sure you want to delete \"When?\"? This will remove it from the public website immediately.",
            CmsFormRules.deleteFaqBody("When?")
        )
    }

    @Test fun renumberingMakesOrderPositionPlusOne() {
        val items = listOf(FaqItem("a", "Q1", "A1", 9), FaqItem("b", "Q2", "A2", 0), FaqItem("c", "Q3", "A3", 5))
        assertEquals(listOf(1, 2, 3), CmsListRules.renumberFaqs(items).map { it.order })
        assertEquals(listOf("a", "b", "c"), CmsListRules.renumberFaqs(items).map { it.id })
    }
}
