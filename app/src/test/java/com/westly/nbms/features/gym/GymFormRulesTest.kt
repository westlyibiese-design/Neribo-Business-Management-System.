package com.westly.nbms.features.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymFormRulesTest {

    // ── price ──

    @Test fun blankPriceIsZero() {
        assertEquals(0.0, GymFormRules.parsePrice("")!!, 0.0)
        assertEquals(0.0, GymFormRules.parsePrice("   ")!!, 0.0)
    }

    @Test fun plainAndFractionalPricesParse() {
        assertEquals(15000.0, GymFormRules.parsePrice("15000")!!, 0.0)
        assertEquals(1500.5, GymFormRules.parsePrice("1500.50")!!, 0.0)
        assertEquals(0.5, GymFormRules.parsePrice(".5")!!, 0.0)
        assertEquals(20.0, GymFormRules.parsePrice(" 20 ")!!, 0.0)
        assertEquals(0.0, GymFormRules.parsePrice("0")!!, 0.0)
    }

    @Test fun thousandsCommasAreIgnored() {
        assertEquals(15000.0, GymFormRules.parsePrice("15,000")!!, 0.0)
        assertEquals(1234567.0, GymFormRules.parsePrice("1,234,567")!!, 0.0)
    }

    @Test fun negativeAndInvalidPricesAreRejected() {
        assertNull(GymFormRules.parsePrice("-5"))
        assertNull(GymFormRules.parsePrice("-0.01"))
        assertNull(GymFormRules.parsePrice("abc"))
        assertNull(GymFormRules.parsePrice("12abc"))
        assertNull(GymFormRules.parsePrice("1e3"))
        assertNull(GymFormRules.parsePrice("NaN"))
        assertNull(GymFormRules.parsePrice("Infinity"))
        assertNull(GymFormRules.parsePrice("5d"))
        assertNull(GymFormRules.parsePrice("1.2.3"))
    }

    @Test fun priceTextShowsWholeAmountsWithoutDecimals() {
        assertEquals("15000", GymFormRules.priceToText(15000.0))
        assertEquals("1500.5", GymFormRules.priceToText(1500.5))
        assertEquals("0", GymFormRules.priceToText(0.0))
        assertEquals("0", GymFormRules.priceToText(-3.0))
        assertEquals("0", GymFormRules.priceToText(Double.NaN))
    }

    @Test fun priceTextRoundTripsThroughParse() {
        listOf(0.0, 1.0, 15000.0, 1500.5, 99.99).forEach {
            assertEquals(it, GymFormRules.parsePrice(GymFormRules.priceToText(it))!!, 0.0)
        }
    }

    // ── feature chips ──

    @Test fun addFeatureTrimsTheText() {
        assertEquals(listOf("Sauna"), GymFormRules.addFeature(emptyList(), "  Sauna  "))
    }

    @Test fun addFeatureKeepsOrder() {
        val list = GymFormRules.addFeature(GymFormRules.addFeature(emptyList(), "A"), "B")
        assertEquals(listOf("A", "B"), list)
    }

    @Test fun addFeatureIgnoresBlankEntries() {
        val start = listOf("A")
        assertEquals(start, GymFormRules.addFeature(start, ""))
        assertEquals(start, GymFormRules.addFeature(start, "    "))
    }

    @Test fun addFeatureIgnoresDuplicatesEvenWithOtherCase() {
        val start = listOf("Full equipment access")
        assertEquals(start, GymFormRules.addFeature(start, "Full equipment access"))
        assertEquals(start, GymFormRules.addFeature(start, "  full EQUIPMENT access "))
    }

    @Test fun removeFeatureDropsOnlyThatChip() {
        assertEquals(listOf("A", "C"), GymFormRules.removeFeature(listOf("A", "B", "C"), 1))
        assertEquals(listOf("B", "C"), GymFormRules.removeFeature(listOf("A", "B", "C"), 0))
        assertEquals(listOf("A", "B"), GymFormRules.removeFeature(listOf("A", "B", "C"), 2))
    }

    @Test fun removeFeatureWithBadIndexChangesNothing() {
        val list = listOf("A", "B")
        assertEquals(list, GymFormRules.removeFeature(list, -1))
        assertEquals(list, GymFormRules.removeFeature(list, 2))
        assertEquals(emptyList<String>(), GymFormRules.removeFeature(emptyList(), 0))
    }

    // ── changed checks ──

    @Test fun aboutChangedOnlyWhenTheTextDiffers() {
        assertFalse(GymFormRules.aboutChanged("Hello", "Hello"))
        assertTrue(GymFormRules.aboutChanged("Hello", "Hello there"))
        assertTrue(GymFormRules.aboutChanged("", "x"))
        assertTrue(GymFormRules.aboutChanged("x", ""))
    }

    @Test fun aboutSurroundingSpacesAreNotAChange() {
        assertFalse(GymFormRules.aboutChanged("Hello", "  Hello  "))
        assertFalse(GymFormRules.aboutChanged("", "   "))
    }

    @Test fun hoursChangedWhenAnyDayDiffers() {
        val saved = GymContent.DEFAULT_HOURS
        assertFalse(GymFormRules.hoursChanged(saved, saved.toList()))
        assertTrue(GymFormRules.hoursChanged(saved, GymFormRules.withClosed(saved, 6, true)))
        assertTrue(GymFormRules.hoursChanged(saved, GymFormRules.withOpenTime(saved, 0, 7, 30)))
        assertTrue(GymFormRules.hoursChanged(saved, GymFormRules.withCloseTime(saved, 3, 21, 0)))
    }

    @Test fun changingAndChangingBackIsNotAChange() {
        val saved = GymContent.DEFAULT_HOURS
        val edited = GymFormRules.withClosed(GymFormRules.withClosed(saved, 2, true), 2, false)
        assertFalse(GymFormRules.hoursChanged(saved, edited))
    }

    @Test fun hoursEditsTouchOnlyTheChosenDay() {
        val saved = GymContent.DEFAULT_HOURS
        val edited = GymFormRules.withOpenTime(saved, 2, 8, 5)
        assertEquals("08:05", edited[2].open)
        assertEquals(saved[2].close, edited[2].close)
        assertEquals(saved.filterIndexed { i, _ -> i != 2 }, edited.filterIndexed { i, _ -> i != 2 })
        assertEquals(7, edited.size)
    }

    @Test fun timeParsingAndFormatting() {
        val t = GymFormRules.parseTime("06:00")
        assertNotNull(t)
        assertEquals(6, t!!.hour)
        assertEquals(0, t.minute)
        assertEquals(22, GymFormRules.parseTime("22:30")!!.hour)
        assertNull(GymFormRules.parseTime("24:00"))
        assertNull(GymFormRules.parseTime("6:00"))
        assertNull(GymFormRules.parseTime("ab:cd"))
        assertNull(GymFormRules.parseTime(""))
        assertEquals("07:05", GymFormRules.formatTime(7, 5))
        assertEquals("22:00", GymFormRules.formatTime(22, 0))
    }

    // ── texts ──

    @Test fun packageSubtitleIsDurationDotPrice() {
        assertEquals("Monthly · ₦15,000", GymFormRules.packageSubtitle("Monthly", 15000.0))
        assertEquals("Quarterly · ₦40,500.50", GymFormRules.packageSubtitle("Quarterly", 40500.5))
        assertEquals("Weekly · ₦0", GymFormRules.packageSubtitle("Weekly", 0.0))
    }

    @Test fun packageSubtitleUsesTheBusinessSymbolAndTrimsTheDuration() {
        assertEquals("Annual · $120", GymFormRules.packageSubtitle("  Annual ", 120.0, "$"))
    }

    @Test fun blankDurationShowsMonthly() {
        assertEquals("Monthly · ₦5,000", GymFormRules.packageSubtitle("  ", 5000.0))
    }

    @Test fun headingShowsCountAndLimit() {
        assertEquals("Equipment & Services (3/20)", GymFormRules.countHeading("Equipment & Services", 3, GymContentLimits.EQUIPMENT))
        assertEquals("Membership Packages (0/12)", GymFormRules.countHeading("Membership Packages", 0, GymContentLimits.PACKAGES))
        assertEquals("Gym Gallery (24/24)", GymFormRules.countHeading("Gym Gallery", 24, GymContentLimits.GALLERY))
    }

    @Test fun deleteTitleQuotesTheName() {
        assertEquals("Delete \"Cardio Zone\"?", GymFormRules.deleteTitle("Cardio Zone"))
    }

    // ── tabs ──

    @Test fun tabsAreInTheAgreedOrderAndAboutIsTheDefault() {
        assertEquals(
            listOf("About", "Equipment & Services", "Hours", "Membership Packages", "Programs", "Gallery"),
            GymFormRules.TABS.map { it.label }
        )
        assertEquals(GymTab.ABOUT, GymFormRules.DEFAULT_TAB)
        assertEquals(0, GymFormRules.DEFAULT_TAB.ordinal)
        assertEquals(6, GymFormRules.TABS.size)
    }

    @Test fun defaultDurationIsMonthly() {
        assertEquals("Monthly", GymFormRules.DEFAULT_DURATION)
        assertEquals(GymFormRules.DEFAULT_DURATION, PackageItem(id = "x", name = "n").duration)
    }

    // ── routing ──

    @Test fun featureRegistersBothRoutesWithTheAgreedNav() {
        val feature = GymContentFeature()
        assertEquals("gymcontent", feature.id)
        assertEquals(listOf("gym/reports", "gym-cms"), feature.screens.map { it.route })
        val reports = feature.nav.first { it.route == "gym/reports" }
        val cms = feature.nav.first { it.route == "gym-cms" }
        assertEquals("Reports", reports.label)
        assertEquals(163, reports.order)
        assertEquals(GymFeature.GYM_ROLES, reports.roles)
        assertEquals("Membership Packages", cms.label)
        assertEquals("Gym", cms.group)
        assertEquals(164, cms.order)
        assertEquals(setOf(com.westly.nbms.core.rbac.Role.SUPER_ADMIN, com.westly.nbms.core.rbac.Role.MANAGER), cms.roles)
        assertEquals(com.westly.nbms.core.rbac.ModuleKey.GYM, cms.module)
        assertFalse(cms.roles!!.contains(com.westly.nbms.core.rbac.Role.GYM_STAFF))
        assertFalse(cms.roles!!.contains(com.westly.nbms.core.rbac.Role.OPERATIONS_MANAGER))
    }
}
