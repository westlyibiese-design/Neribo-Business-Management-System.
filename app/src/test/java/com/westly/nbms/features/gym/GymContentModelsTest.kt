package com.westly.nbms.features.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymContentModelsTest {

    private fun hoursRow(day: String) = mapOf("day" to day, "open" to "07:00", "close" to "21:30", "closed" to false)
    private val sevenDays = GymContent.DEFAULT_DAYS.map { hoursRow(it) }

    // ── constants ──

    @Test fun limitsAndIconsAreTheAgreedOnes() {
        assertEquals(20, GymContentLimits.EQUIPMENT)
        assertEquals(12, GymContentLimits.PACKAGES)
        assertEquals(20, GymContentLimits.PROGRAMS)
        assertEquals(24, GymContentLimits.GALLERY)
        assertEquals(listOf("Dumbbell", "Sparkles", "Waves", "Heart", "Users", "Trophy", "Timer", "Flame", "Zap"), GymIcons.ALL)
    }

    @Test fun defaultHoursAreMondayToSundayOpenSixToTen() {
        val h = GymContent.DEFAULT_HOURS
        assertEquals(listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"), h.map { it.day })
        assertTrue(h.all { it.open == "06:00" && it.close == "22:00" && !it.closed })
        assertEquals(h, GymContent().hours)
    }

    @Test fun sectionsCarryTheirKeyAndToast() {
        assertEquals(
            listOf("about", "equipment", "hours", "packages", "programs", "gallery"),
            GymSection.entries.map { it.key }
        )
        assertEquals("About Section Updated", GymSection.ABOUT.successToast)
        assertEquals("Equipment & Services Updated", GymSection.EQUIPMENT.successToast)
        assertEquals("Operating Hours Updated", GymSection.HOURS.successToast)
        assertEquals("Membership Packages Updated", GymSection.PACKAGES.successToast)
        assertEquals("Programs Updated", GymSection.PROGRAMS.successToast)
        assertEquals("Gym Gallery Updated", GymSection.GALLERY.successToast)
    }

    @Test fun itemDefaultsMatchTheContract() {
        assertEquals("Dumbbell", EquipmentItem("i", "n", description = "d").icon)
        val p = PackageItem("p", "Gold")
        assertEquals(0.0, p.price, 0.0)
        assertEquals("Monthly", p.duration)
        assertTrue(p.features.isEmpty())
        assertFalse(p.popular)
        assertEquals("", ProgramItem("g", "n", "d").image)
        assertEquals(HoursRow("Monday", "06:00", "22:00", false), HoursRow("Monday"))
    }

    // ── tolerant parsing ──

    @Test fun nothingStoredGivesAnEmptyContentWithDefaultHours() {
        assertEquals(GymContent(), GymContent.parse(null))
        assertEquals(GymContent(), GymContent.parse(emptyMap()))
    }

    @Test fun aFullDocumentIsReadFieldByField() {
        val raw = mapOf<String, Any?>(
            "about" to "Our gym",
            "equipment" to listOf(mapOf("id" to "e1", "name" to "Treadmill", "image" to "http://x/t.jpg", "description" to "Cardio", "icon" to "Flame")),
            "hours" to sevenDays,
            "packages" to listOf(mapOf("id" to "p1", "name" to "Gold", "price" to 15000, "duration" to "Monthly", "features" to listOf("Pool", " Sauna ", "", 5), "popular" to true)),
            "programs" to listOf(mapOf("id" to "g1", "name" to "Yoga", "description" to "Calm", "image" to "http://x/y.jpg")),
            "gallery" to listOf("http://x/1.jpg", " ", "http://x/2.jpg", 7)
        )
        val c = GymContent.parse(raw)
        assertEquals("Our gym", c.about)
        assertEquals(listOf(EquipmentItem("e1", "Treadmill", "http://x/t.jpg", "Cardio", "Flame")), c.equipment)
        assertEquals(7, c.hours.size)
        assertEquals(HoursRow("Monday", "07:00", "21:30", false), c.hours[0])
        assertEquals(listOf(PackageItem("p1", "Gold", 15000.0, "Monthly", listOf("Pool", "Sauna"), true)), c.packages)
        assertEquals(listOf(ProgramItem("g1", "Yoga", "Calm", "http://x/y.jpg")), c.programs)
        assertEquals(listOf("http://x/1.jpg", "http://x/2.jpg"), c.gallery)
    }

    @Test fun missingOrNonListFieldsBecomeEmpty() {
        val c = GymContent.parse(mapOf("about" to 5, "equipment" to "oops", "packages" to mapOf("a" to 1), "programs" to null, "gallery" to 3, "hours" to "x"))
        assertEquals("", c.about)
        assertTrue(c.equipment.isEmpty())
        assertTrue(c.packages.isEmpty())
        assertTrue(c.programs.isEmpty())
        assertTrue(c.gallery.isEmpty())
        assertEquals(GymContent.DEFAULT_HOURS, c.hours)
    }

    @Test fun hoursMustBeExactlySevenRows() {
        assertEquals(GymContent.DEFAULT_HOURS, GymContent.parse(mapOf("hours" to sevenDays.take(6))).hours)
        assertEquals(GymContent.DEFAULT_HOURS, GymContent.parse(mapOf("hours" to sevenDays + hoursRow("Extra"))).hours)
        assertEquals(GymContent.DEFAULT_HOURS, GymContent.parse(mapOf("hours" to emptyList<Any>())).hours)
        assertEquals(GymContent.DEFAULT_HOURS, GymContent.parse(mapOf("hours" to sevenDays.take(6) + "bad")).hours)
        assertEquals("07:00", GymContent.parse(mapOf("hours" to sevenDays)).hours[3].open)
    }

    @Test fun aBadDayOrTimeInsideSevenRowsFallsBackForThatRowOnly() {
        val rows = sevenDays.toMutableList()
        rows[1] = mapOf("day" to "", "open" to "7am", "close" to "25:00", "closed" to "yes")
        val h = GymContent.parse(mapOf("hours" to rows)).hours
        assertEquals(HoursRow("Tuesday", "06:00", "22:00", false), h[1])
        assertEquals(HoursRow("Monday", "07:00", "21:30", false), h[0])
    }

    @Test fun listEntriesThatAreNotMapsAreSkipped() {
        val c = GymContent.parse(mapOf("equipment" to listOf("junk", 4, mapOf("id" to "e1", "name" to "Bike", "description" to "Spin"))))
        assertEquals(listOf("e1"), c.equipment.map { it.id })
    }

    @Test fun anUnknownIconBecomesDumbbellAndAMissingIdGetsAStablePositionId() {
        val c = GymContent.parse(mapOf("equipment" to listOf(mapOf("name" to "A", "description" to "a", "icon" to "Rocket"), mapOf("id" to "  ", "name" to "B", "description" to "b"))))
        assertEquals(listOf("Dumbbell", "Dumbbell"), c.equipment.map { it.icon })
        assertEquals(listOf("equipment-0", "equipment-1"), c.equipment.map { it.id })
    }

    @Test fun packagePriceAndDurationAreReadTolerantly() {
        val c = GymContent.parse(
            mapOf(
                "packages" to listOf(
                    mapOf("id" to "a", "name" to "A", "price" to "12500.5"),
                    mapOf("id" to "b", "name" to "B", "price" to -5),
                    mapOf("id" to "c", "name" to "C", "price" to "abc", "duration" to "  "),
                    mapOf("id" to 7, "name" to "D", "price" to 100L, "duration" to "Weekly", "popular" to "true")
                )
            )
        )
        assertEquals(listOf(12500.5, 0.0, 0.0, 100.0), c.packages.map { it.price })
        assertEquals(listOf("Monthly", "Monthly", "Monthly", "Weekly"), c.packages.map { it.duration })
        assertEquals("7", c.packages[3].id)
        assertFalse(c.packages[3].popular) // only a real true counts
    }

    // ── what is written ──

    @Test fun listsAreStoredAsMapsWithExactlyTheAgreedFieldNames() {
        val c = GymContent(
            about = "About",
            equipment = listOf(EquipmentItem("e", "N", "img", "D", "Zap")),
            hours = GymContent.DEFAULT_HOURS,
            packages = listOf(PackageItem("p", "Gold", 100.0, "Weekly", listOf("a"), true)),
            programs = listOf(ProgramItem("g", "Yoga", "D", "img")),
            gallery = listOf("u1")
        )
        assertEquals("About", gymSectionValue(GymSection.ABOUT, c))
        assertEquals(listOf(mapOf("id" to "e", "name" to "N", "image" to "img", "description" to "D", "icon" to "Zap")), gymSectionValue(GymSection.EQUIPMENT, c))
        assertEquals(setOf("day", "open", "close", "closed"), (gymSectionValue(GymSection.HOURS, c) as List<*>).map { (it as Map<*, *>).keys }.first())
        assertEquals(7, (gymSectionValue(GymSection.HOURS, c) as List<*>).size)
        assertEquals(
            listOf(mapOf("id" to "p", "name" to "Gold", "price" to 100.0, "duration" to "Weekly", "features" to listOf("a"), "popular" to true)),
            gymSectionValue(GymSection.PACKAGES, c)
        )
        assertEquals(listOf(mapOf("id" to "g", "name" to "Yoga", "description" to "D", "image" to "img")), gymSectionValue(GymSection.PROGRAMS, c))
        assertEquals(listOf("u1"), gymSectionValue(GymSection.GALLERY, c))
    }

    @Test fun whatIsWrittenCanBeReadBackUnchanged() {
        val c = GymContent(
            about = "Hello",
            equipment = listOf(EquipmentItem("e", "N", "", "D", "Heart")),
            hours = GymContent.DEFAULT_HOURS.mapIndexed { i, r -> if (i == 6) r.copy(closed = true) else r },
            packages = listOf(PackageItem("p", "Gold", 2500.0, "Quarterly", listOf("x", "y"), true)),
            programs = listOf(ProgramItem("g", "Yoga", "D")),
            gallery = listOf("u1", "u2")
        )
        val stored = GymSection.entries.associate { it.key to gymSectionValue(it, c) }
        assertEquals(c, GymContent.parse(stored))
    }

    @Test fun phase29StillReadsThePackagesWeWrite() {
        val stored = mapOf("packages" to gymSectionValue(GymSection.PACKAGES, GymContent(packages = listOf(PackageItem("p1", "Gold", 15000.0, "Monthly")))))
        assertEquals(listOf(GymPackage("p1", "Gold", 15000.0, "Monthly")), parseGymPackages(stored))
    }

    // ── rules ──

    @Test fun newIdsAreEightCharactersAndDifferent() {
        val ids = List(200) { GymContentRules.newItemId() }
        assertTrue(ids.all { it.length == 8 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } })
        assertTrue(ids.toSet().size > 190)
    }

    @Test fun limitsAllowOneLessThanTheMaximum() {
        assertTrue(GymContentRules.canAddEquipment(19)); assertFalse(GymContentRules.canAddEquipment(20))
        assertTrue(GymContentRules.canAddPackage(11)); assertFalse(GymContentRules.canAddPackage(12))
        assertTrue(GymContentRules.canAddProgram(19)); assertFalse(GymContentRules.canAddProgram(20))
        assertTrue(GymContentRules.canAddGalleryImage(23)); assertFalse(GymContentRules.canAddGalleryImage(24))
        assertTrue(GymContentRules.canAddEquipment(0))
    }

    @Test fun validationNeedsTextAfterTrimming() {
        assertTrue(GymContentRules.isValidEquipment("Bike", "Spin"))
        assertFalse(GymContentRules.isValidEquipment("  ", "Spin"))
        assertFalse(GymContentRules.isValidEquipment("Bike", ""))
        assertTrue(GymContentRules.isValidPackage("Gold"))
        assertFalse(GymContentRules.isValidPackage(" \n"))
        assertTrue(GymContentRules.isValidProgram("Yoga", "Calm"))
        assertFalse(GymContentRules.isValidProgram("", "Calm"))
        assertFalse(GymContentRules.isValidProgram("Yoga", "  "))
    }

    @Test fun movingAnItemUpOrDown() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), GymContentRules.moveItem(l, 1, -1))
        assertEquals(listOf("a", "c", "b"), GymContentRules.moveItem(l, 1, 1))
        assertEquals(listOf("b", "c", "a"), GymContentRules.moveItem(l, 0, 1).let { GymContentRules.moveItem(it, 1, 1) })
    }

    @Test fun movingAtTheEndsOrWithABadIndexChangesNothing() {
        val l = listOf("a", "b", "c")
        assertEquals(l, GymContentRules.moveItem(l, 0, -1))
        assertEquals(l, GymContentRules.moveItem(l, 2, 1))
        assertEquals(l, GymContentRules.moveItem(l, -1, 1))
        assertEquals(l, GymContentRules.moveItem(l, 5, -1))
        assertEquals(l, GymContentRules.moveItem(l, 1, 0))
        assertEquals(l, GymContentRules.moveItem(l, 1, 2))
        assertEquals(emptyList<String>(), GymContentRules.moveItem(emptyList<String>(), 0, 1))
    }

    @Test fun removingByIndex() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "c"), GymContentRules.removeAt(l, 0))
        assertEquals(listOf("a", "b"), GymContentRules.removeAt(l, 2))
        assertEquals(l, GymContentRules.removeAt(l, 3))
        assertEquals(l, GymContentRules.removeAt(l, -1))
    }

    @Test fun upsertReplacesInPlaceOrAppends() {
        val l = listOf(ProgramItem("1", "A", "a"), ProgramItem("2", "B", "b"))
        val edited = GymContentRules.upsertById(l, ProgramItem("1", "A2", "a2")) { it.id }
        assertEquals(listOf("A2", "B"), edited.map { it.name })
        val added = GymContentRules.upsertById(l, ProgramItem("3", "C", "c")) { it.id }
        assertEquals(listOf("1", "2", "3"), added.map { it.id })
        assertNotEquals(l, added)
        assertEquals(2, l.size)
    }
}
