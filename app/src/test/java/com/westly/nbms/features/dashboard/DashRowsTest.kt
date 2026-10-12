package com.westly.nbms.features.dashboard

import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.features.dashboard.data.ActivityItem
import com.westly.nbms.features.dashboard.data.RawDashAttendance
import com.westly.nbms.features.dashboard.data.RawDashBooking
import com.westly.nbms.features.dashboard.data.RawDashExpense
import com.westly.nbms.features.dashboard.data.RawDashGymMember
import com.westly.nbms.features.dashboard.data.RawDashGymVisit
import com.westly.nbms.features.dashboard.data.RawDashLaundry
import com.westly.nbms.features.dashboard.data.RawDashMaintenance
import com.westly.nbms.features.dashboard.data.RawDashOrder
import com.westly.nbms.features.dashboard.data.RawDashRoom
import com.westly.nbms.features.dashboard.data.RawDashTask
import com.westly.nbms.features.dashboard.data.dashZoneOf
import com.westly.nbms.features.dashboard.data.filterRows
import com.westly.nbms.features.dashboard.data.mapRows
import com.westly.nbms.features.dashboard.data.newestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DashRowsTest {

    private val stamp = Timestamp(1_760_000_000L, 500_000_000)
    private val instant = Instant.ofEpochSecond(1_760_000_000L, 500_000_000L)

    @Test fun roomNumberIsReadFromTheStoredNumberField() {
        val row = RawDashRoom(id = "r1", number = "204", status = "occupied", isDeleted = true).toDash()
        assertEquals("r1", row.id)
        assertEquals("204", row.roomNumber)
        assertEquals("occupied", row.status)
        assertTrue(row.isDeleted)
        assertNull(RawDashRoom().toDash().roomNumber)
    }

    @Test fun bookingTimestampsBecomeInstants() {
        val row = RawDashBooking(
            id = "b1", guestName = "Ada", roomNumber = "12", roomType = "Deluxe", status = "pending", source = "walk_in",
            checkIn = stamp, checkOut = stamp, checkInAt = null, checkOutAt = stamp, createdAt = stamp
        ).toDash()
        assertEquals("Ada", row.guestName)
        assertEquals("walk_in", row.source)
        assertEquals(instant, row.checkIn)
        assertEquals(instant, row.checkOut)
        assertNull(row.checkInAt)
        assertEquals(instant, row.checkOutAt)
        assertEquals(instant, row.createdAt)
        assertEquals(false, row.isDeleted)
    }

    @Test fun expenseKeepsAmountAndDate() {
        val row = RawDashExpense(id = "e1", amount = 1250.5, date = stamp).toDash()
        assertEquals(1250.5, row.amount, 0.0)
        assertEquals(instant, row.date)
        assertNull(RawDashExpense().toDash().date)
    }

    @Test fun orderWaiterComesFromWaiterIdThenBarAttendantId() {
        assertEquals("w1", RawDashOrder(waiterId = "w1", barAttendantId = "b1").toDash().waiterId)
        assertEquals("b1", RawDashOrder(waiterId = null, barAttendantId = "b1").toDash().waiterId)
        assertEquals("b1", RawDashOrder(waiterId = "", barAttendantId = "b1").toDash().waiterId)
        assertNull(RawDashOrder().toDash().waiterId)
        val row = RawDashOrder(id = "o1", status = "pending", customerName = "Ada", roomNumber = "3", tableNumber = "T4", createdAt = stamp).toDash()
        assertEquals("pending", row.status)
        assertEquals("Ada", row.customerName)
        assertEquals("3", row.roomNumber)
        assertEquals("T4", row.tableNumber)
        assertEquals(instant, row.createdAt)
    }

    @Test fun laundryRowKeepsStatusGuestAndRoom() {
        val row = RawDashLaundry(id = "l1", status = "washing", guestName = "Ada", roomNumber = "9").toDash()
        assertEquals("washing", row.status)
        assertEquals("Ada", row.guestName)
        assertEquals("9", row.roomNumber)
    }

    @Test fun maintenanceMapsDescriptionToIssueAndRoomNumberToRoomOrArea() {
        val stored = RawDashMaintenance(id = "m1", status = "open", title = "Leaking tap", description = "Drips all night", roomNumber = "12").toDash()
        assertEquals("Leaking tap", stored.title)
        assertEquals("Drips all night", stored.issue)
        assertEquals("12", stored.roomOrArea)
        // a request with no room is stored with "—" as its room number: fall back to the area
        val noRoom = RawDashMaintenance(roomNumber = "—", area = "Lobby").toDash()
        assertEquals("Lobby", noRoom.roomOrArea)
        // an explicit issue wins over the description
        assertEquals("Broken lock", RawDashMaintenance(issue = "Broken lock", description = "x").toDash().issue)
    }

    @Test fun attendanceKeepsItsDateKey() {
        val row = RawDashAttendance(id = "a1", staffId = "s1", staffName = "Ada", dateKey = "2026-10-12", status = "present").toDash(ZoneId.of("Africa/Lagos"))
        assertEquals("2026-10-12", row.dateKey)
        assertEquals("s1", row.staffId)
        assertEquals("Ada", row.staffName)
        assertEquals("present", row.status)
    }

    @Test fun olderAttendanceWithoutDateKeyReadsTheDateInTheBusinessZone() {
        // 23:30Z on 11 October is 00:30 on 12 October in Lagos, but still 11 October in UTC
        val date = Timestamp(Instant.parse("2026-10-11T23:30:00Z").epochSecond, 0)
        assertEquals("2026-10-12", RawDashAttendance(date = date).toDash(ZoneId.of("Africa/Lagos")).dateKey)
        assertEquals("2026-10-11", RawDashAttendance(dateKey = "  ", date = date).toDash(ZoneId.of("UTC")).dateKey)
        assertNull(RawDashAttendance().toDash(ZoneId.of("Africa/Lagos")).dateKey)
    }

    @Test fun taskKeepsStatusAndDueTime() {
        val row = RawDashTask(id = "t1", status = "in_progress", dueAt = stamp).toDash()
        assertEquals("in_progress", row.status)
        assertEquals(instant, row.dueAt)
    }

    @Test fun gymRowsKeepWhatGymMathNeeds() {
        val member = RawDashGymMember(id = "g1", name = "Ada", status = "active", endDate = stamp).toDash()
        assertEquals("Ada", member.name)
        assertEquals("active", member.status)
        assertEquals(instant, member.endDate)
        val visit = RawDashGymVisit(id = "v1", memberId = "g1", checkInAt = stamp, checkOutAt = null, dateKey = "2026-10-12").toDash()
        assertEquals("g1", visit.memberId)
        assertEquals(instant, visit.checkInAt)
        assertNull(visit.checkOutAt)
        assertEquals("2026-10-12", visit.dateKey)
    }

    @Test fun resourceMappingPassesLoadingAndErrorThrough() {
        val loading: Resource<List<Int>> = Resource.Loading
        val error: Resource<List<Int>> = Resource.Error("boom")
        assertSame(loading, loading.mapRows { it + 1 })
        assertSame(error, error.mapRows { it + 1 })
        assertEquals(Resource.Success(listOf(2, 3)), Resource.Success(listOf(1, 2)).mapRows { it + 1 })
        assertEquals(Resource.Success(listOf(2)), Resource.Success(listOf(1, 2, 3)).filterRows { it == 2 })
    }

    @Test fun activityFeedIsNewestFirstAndLimited() {
        val items = listOf(
            ActivityItem("booking", "old", 1_000L), ActivityItem("checkin", "new", 3_000L), ActivityItem("payment", "mid", 2_000L)
        )
        assertEquals(listOf("new", "mid"), newestActivity(items, 2).map { it.text })
        assertEquals(3, newestActivity(items, 10).size)
        assertTrue(newestActivity(items, 0).isEmpty())
        assertTrue(newestActivity(items, -5).isEmpty())
        assertTrue(newestActivity(emptyList(), 5).isEmpty())
    }

    @Test fun businessZoneFallsBackToLagosForAMissingOrUnknownName() {
        assertEquals(ZoneId.of("Pacific/Auckland"), dashZoneOf("Pacific/Auckland"))
        assertEquals(ZoneId.of("Africa/Lagos"), dashZoneOf(null))
        assertEquals(ZoneId.of("Africa/Lagos"), dashZoneOf(" "))
        assertEquals(ZoneId.of("Africa/Lagos"), dashZoneOf("Not/AZone"))
    }
}
