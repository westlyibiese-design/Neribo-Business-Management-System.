package com.westly.nbms.features.rooms

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomSearchTest {

    private val rooms = listOf(
        Room(id = "1", number = "101", name = "Ocean View Deluxe", type = "Deluxe Room", status = "available", price = 45000.0),
        Room(id = "2", number = "102", name = null, type = "Standard Room", status = "occupied", price = 30000.0),
        Room(id = "3", number = "201", name = "Skyline", type = "Executive Suite", status = "out_of_service", price = 90000.0),
        Room(id = "4", number = "202", name = null, type = "Junior Suite", status = "maintenance", price = 60000.0)
    )

    private fun ids(query: String) = filterRoomsByQuery(rooms, query).map { it.id }

    @Test
    fun blankQueryKeepsEveryRoom() {
        assertEquals(listOf("1", "2", "3", "4"), ids(""))
        assertEquals(listOf("1", "2", "3", "4"), ids("   "))
    }

    @Test
    fun matchesNumber() {
        assertEquals(listOf("1", "2"), ids("10"))
        assertEquals(listOf("3"), ids("201"))
    }

    @Test
    fun matchesNameIgnoringCapitals() {
        assertEquals(listOf("1"), ids("ocean"))
        assertEquals(listOf("3"), ids("SKYLINE"))
    }

    @Test
    fun matchesType() {
        assertEquals(listOf("3", "4"), ids("suite"))
        assertEquals(listOf("2"), ids("standard"))
    }

    @Test
    fun matchesStatusWithKeyOrWords() {
        assertEquals(listOf("2"), ids("occupied"))
        assertEquals(listOf("3"), ids("out_of_service"))
        assertEquals(listOf("3"), ids("out of service"))
        assertEquals(listOf("4"), ids("maint"))
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertEquals(emptyList<String>(), ids("penthouse"))
    }

    @Test
    fun selectedRoomText() {
        assertEquals("Room 101 — Deluxe Room (₦45,000/night)", roomSelectionText(rooms[0]))
        assertEquals("Room 102 — Standard Room (₦30,000/night)", roomSelectionText(rooms[1]))
    }

    @Test
    fun badgeText() {
        assertEquals("AVAILABLE", roomBadgeText("available"))
        assertEquals("OUT OF SERVICE", roomBadgeText("out_of_service"))
        assertEquals("CLEANING", roomBadgeText("cleaning"))
    }
}
