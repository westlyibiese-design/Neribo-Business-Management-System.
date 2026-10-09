package com.westly.nbms.features.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomFormTest {

    private fun input(
        number: String = "101",
        price: String = "45000",
        capacity: String = "2"
    ) = RoomFormInput(number = number, priceText = price, capacityText = capacity)

    private val existing = listOf(
        Room(id = "a", number = "101"),
        Room(id = "b", number = "102"),
        Room(id = "c", number = "103", isDeleted = true)
    )

    // ---- validation ----

    @Test
    fun aValidFormHasNoErrors() {
        assertFalse(validateRoomForm(input(number = "104"), existing, null).hasErrors)
    }

    @Test
    fun roomNumberIsRequired() {
        assertEquals(MSG_NUMBER_REQUIRED, validateRoomForm(input(number = ""), existing, null).number)
        assertEquals(MSG_NUMBER_REQUIRED, validateRoomForm(input(number = "   "), existing, null).number)
    }

    @Test
    fun duplicateNumberIsRejectedWithTheWestlyMessage() {
        val errors = validateRoomForm(input(number = "101"), existing, null)
        assertEquals("A room with this number already exists.", errors.number)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun duplicateCheckIgnoresSpacesAndCapitals() {
        val rooms = listOf(Room(id = "a", number = "A1"))
        assertEquals(MSG_NUMBER_TAKEN, validateRoomForm(input(number = " a1 "), rooms, null).number)
    }

    @Test
    fun deletedRoomsDoNotBlockTheirNumber() {
        assertNull(validateRoomForm(input(number = "103"), existing, null).number)
    }

    @Test
    fun editingARoomMayKeepItsOwnNumberButNotTakeAnotherRoomsNumber() {
        assertNull(validateRoomForm(input(number = "101"), existing, editingId = "a").number)
        assertEquals(MSG_NUMBER_TAKEN, validateRoomForm(input(number = "102"), existing, editingId = "a").number)
    }

    @Test
    fun priceIsRequiredAndMustBeZeroOrMore() {
        assertEquals(MSG_PRICE_REQUIRED, validateRoomForm(input(price = ""), existing, null).price)
        assertEquals(MSG_PRICE_REQUIRED, validateRoomForm(input(price = "  "), existing, null).price)
        assertEquals(MSG_PRICE_INVALID, validateRoomForm(input(price = "-5"), existing, null).price)
        assertEquals(MSG_PRICE_INVALID, validateRoomForm(input(price = "abc"), existing, null).price)
        assertEquals(MSG_PRICE_INVALID, validateRoomForm(input(price = "1.2.3"), existing, null).price)
        assertNull(validateRoomForm(input(number = "104", price = "0"), existing, null).price)
        assertNull(validateRoomForm(input(number = "104", price = "45000.50"), existing, null).price)
    }

    @Test
    fun capacityMustBeAWholeNumberOfAtLeastOne() {
        assertEquals(MSG_CAPACITY_INVALID, validateRoomForm(input(capacity = "0"), existing, null).capacity)
        assertEquals(MSG_CAPACITY_INVALID, validateRoomForm(input(capacity = "-1"), existing, null).capacity)
        assertEquals(MSG_CAPACITY_INVALID, validateRoomForm(input(capacity = "2.5"), existing, null).capacity)
        assertNull(validateRoomForm(input(number = "104", capacity = "1"), existing, null).capacity)
        assertNull(validateRoomForm(input(number = "104", capacity = "6"), existing, null).capacity)
    }

    @Test
    fun blankCapacityMeansTheDefaultOfTwo() {
        assertEquals(2, parseCapacity(""))
        assertEquals(2, parseCapacity("  "))
        assertNull(validateRoomForm(input(number = "104", capacity = ""), existing, null).capacity)
    }

    @Test
    fun severalErrorsAreReportedTogether() {
        val e = validateRoomForm(input(number = "", price = "", capacity = "0"), existing, null)
        assertEquals(MSG_NUMBER_REQUIRED, e.number)
        assertEquals(MSG_PRICE_REQUIRED, e.price)
        assertEquals(MSG_CAPACITY_INVALID, e.capacity)
    }

    // ---- amenities and payload ----

    @Test
    fun amenitiesAreSplitOnCommasTrimmedAndBlanksDropped() {
        assertEquals(listOf("WiFi", "TV", "AC", "Minibar"), splitAmenities("WiFi, TV, AC, Minibar"))
        assertEquals(listOf("WiFi", "TV", "AC"), splitAmenities(" WiFi ,, TV , ,AC,"))
        assertEquals(emptyList<String>(), splitAmenities(""))
        assertEquals(emptyList<String>(), splitAmenities(" , ,"))
    }

    @Test
    fun payloadHasExactlyTheAgreedFields() {
        val fields = roomFields(
            RoomFormInput(
                number = " 101 ", floor = " 2 ", type = "Deluxe Room", name = " Ocean View ",
                priceText = "45000", capacityText = "3", status = RoomStatus.MAINTENANCE,
                amenitiesText = "WiFi, TV", description = " Nice ", images = listOf(" a.jpg ", "", "b.jpg")
            )
        )
        assertEquals(
            setOf("number", "name", "type", "price", "capacity", "floor", "description", "amenities", "status", "images"),
            fields.keys
        )
        assertEquals("101", fields["number"])
        assertEquals("Ocean View", fields["name"])
        assertEquals("Deluxe Room", fields["type"])
        assertEquals(45000.0, fields["price"])
        assertEquals(3, fields["capacity"])
        assertEquals("2", fields["floor"])
        assertEquals("Nice", fields["description"])
        assertEquals(listOf("WiFi", "TV"), fields["amenities"])
        assertEquals("maintenance", fields["status"])
        assertEquals(listOf("a.jpg", "b.jpg"), fields["images"])
    }

    @Test
    fun blankNameIsSavedAsNullAndBlankFloorAsOne() {
        val fields = roomFields(input().copy(name = "  ", floor = " "))
        assertNull(fields["name"])
        assertEquals("1", fields["floor"])
    }

    @Test
    fun fractionalPriceIsKept() {
        assertEquals(45000.5, roomFields(input(price = "45000.5"))["price"])
    }

    @Test
    fun newRoomDocumentAddsIsDeletedFalseAndCreatedAt() {
        val doc = newRoomDocument(roomFields(input()), createdAt = "SERVER_TIME")
        assertEquals(false, doc["isDeleted"])
        assertEquals("SERVER_TIME", doc["createdAt"])
        assertEquals("101", doc["number"])
        assertEquals(12, doc.size)
    }

    // ---- list helpers ----

    @Test
    fun roomsAreSortedByNumberLikeANumber() {
        val sorted = sortRooms(listOf(Room(number = "10"), Room(number = "2"), Room(number = "A1"), Room(number = "1")))
        assertEquals(listOf("1", "2", "10", "A1"), sorted.map { it.number })
    }

    @Test
    fun chipCountsAndFilter() {
        val rooms = listOf(
            Room(id = "1", status = "available"), Room(id = "2", status = "available"),
            Room(id = "3", status = "occupied"), Room(id = "4", status = "out_of_service")
        )
        val counts = roomCounts(rooms)
        assertEquals(4, counts["all"])
        assertEquals(2, counts["available"])
        assertEquals(1, counts["occupied"])
        assertEquals(0, counts["cleaning"])
        assertEquals(1, counts["out_of_service"])
        assertEquals(listOf("1", "2"), filterRoomsByStatus(rooms, "available").map { it.id })
        assertEquals(4, filterRoomsByStatus(rooms, "all").size)
    }

    @Test
    fun priceBoxText() {
        assertEquals("45000", priceInputText(45000.0))
        assertEquals("45000.5", priceInputText(45000.5))
        assertEquals("0", priceInputText(0.0))
    }

    @Test
    fun numberBoxesKeepOnlyDigitsAndOneDot() {
        assertEquals("4500", cleanNumberInput("4a5,0 0", allowDot = false))
        assertEquals("45.5", cleanNumberInput("4x5.5", allowDot = true))
        assertEquals("1.23", cleanNumberInput("1.2.3", allowDot = true)) // the second dot is dropped
        assertEquals("12", cleanNumberInput("1.2", allowDot = false))
    }

    @Test
    fun gridColumnsByWidth() {
        assertEquals(1, gridColumns(360f))
        assertEquals(1, gridColumns(599f))
        assertEquals(2, gridColumns(600f))
        assertEquals(2, gridColumns(839f))
        assertEquals(3, gridColumns(840f))
    }

    @Test
    fun statusWordsForToasts() {
        assertEquals("out of service", statusWords("out_of_service"))
        assertEquals("maintenance", statusWords("maintenance"))
    }

    @Test
    fun imageUrlLines() {
        assertEquals(listOf("a", "b"), parseImageUrls(" a \n\n  b  \n"))
        assertEquals(emptyList<String>(), parseImageUrls(""))
        assertEquals(emptyList<String>(), parseImageUrls(" \n \n"))
    }
}
