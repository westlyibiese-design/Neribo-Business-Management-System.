package com.westly.nbms.features.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VenueFormTest {

    private fun input(
        name: String = "Grand Ballroom",
        capacity: String = "200",
        price: String = ""
    ) = VenueFormInput(name = name, capacityText = capacity, priceText = price)

    @Test
    fun aBlankNameIsNotUsable() {
        assertFalse(hasVenueName(input(name = "")))
        assertFalse(hasVenueName(input(name = "   ")))
        assertTrue(hasVenueName(input()))
        assertEquals("Name required", MSG_VENUE_NAME_TITLE)
        assertEquals("Please give the venue a name.", MSG_VENUE_NAME_BODY)
    }

    @Test
    fun capacityAndPriceAreOptional() {
        assertFalse(validateVenueForm(input(capacity = "", price = "")).hasErrors)
    }

    @Test
    fun capacityMustBeAWholeNumberOfAtLeastOne() {
        assertEquals(MSG_VENUE_CAPACITY_INVALID, validateVenueForm(input(capacity = "0")).capacity)
        assertEquals(MSG_VENUE_CAPACITY_INVALID, validateVenueForm(input(capacity = "-3")).capacity)
        assertEquals(MSG_VENUE_CAPACITY_INVALID, validateVenueForm(input(capacity = "1.5")).capacity)
        assertEquals(MSG_VENUE_CAPACITY_INVALID, validateVenueForm(input(capacity = "many")).capacity)
        assertNull(validateVenueForm(input(capacity = "1")).capacity)
        assertNull(validateVenueForm(input(capacity = "200")).capacity)
    }

    @Test
    fun priceMustBeZeroOrMoreWhenGiven() {
        assertEquals(MSG_VENUE_PRICE_INVALID, validateVenueForm(input(price = "-1")).price)
        assertEquals(MSG_VENUE_PRICE_INVALID, validateVenueForm(input(price = "abc")).price)
        assertNull(validateVenueForm(input(price = "0")).price)
        assertNull(validateVenueForm(input(price = "250000")).price)
    }

    @Test
    fun payloadHasExactlyTheAgreedFields() {
        val fields = venueFields(
            VenueFormInput(
                name = " Grand Ballroom ", description = " Big hall ", size = " 450 sqm ",
                capacityText = "200", priceText = "250000", amenitiesText = "Stage, AV Equipment,, Parking",
                images = listOf(" a.jpg ", "", "b.jpg"), available = false
            )
        )
        assertEquals(
            setOf("name", "description", "size", "capacity", "price", "amenities", "available", "images"),
            fields.keys
        )
        assertEquals("Grand Ballroom", fields["name"])
        assertEquals("Big hall", fields["description"])
        assertEquals("450 sqm", fields["size"])
        assertEquals(200, fields["capacity"])
        assertEquals(250000.0, fields["price"])
        assertEquals(listOf("Stage", "AV Equipment", "Parking"), fields["amenities"])
        assertEquals(false, fields["available"])
        assertEquals(listOf("a.jpg", "b.jpg"), fields["images"])
    }

    @Test
    fun blankCapacityAndPriceAreSavedAsNull() {
        val fields = venueFields(input(capacity = "", price = " "))
        assertTrue(fields.containsKey("capacity"))
        assertTrue(fields.containsKey("price"))
        assertNull(fields["capacity"])
        assertNull(fields["price"])
    }

    @Test
    fun newVenueDocumentAddsIsDeletedFalseAndCreatedAt() {
        val doc = newVenueDocument(venueFields(input()), "SERVER_TIME")
        assertEquals(false, doc["isDeleted"])
        assertEquals("SERVER_TIME", doc["createdAt"])
        assertEquals(10, doc.size)
    }

    @Test
    fun newVenueIsAvailableByDefault() {
        assertTrue(VenueFormInput().available)
        assertTrue(Venue().available)
    }

    @Test
    fun venuesAreSortedAToZ() {
        val sorted = sortVenues(listOf(Venue(name = "pool deck"), Venue(name = "Grand Ballroom"), Venue(name = "Boardroom")))
        assertEquals(listOf("Boardroom", "Grand Ballroom", "pool deck"), sorted.map { it.name })
    }

    @Test
    fun filterChips() {
        val venues = listOf(Venue(id = "1", available = true), Venue(id = "2", available = false), Venue(id = "3", available = true))
        assertEquals(listOf("1", "2", "3"), filterVenues(venues, "all").map { it.id })
        assertEquals(listOf("1", "3"), filterVenues(venues, "available").map { it.id })
        assertEquals(listOf("2"), filterVenues(venues, "unavailable").map { it.id })
    }

    @Test
    fun availabilityAuditAction() {
        assertEquals("venue_availability:available→unavailable", availabilityAction(true, false))
        assertEquals("venue_availability:unavailable→available", availabilityAction(false, true))
    }

    @Test
    fun cardTexts() {
        assertEquals("200 guests", venueCapacityText(200))
        assertEquals("—", venueCapacityText(null))
        assertEquals("₦250,000", venuePriceText(250000.0, "₦"))
        assertEquals("Contact for pricing", venuePriceText(null, "₦"))
    }

    @Test
    fun snapshotHoldsTheOldValues() {
        val snap = venueSnapshot(Venue(name = "Hall", capacity = 50, price = null, available = false))
        assertEquals("Hall", snap["name"])
        assertEquals(50, snap["capacity"])
        assertNull(snap["price"])
        assertEquals(false, snap["available"])
    }
}
