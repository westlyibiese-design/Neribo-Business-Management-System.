package com.westly.nbms.features.reservations

import com.google.firebase.Timestamp
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private fun ts(seconds: Long) = Timestamp(seconds, 0)

private fun booking(
    id: String = "b1",
    name: String = "Ada Obi",
    room: String = "101",
    email: String? = "ada@example.com",
    code: String? = "WEB-AB12CD",
    status: String = "pending",
    source: String? = "website",
    createdAt: Timestamp? = ts(100),
    deleted: Boolean = false,
    nights: Int? = 1
) = Booking(
    id = id, bookingId = code, guestName = name, roomNumber = room, guestEmail = email,
    status = status, source = source, createdAt = createdAt, isDeleted = deleted, nights = nights
)

class ReservationRulesTest {

    private val lagos = ZoneId.of("Africa/Lagos")
    private fun at(text: String) = Instant.parse(text)

    // ---- Which bookings are reservations ------------------------------------------------------------

    @Test fun walkInIsNotAReservation() {
        assertFalse(ReservationRules.isRoomReservation(booking(source = "walk_in")))
    }

    @Test fun websiteAdminAndNullSourceAreReservations() {
        assertTrue(ReservationRules.isRoomReservation(booking(source = "website")))
        assertTrue(ReservationRules.isRoomReservation(booking(source = "admin")))
        assertTrue(ReservationRules.isRoomReservation(booking(source = null)))
    }

    @Test fun roomReservationsDropsDeletedAndWalkInsAndSortsNewestFirstNullLast() {
        val old = booking(id = "old", createdAt = ts(10))
        val newest = booking(id = "new", createdAt = ts(300))
        val none = booking(id = "none", createdAt = null)
        val mid = booking(id = "mid", createdAt = ts(200))
        val gone = booking(id = "gone", deleted = true, createdAt = ts(999))
        val walk = booking(id = "walk", source = "walk_in", createdAt = ts(998))
        val result = ReservationRules.roomReservations(listOf(old, none, gone, newest, walk, mid))
        assertEquals(listOf("new", "mid", "old", "none"), result.map { it.id })
    }

    // ---- Search -------------------------------------------------------------------------------------

    @Test fun searchMatchesEachOfTheFourFieldsCaseInsensitively() {
        val b = booking(name = "Ada Obi", room = "A12", email = "Ada@Example.com", code = "WEB-AB12CD")
        assertTrue(ReservationRules.matchesSearch(b, "ada o"))
        assertTrue(ReservationRules.matchesSearch(b, "a12"))
        assertTrue(ReservationRules.matchesSearch(b, "EXAMPLE.COM"))
        assertTrue(ReservationRules.matchesSearch(b, "web-ab12"))
        assertFalse(ReservationRules.matchesSearch(b, "zzz"))
    }

    @Test fun searchIsTrimmedAndBlankMatchesAll() {
        val b = booking(name = "Ada Obi")
        assertTrue(ReservationRules.matchesSearch(b, "   ada   "))
        assertTrue(ReservationRules.matchesSearch(b, ""))
        assertTrue(ReservationRules.matchesSearch(b, "    "))
    }

    @Test fun searchHandlesNullEmailAndCode() {
        val b = booking(email = null, code = null)
        assertFalse(ReservationRules.matchesSearch(b, "example"))
        assertTrue(ReservationRules.matchesSearch(b, "ada"))
    }

    // ---- Filters and counts -------------------------------------------------------------------------

    private val sample = listOf(
        booking(id = "1", name = "Ann", status = "pending"),
        booking(id = "2", name = "Ben", status = "confirmed"),
        booking(id = "3", name = "Cy", status = "confirmed"),
        booking(id = "4", name = "Dee", status = "checked_in"),
        booking(id = "5", name = "Eve", status = "checked_out"),
        booking(id = "6", name = "Fay", status = "cancelled"),
        booking(id = "7", name = "Gus", status = "rejected"),
        booking(id = "8", name = "Hal", status = "no_show")
    )

    @Test fun filteredByEveryFilterKeepsOrder() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), ReservationRules.filtered(sample, ReservationFilter.ALL, "").map { it.id })
        assertEquals(listOf("1"), ReservationRules.filtered(sample, ReservationFilter.PENDING, "").map { it.id })
        assertEquals(listOf("2", "3"), ReservationRules.filtered(sample, ReservationFilter.CONFIRMED, "").map { it.id })
        assertEquals(listOf("4"), ReservationRules.filtered(sample, ReservationFilter.CHECKED_IN, "").map { it.id })
        assertEquals(listOf("5"), ReservationRules.filtered(sample, ReservationFilter.CHECKED_OUT, "").map { it.id })
        assertEquals(listOf("6"), ReservationRules.filtered(sample, ReservationFilter.CANCELLED, "").map { it.id })
    }

    @Test fun filteredCombinesStatusAndSearch() {
        assertEquals(listOf("3"), ReservationRules.filtered(sample, ReservationFilter.CONFIRMED, "cy").map { it.id })
        assertTrue(ReservationRules.filtered(sample, ReservationFilter.PENDING, "cy").isEmpty())
        assertEquals(listOf("7"), ReservationRules.filtered(sample, ReservationFilter.ALL, "gus").map { it.id })
    }

    @Test fun countsForEveryChipRejectedAndNoShowOnlyInAll() {
        val c = ReservationRules.counts(sample)
        assertEquals(8, c[ReservationFilter.ALL])
        assertEquals(1, c[ReservationFilter.PENDING])
        assertEquals(2, c[ReservationFilter.CONFIRMED])
        assertEquals(1, c[ReservationFilter.CHECKED_IN])
        assertEquals(1, c[ReservationFilter.CHECKED_OUT])
        assertEquals(1, c[ReservationFilter.CANCELLED])
        assertEquals(ReservationFilter.entries.toList(), c.keys.toList())
        // rejected and no_show are counted only under ALL
        assertEquals(c.getValue(ReservationFilter.ALL) - 2, c.filterKeys { it != ReservationFilter.ALL }.values.sum())
    }

    @Test fun countsOfEmptyListAreZero() {
        assertTrue(ReservationRules.counts(emptyList()).values.all { it == 0 })
    }

    // ---- Actions ------------------------------------------------------------------------------------

    @Test fun awaitingCheckInOnlyPendingAndConfirmed() {
        assertTrue(ReservationRules.awaitingCheckIn(booking(status = "pending")))
        assertTrue(ReservationRules.awaitingCheckIn(booking(status = "confirmed")))
        listOf("checked_in", "checked_out", "cancelled", "rejected", "no_show").forEach {
            assertFalse(it, ReservationRules.awaitingCheckIn(booking(status = it)))
        }
    }

    @Test fun actionsForEveryStatusAndRole() {
        val allowedRoles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST)
        val pending = listOf(ReservationAction.CONFIRM_ARRIVAL, ReservationAction.REJECT, ReservationAction.CHECK_IN)
        val confirmed = listOf(ReservationAction.CHECK_IN, ReservationAction.CANCEL, ReservationAction.NO_SHOW)
        val statuses = listOf("pending", "confirmed", "checked_in", "checked_out", "cancelled", "rejected", "no_show")
        for (role in Role.entries) {
            for (status in statuses) {
                val expected = when {
                    role !in allowedRoles -> emptyList()
                    status == "pending" -> pending
                    status == "confirmed" -> confirmed
                    else -> emptyList()
                }
                assertEquals("$role / $status", expected, ReservationRules.actionsFor(booking(status = status), role))
            }
        }
    }

    // ---- Nights and entitled check-out --------------------------------------------------------------

    @Test fun nightsPaid() {
        assertEquals(1, ReservationRules.nightsPaid(booking(nights = null)))
        assertEquals(1, ReservationRules.nightsPaid(booking(nights = 0)))
        assertEquals(1, ReservationRules.nightsPaid(booking(nights = 1)))
        assertEquals(3, ReservationRules.nightsPaid(booking(nights = 3)))
    }

    @Test fun entitledCheckOutOneTwoAndSevenNights() {
        val checkIn = at("2026-10-09T09:00:00Z") // 10:00 in Lagos
        assertEquals(at("2026-10-10T10:00:00Z"), ReservationRules.entitledCheckOut(checkIn, 1, "11:00", lagos))
        assertEquals(at("2026-10-11T10:00:00Z"), ReservationRules.entitledCheckOut(checkIn, 2, "11:00", lagos))
        assertEquals(at("2026-10-16T10:00:00Z"), ReservationRules.entitledCheckOut(checkIn, 7, "11:00", lagos))
    }

    @Test fun entitledCheckOutDefaultsToEleven() {
        val checkIn = at("2026-10-09T09:00:00Z")
        val expected = at("2026-10-10T10:00:00Z")
        assertEquals(expected, ReservationRules.entitledCheckOut(checkIn, 1, null, lagos))
        assertEquals(expected, ReservationRules.entitledCheckOut(checkIn, 1, "", lagos))
        assertEquals(expected, ReservationRules.entitledCheckOut(checkIn, 1, "   ", lagos))
        assertEquals(expected, ReservationRules.entitledCheckOut(checkIn, 1, "garbage", lagos))
        assertEquals(expected, ReservationRules.entitledCheckOut(checkIn, 1, "25:00", lagos))
    }

    @Test fun entitledCheckOutCustomTime() {
        val checkIn = at("2026-10-09T09:00:00Z")
        assertEquals(at("2026-10-10T11:30:00Z"), ReservationRules.entitledCheckOut(checkIn, 1, "12:30", lagos))
    }

    @Test fun entitledCheckOutUsesTheLocalDateNearMidnight() {
        // 23:50 in Lagos on 9 Oct -> still the 9th locally
        assertEquals(
            at("2026-10-10T10:00:00Z"),
            ReservationRules.entitledCheckOut(at("2026-10-09T22:50:00Z"), 1, "11:00", lagos)
        )
        // 00:05 in Lagos on 10 Oct (still the 9th in UTC) -> the 10th locally
        assertEquals(
            at("2026-10-11T10:00:00Z"),
            ReservationRules.entitledCheckOut(at("2026-10-09T23:05:00Z"), 1, "11:00", lagos)
        )
    }

    @Test fun entitledCheckOutInAnotherZone() {
        // 19:50 on 9 Oct in New York (UTC-4) + 2 nights -> 11 Oct 11:00 New York = 15:00 UTC
        assertEquals(
            at("2026-10-11T15:00:00Z"),
            ReservationRules.entitledCheckOut(at("2026-10-09T23:50:00Z"), 2, "11:00", ZoneId.of("America/New_York"))
        )
    }

    @Test fun entitledCheckOutNullWhenNoNights() {
        assertNull(ReservationRules.entitledCheckOut(at("2026-10-09T09:00:00Z"), 0, "11:00", lagos))
        assertNull(ReservationRules.entitledCheckOut(at("2026-10-09T09:00:00Z"), -2, "11:00", lagos))
    }

    @Test fun normalizeCheckOutTime() {
        assertEquals("11:00", normalizeCheckOutTime(null))
        assertEquals("11:00", normalizeCheckOutTime("noon"))
        assertEquals("12:30", normalizeCheckOutTime(" 12:30 "))
        assertEquals("9:05", normalizeCheckOutTime("9:05"))
    }
}
