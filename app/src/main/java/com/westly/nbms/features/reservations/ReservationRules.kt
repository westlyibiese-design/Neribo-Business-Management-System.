package com.westly.nbms.features.reservations

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.Instant
import java.time.LocalTime
import java.time.ZoneId

internal const val DEFAULT_OFFICIAL_CHECK_OUT = "11:00"

private val CLOCK_TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

/** "HH:mm" (24-hour) -> time; null for anything else. */
internal fun parseOfficialTime(text: String?): LocalTime? {
    val match = CLOCK_TIME.matchEntire(text?.trim().orEmpty()) ?: return null
    return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

/** The saved check-out time when it is a valid "HH:mm", otherwise "11:00". */
internal fun normalizeCheckOutTime(saved: String?): String =
    saved?.trim()?.takeIf { parseOfficialTime(it) != null } ?: DEFAULT_OFFICIAL_CHECK_OUT

/** Pure list and decision rules of the Room Reservations screen (port of Westly's RoomReservationsPage). */
object ReservationRules {

    private val ACTION_ROLES = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST)

    fun isRoomReservation(b: Booking): Boolean = b.source != "walk_in"

    fun roomReservations(all: List<Booking>): List<Booking> =
        all.filter { !it.isDeleted && isRoomReservation(it) }
            .sortedWith { a, b ->
                val x = a.createdAt
                val y = b.createdAt
                when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> y.compareTo(x)
                }
            }

    fun matchesSearch(b: Booking, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(b.guestName, b.roomNumber, b.guestEmail.orEmpty(), b.bookingId.orEmpty())
            .any { it.lowercase().contains(q) }
    }

    fun filtered(reservations: List<Booking>, filter: ReservationFilter, query: String): List<Booking> =
        reservations.filter { b ->
            (filter == ReservationFilter.ALL || b.status == filter.key) && matchesSearch(b, query)
        }

    fun counts(reservations: List<Booking>): Map<ReservationFilter, Int> {
        val result = LinkedHashMap<ReservationFilter, Int>()
        for (f in ReservationFilter.entries) {
            result[f] = if (f == ReservationFilter.ALL) reservations.size else reservations.count { it.status == f.key }
        }
        return result
    }

    fun awaitingCheckIn(b: Booking): Boolean = b.status == "pending" || b.status == "confirmed"

    fun actionsFor(b: Booking, role: Role): List<ReservationAction> {
        if (role !in ACTION_ROLES || !awaitingCheckIn(b)) return emptyList()
        return if (b.status == "pending") {
            listOf(ReservationAction.CONFIRM_ARRIVAL, ReservationAction.REJECT, ReservationAction.CHECK_IN)
        } else {
            listOf(ReservationAction.CHECK_IN, ReservationAction.CANCEL, ReservationAction.NO_SHOW)
        }
    }

    fun nightsPaid(b: Booking): Int = (b.nights ?: 1).coerceAtLeast(1)

    /**
     * The check-in DATE (in [zone]) plus [nights] days, at [officialTime] ("HH:mm", "11:00" when null, blank or invalid),
     * in [zone]. Null when [nights] is less than 1.
     */
    fun entitledCheckOut(checkInAt: Instant, nights: Int, officialTime: String?, zone: ZoneId): Instant? {
        if (nights < 1) return null
        val time = parseOfficialTime(officialTime) ?: parseOfficialTime(DEFAULT_OFFICIAL_CHECK_OUT)!!
        val date = java.time.Instant.ofEpochSecond(checkInAt.epochSeconds, checkInAt.nanosecondsOfSecond.toLong())
            .atZone(zone).toLocalDate()
        val out = date.plusDays(nights.toLong()).atTime(time).atZone(zone).toInstant()
        return Instant.fromEpochSeconds(out.epochSecond, out.nano.toLong())
    }
}
