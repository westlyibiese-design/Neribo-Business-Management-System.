package com.westly.nbms.features.cms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsModelsTest {

    // ── object models ──

    @Test fun heroReadsEveryFieldAndWritesTheSameNames() {
        val raw = mapOf("headline" to "H", "subheadline" to "S", "ctaText" to "Book", "ctaLink" to "/rooms", "backgroundImage" to "u")
        val hero = HeroContent.parse(raw)
        assertEquals(HeroContent("H", "S", "Book", "/rooms", "u"), hero)
        assertEquals(raw, hero.toMap())
    }

    @Test fun aboutReadsEveryFieldAndWritesTheSameNames() {
        val raw = mapOf("title" to "T", "description" to "D", "mission" to "M", "founded" to "1999", "image" to "i")
        assertEquals(raw, AboutContent.parse(raw).toMap())
        assertEquals("M", AboutContent.parse(raw).mission)
    }

    @Test fun contactReadsEveryFieldAndWritesTheSameNames() {
        val raw = mapOf(
            "address" to "a", "phone" to "p", "email" to "e", "checkInTime" to "14:00", "checkOutTime" to "11:00", "mapEmbedUrl" to "m"
        )
        assertEquals(raw, ContactContent.parse(raw).toMap())
    }

    @Test fun pageHeroReadsEveryFieldAndWritesTheSameNames() {
        val raw = mapOf("title" to "t", "subtitle" to "s", "description" to "d", "buttonText" to "bt", "buttonLink" to "bl", "image" to "i")
        assertEquals(raw, PageHeroContent.parse(raw).toMap())
    }

    @Test fun objectModelsBecomeEmptyForNullNonObjectOrMissingData() {
        listOf<Any?>(null, "text", 5, listOf("a"), emptyMap<String, Any?>()).forEach { raw ->
            assertEquals(HeroContent(), HeroContent.parse(raw))
            assertEquals(AboutContent(), AboutContent.parse(raw))
            assertEquals(ContactContent(), ContactContent.parse(raw))
            assertEquals(PageHeroContent(), PageHeroContent.parse(raw))
        }
    }

    @Test fun wronglyTypedOrNullFieldsBecomeEmptyStringsAndGoodOnesSurvive() {
        val hero = HeroContent.parse(mapOf("headline" to 12, "subheadline" to null, "ctaText" to listOf("x"), "ctaLink" to "/ok", "backgroundImage" to true))
        assertEquals(HeroContent(ctaLink = "/ok"), hero)
        val contact = ContactContent.parse(mapOf("phone" to 123.5, "email" to "a@b.c"))
        assertEquals(ContactContent(email = "a@b.c"), contact)
    }

    @Test fun mapsWithNonTextKeysAreIgnoredSafely() {
        assertEquals(HeroContent(), HeroContent.parse(mapOf(1 to "x")))
    }

    // ── testimonials ──

    @Test fun testimonialsReadAndWriteTheContractFields() {
        val raw = listOf(mapOf("id" to "t1", "author" to "Ada", "role" to "Guest", "text" to "Lovely", "rating" to 4))
        val list = TestimonialItem.parseList(raw)
        assertEquals(listOf(TestimonialItem("t1", "Ada", "Guest", "Lovely", 4)), list)
        assertEquals(raw.single(), list.single().toMap())
    }

    @Test fun numbersStoredAsLongOrDoubleAreReadAsWholeNumbers() {
        val t = TestimonialItem.parseList(listOf(mapOf("id" to "a", "author" to "x", "rating" to 3L))).single()
        assertEquals(3, t.rating)
        val f = FaqItem.parseList(listOf(mapOf("id" to "a", "question" to "q", "order" to 6L))).single()
        assertEquals(6, f.order)
    }

    @Test fun testimonialRatingIsTolerantAndStaysBetweenOneAndFive() {
        fun rating(value: Any?): Int =
            TestimonialItem.parseList(listOf(mapOf("id" to "a", "author" to "x", "rating" to value))).single().rating
        assertEquals(5, rating(null))
        assertEquals(5, rating("4"))
        assertEquals(4, rating(4.0))
        assertEquals(5, rating(9))
        assertEquals(1, rating(0))
        assertEquals(1, rating(-3))
        assertEquals(5, rating(Double.NaN))
    }

    @Test fun testimonialsDropEntriesWithoutAnIdOrAuthorAndKeepTheRestInOrder() {
        val raw = listOf(
            null,
            "text",
            42,
            mapOf("author" to "No id", "text" to "x"),
            mapOf("id" to 7, "author" to "Numeric id"),
            mapOf("id" to "", "author" to "Blank id"),
            mapOf("id" to "t1", "text" to "No author"),
            mapOf("id" to "t2", "author" to 9),
            mapOf("id" to "t3", "author" to "Kept", "text" to null, "role" to 3),
            mapOf("id" to "t4", "author" to "Also kept")
        )
        val list = TestimonialItem.parseList(raw)
        assertEquals(listOf("t3", "t4"), list.map { it.id })
        assertEquals("", list[0].text)
        assertEquals("", list[0].role)
    }

    // ── FAQs ──

    @Test fun faqsReadAndWriteTheContractFields() {
        val raw = listOf(mapOf("id" to "f1", "question" to "Q?", "answer" to "A.", "order" to 2))
        val list = FaqItem.parseList(raw)
        assertEquals(listOf(FaqItem("f1", "Q?", "A.", 2)), list)
        assertEquals(raw.single(), list.single().toMap())
    }

    @Test fun faqsDropEntriesWithoutAnIdOrQuestionAndTolerateBadOrder() {
        val raw = listOf(
            mapOf("id" to "f1", "answer" to "no question"),
            mapOf("question" to "no id"),
            mapOf("id" to "f2", "question" to "Q", "answer" to 5, "order" to "3"),
            mapOf("id" to "f3", "question" to "Q3", "answer" to "A3", "order" to 2.0)
        )
        val list = FaqItem.parseList(raw)
        assertEquals(listOf("f2", "f3"), list.map { it.id })
        assertEquals(FaqItem("f2", "Q", "", 0), list[0])
        assertEquals(2, list[1].order)
    }

    // ── facilities ──

    @Test fun facilitiesReadAndWriteTheContractFields() {
        val raw = listOf(mapOf("id" to "a1", "name" to "Pool", "image" to "u", "description" to "Big"))
        val list = FacilityItem.parseList(raw)
        assertEquals(listOf(FacilityItem("a1", "Pool", "u", "Big")), list)
        assertEquals(raw.single(), list.single().toMap())
    }

    @Test fun facilitiesDropEntriesWithoutAnIdOrNameAndFillMissingFieldsWithEmptyText() {
        val raw = listOf(
            mapOf("id" to "a1", "description" to "no name"),
            mapOf("name" to "no id"),
            mapOf("id" to "a2", "name" to "Gym", "image" to null, "description" to 4),
            "junk"
        )
        assertEquals(listOf(FacilityItem("a2", "Gym", "", "")), FacilityItem.parseList(raw))
    }

    // ── gallery ──

    @Test fun galleryReadsAndWritesTheContractFields() {
        val raw = listOf(mapOf("id" to "g1", "title" to "Lobby", "caption" to "Evening", "imageUrl" to "u"))
        val list = GalleryItem.parseList(raw)
        assertEquals(listOf(GalleryItem("g1", "Lobby", "Evening", "u")), list)
        assertEquals(raw.single(), list.single().toMap())
    }

    @Test fun galleryDropsEntriesWithoutAnIdOrTitle() {
        val raw = listOf(
            mapOf("id" to "g1", "imageUrl" to "u"),
            mapOf("title" to "no id"),
            mapOf("id" to "g2", "title" to "Ok", "caption" to 1, "imageUrl" to null)
        )
        assertEquals(listOf(GalleryItem("g2", "Ok", "", "")), GalleryItem.parseList(raw))
    }

    @Test fun everyListParserGivesAnEmptyListForNonListData() {
        listOf<Any?>(null, "text", 5, mapOf("id" to "x", "name" to "y"), true).forEach { raw ->
            assertTrue(TestimonialItem.parseList(raw).isEmpty())
            assertTrue(FaqItem.parseList(raw).isEmpty())
            assertTrue(FacilityItem.parseList(raw).isEmpty())
            assertTrue(GalleryItem.parseList(raw).isEmpty())
        }
    }

    // ── banners, limits, ids ──

    @Test fun bannerTableHasTheEightSectionsInOrder() {
        val rows = PAGE_HERO_SECTIONS.map { listOf(it.docId, it.label, it.usedOn, it.imageFolder, it.supportsButton) }
        assertEquals(
            listOf(
                listOf("contact_hero", "Contact Page Hero Banner", "/contact", "cms-contact-hero", true),
                listOf("faq_hero", "FAQ Page Background", "/faq", "cms-faq-hero", false),
                listOf("facilities_hero", "Facilities Page Hero Banner", "/facilities", "cms-facilities-hero", true),
                listOf("rooms_hero", "Room List Page Hero Banner", "/rooms", "cms-rooms-hero", true),
                listOf("restaurant_hero", "Restaurant Menu Page Hero Banner", "/restaurant", "cms-restaurant-hero", true),
                listOf("venue_hero", "Venue Page Hero Banner", "/venues", "cms-venue-hero", true),
                listOf("gym_hero", "Gym Page Hero Banner", "/gym", "cms-gym-hero", true),
                listOf("testimonials_hero", "Testimonials Section Background", "/testimonials", "cms-testimonials-hero", false)
            ),
            rows
        )
        assertEquals(8, PAGE_HERO_SECTIONS.map { it.docId }.toSet().size)
    }

    @Test fun limitsMatchTheContract() {
        assertEquals(20, CmsLimits.FACILITIES)
        assertEquals(100, CmsLimits.GALLERY)
        assertEquals(50, CmsLimits.TESTIMONIALS)
        assertEquals(50, CmsLimits.FAQS)
    }

    @Test fun newIdsAreEightLowercaseLettersOrDigitsAndDifferEachTime() {
        val ids = List(300) { CmsIds.newId() }
        assertTrue(ids.all { Regex("[a-z0-9]{8}").matches(it) })
        assertEquals(300, ids.toSet().size)
    }

    @Test fun faqsAreRenumberedFromOneInTheirCurrentPosition() {
        val items = listOf(FaqItem("a", "Q1", "A1", 9), FaqItem("b", "Q2", "A2", 0), FaqItem("c", "Q3", "A3", 2))
        val renumbered = CmsListRules.renumberFaqs(items)
        assertEquals(listOf("a", "b", "c"), renumbered.map { it.id })
        assertEquals(listOf(1, 2, 3), renumbered.map { it.order })
        assertTrue(CmsListRules.renumberFaqs(emptyList()).isEmpty())
    }

    @Test fun theSaveGuardLetsOnlyTheFirstCallThroughUntilFinished() {
        val guard = CmsSaveGuard()
        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())
        guard.finish()
        assertTrue(guard.tryStart())
    }
}
