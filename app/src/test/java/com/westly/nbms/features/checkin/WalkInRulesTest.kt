package com.westly.nbms.features.checkin

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WalkInRulesTest {

    private val lagos = TimeZone.of("Africa/Lagos") // UTC+1, no daylight saving

    // ---- booking code, nights, total ----

    @Test fun bookingCodeIsWiPlusFirstSixInCapitals() {
        assertEquals("WI-AB12CD", bookingCode("ab12cdXYZ99"))
        assertEquals("WI-ABC", bookingCode("abc"))
    }

    @Test fun nightsAreDaysBetweenDatesWithMinimumOne() {
        assertEquals(2, stayNights(LocalDate(2026, 10, 10), LocalDate(2026, 10, 12)))
        assertEquals(1, stayNights(LocalDate(2026, 10, 10), LocalDate(2026, 10, 11)))
        assertEquals(1, stayNights(LocalDate(2026, 10, 10), LocalDate(2026, 10, 10)))
        assertEquals(1, stayNights(LocalDate(2026, 10, 10), LocalDate(2026, 10, 9)))
        assertEquals(30, stayNights(LocalDate(2026, 10, 10), LocalDate(2026, 11, 9)))
    }

    @Test fun totalIsPriceTimesNights() {
        assertEquals(75_000.0, stayTotal(25_000.0, 3), 0.0)
        assertEquals(12_500.5, stayTotal(12_500.5, 1), 0.0)
    }

    @Test fun priceLineUsesSingularAndPlural() {
        assertEquals("₦25,000 × 1 night", priceLine(25_000.0, 1, "₦"))
        assertEquals("₦25,000 × 3 nights", priceLine(25_000.0, 3, "₦"))
    }

    // ---- combining a date with HH:mm ----

    @Test fun combineUsesTheBusinessTimeZone() {
        // 11:00 in Lagos (UTC+1) is 10:00 UTC.
        assertEquals(
            Instant.parse("2026-10-12T10:00:00Z"),
            combineDateAndTime(LocalDate(2026, 10, 12), "11:00", lagos)
        )
        assertEquals(
            Instant.parse("2026-10-12T12:00:00Z"),
            combineDateAndTime(LocalDate(2026, 10, 12), "12:00", TimeZone.UTC)
        )
    }

    @Test fun combineFallsBackToElevenWhenTheTimeIsBroken() {
        val expected = Instant.parse("2026-10-12T10:00:00Z")
        assertEquals(expected, combineDateAndTime(LocalDate(2026, 10, 12), null, lagos))
        assertEquals(expected, combineDateAndTime(LocalDate(2026, 10, 12), "soon", lagos))
        assertEquals(expected, combineDateAndTime(LocalDate(2026, 10, 12), "25:99", lagos))
    }

    @Test fun clockTimeParsing() {
        assertEquals(LocalTime(9, 5), parseClockTime("9:05"))
        assertEquals(LocalTime(23, 59), parseClockTime(" 23:59 "))
        assertNull(parseClockTime("24:00"))
        assertNull(parseClockTime("11"))
        assertNull(parseClockTime(null))
    }

    @Test fun officialCheckOutTimeDefaultsToEleven() {
        assertEquals("11:00", officialCheckOutTime(null))
        assertEquals("11:00", officialCheckOutTime(""))
        assertEquals("11:00", officialCheckOutTime("noon"))
        assertEquals("12:30", officialCheckOutTime("12:30"))
    }

    @Test fun unknownTimeZoneFallsBackToLagos() {
        assertEquals(TimeZone.of("Africa/Lagos"), zoneOrLagos(null))
        assertEquals(TimeZone.of("Africa/Lagos"), zoneOrLagos("Mars/Base"))
        assertEquals(TimeZone.of("Europe/London"), zoneOrLagos("Europe/London"))
    }

    @Test fun captionNamesTheOfficialTime() {
        assertEquals(
            "Check-out time will be recorded as 11:00, the hotel's official check-out time (set by the Super Admin in Settings).",
            checkOutCaption("11:00")
        )
    }

    // ---- validation messages ----

    @Test fun missingCheckInGivesTheCheckInMessage() {
        val r = validateStay(null, LocalTime(14, 0), LocalDate(2026, 10, 12), "11:00", lagos)
        assertEquals(StayValidation.Invalid("Please enter a valid check-in date and time."), r)
        val r2 = validateStay(LocalDate(2026, 10, 10), null, LocalDate(2026, 10, 12), "11:00", lagos)
        assertEquals(StayValidation.Invalid("Please enter a valid check-in date and time."), r2)
    }

    @Test fun checkOutMustBeAfterCheckIn() {
        // Same day: check-in 14:00, official check-out 11:00 -> check-out is earlier.
        val sameDay = validateStay(LocalDate(2026, 10, 10), LocalTime(14, 0), LocalDate(2026, 10, 10), "11:00", lagos)
        assertEquals(StayValidation.Invalid("Check-out must be after check-in."), sameDay)
        // Check-out date before check-in date.
        val before = validateStay(LocalDate(2026, 10, 10), LocalTime(14, 0), LocalDate(2026, 10, 9), "11:00", lagos)
        assertEquals(StayValidation.Invalid("Check-out must be after check-in."), before)
        // Exactly equal counts as not after.
        val equal = validateStay(LocalDate(2026, 10, 10), LocalTime(11, 0), LocalDate(2026, 10, 10), "11:00", lagos)
        assertEquals(StayValidation.Invalid("Check-out must be after check-in."), equal)
    }

    @Test fun earlySameDayCheckInBeforeCheckOutTimeIsAllowed() {
        val r = validateStay(LocalDate(2026, 10, 10), LocalTime(8, 0), LocalDate(2026, 10, 10), "11:00", lagos)
        val valid = r as StayValidation.Valid
        assertEquals(Instant.parse("2026-10-10T07:00:00Z"), valid.checkIn)
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), valid.checkOut)
        assertEquals(1, valid.nights)
    }

    @Test fun validStayCarriesInstantsAndNights() {
        val r = validateStay(LocalDate(2026, 10, 10), LocalTime(14, 0), LocalDate(2026, 10, 12), "11:00", lagos)
        val valid = r as StayValidation.Valid
        assertEquals(Instant.parse("2026-10-10T13:00:00Z"), valid.checkIn)
        assertEquals(Instant.parse("2026-10-12T10:00:00Z"), valid.checkOut)
        assertEquals(2, valid.nights)
    }

    @Test fun requiredFieldMessages() {
        val empty = fieldErrors(WalkInForm())
        assertEquals("Full name is required.", empty.name)
        assertEquals("Phone number is required.", empty.phone)
        assertNull(empty.email)
        assertTrue(empty.any)

        val ok = fieldErrors(WalkInForm(fullName = "Ada", phone = "0803", email = "ada@example.com"))
        assertFalse(ok.any)

        val badEmail = fieldErrors(WalkInForm(fullName = "Ada", phone = "0803", email = "not-an-email"))
        assertEquals("Enter a valid email address.", badEmail.email)
        assertTrue(badEmail.any)

        // A blank name made of spaces counts as empty.
        assertEquals("Full name is required.", fieldErrors(WalkInForm(fullName = "   ", phone = "1")).name)
    }

    // ---- rooms: who can be submitted, what is said under the picker ----

    @Test fun onlyAnAvailableRoomCanBeSubmitted() {
        assertTrue(canSubmit(busy = false, room = testRoom(status = "available")))
        assertFalse(canSubmit(busy = false, room = testRoom(status = "occupied")))
        assertFalse(canSubmit(busy = false, room = testRoom(status = "cleaning")))
        assertFalse(canSubmit(busy = false, room = null))
        assertFalse(canSubmit(busy = true, room = testRoom(status = "available")))
    }

    @Test fun roomNotAvailableMessageNamesNumberAndStatus() {
        assertEquals(
            "Room 101 is currently occupied. Please choose a different room.",
            roomNotAvailableMessage("101", "occupied")
        )
        assertEquals(
            "Room 7 is currently out of service. Please choose a different room.",
            roomNotAvailableMessage("7", "out_of_service")
        )
    }

    @Test fun roomListDropsDeletedAndSortsByNumber() {
        val rooms = listOf(
            testRoom("a", "10"), testRoom("b", "2"), testRoom("c", "1").copy(isDeleted = true), testRoom("d", "A1")
        )
        assertEquals(listOf("2", "10", "A1"), listedRooms(rooms).map { it.number })
    }

    @Test fun noticeUnderThePicker() {
        assertEquals("Couldn't load rooms. Check your connection and reload.", roomsNotice(true, false, emptyList()))
        assertNull(roomsNotice(false, false, emptyList()))
        assertEquals("No rooms found.", roomsNotice(false, true, emptyList()))
        assertEquals(
            "No rooms are currently available — all rooms are occupied, cleaning, or under maintenance.",
            roomsNotice(false, true, listOf(testRoom(status = "occupied")))
        )
        assertNull(roomsNotice(false, true, listOf(testRoom(status = "occupied"), testRoom("r2", "102"))))
    }

    @Test fun transactionRoomCheck() {
        assertNull(roomCheckError(exists = true, status = "available"))
        assertEquals("Room not found.", roomCheckError(exists = false, status = null))
        assertEquals("Room is no longer available.", roomCheckError(exists = true, status = "occupied"))
        assertEquals("Room is no longer available.", roomCheckError(exists = true, status = null))
    }

    // ---- toast and feed texts ----

    @Test fun completeMessageDependsOnPaymentOption() {
        assertEquals(
            "Ada is now checked in. Payment sent to the Accountant for approval.",
            completeMessage("Ada", paidNow = true)
        )
        assertEquals(
            "Ada is now checked in. Payment will be collected at check-out.",
            completeMessage("Ada", paidNow = false)
        )
    }

    @Test fun activityTextHasTheArrow() {
        assertEquals("Walk-in: Ada Obi → Room 101", activityText("Ada Obi", "101"))
    }

    @Test fun paymentOptionHelpTexts() {
        assertEquals("Payment is recorded now and sent to the Accountant for approval.", PaymentOption.PAY_AT_CHECKIN.help)
        assertEquals(
            "No payment is recorded now — the booking is marked Payment Pending and charged at check-out.",
            PaymentOption.PAY_AT_CHECKOUT.help
        )
        assertEquals(listOf("cash", "credit_card", "debit_card", "bank_transfer"), PaymentMethod.entries.map { it.key })
        assertEquals(listOf("Cash", "Credit Card", "Debit Card", "Bank Transfer"), PaymentMethod.entries.map { it.label })
    }

    @Test fun dropdownRanges() {
        assertEquals(listOf(1, 2, 3, 4), ADULT_OPTIONS)
        assertEquals(listOf(0, 1, 2, 3), CHILD_OPTIONS)
    }

    // ---- the fresh form ----

    @Test fun freshFormStartsNowAndEndsTomorrow() {
        val now = Instant.parse("2026-10-09T22:30:00Z") // 23:30 in Lagos
        val form = WalkInForm.initial(now, lagos)
        assertEquals(LocalDate(2026, 10, 9), form.checkInDate)
        assertEquals(LocalTime(23, 30), form.checkInTime)
        assertEquals(LocalDate(2026, 10, 10), form.checkOutDate)
        assertEquals(1, form.adults)
        assertEquals(0, form.children)
        assertEquals(PaymentOption.PAY_AT_CHECKIN, form.paymentOption)
        assertEquals(PaymentMethod.CASH, form.paymentMethod)
    }

    // ---- activity feed key ----

    @Test fun pushIdsAreTwentyCharactersAndKeepTimeOrder() {
        val a = newPushId(1_700_000_000_000L, Random(1))
        val b = newPushId(1_700_000_000_001L, Random(1))
        val c = newPushId(1_800_000_000_000L, Random(1))
        assertEquals(20, a.length)
        assertTrue(a < b)
        assertTrue(b < c)
        assertEquals(a.take(8), newPushId(1_700_000_000_000L, Random(99)).take(8))
    }
}
