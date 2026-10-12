package com.westly.nbms.features.dashboard

import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.features.dashboard.data.DashAttendance
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashLaundry
import com.westly.nbms.features.dashboard.data.DashMaintenance
import com.westly.nbms.features.dashboard.data.DashOrder
import com.westly.nbms.features.dashboard.data.DashRoom
import com.westly.nbms.features.tasks.TaskDefaults
import com.westly.nbms.features.tasks.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsDashboardRulesTest {

    // ---- alert pills ----

    @Test fun noPillsWhenNothingApplies() {
        assertTrue(opsAlertPills(0, 0, 0).isEmpty())
    }

    @Test fun onlyThePillsThatApplyAreShown() {
        val pills = opsAlertPills(reservations = 0, overdueTasks = 2, openMaintenance = 0)
        assertEquals(listOf(OpsPill("2 overdue tasks", AlertTone.RED)), pills)
    }

    @Test fun allThreePillsInOrderWithTheirColours() {
        val pills = opsAlertPills(3, 2, 4)
        assertEquals(
            listOf(
                OpsPill("3 unconfirmed reservations", AlertTone.BLUE),
                OpsPill("2 overdue tasks", AlertTone.RED),
                OpsPill("4 open maintenance requests", AlertTone.ORANGE)
            ),
            pills
        )
    }

    @Test fun pillsUseTheSingularForExactlyOne() {
        val pills = opsAlertPills(1, 1, 1)
        assertEquals(
            listOf("1 unconfirmed reservation", "1 overdue task", "1 open maintenance request"),
            pills.map { it.text }
        )
    }

    // ---- sub lines ----

    @Test fun subLinesFollowThePluralRules() {
        assertEquals("1 unconfirmed reservation awaiting front-desk action", reservationsSub(1))
        assertEquals("0 unconfirmed reservations awaiting front-desk action", reservationsSub(0))
        assertEquals("1 active order · 0 unassigned", restaurantSub(1, 0))
        assertEquals("3 active orders · 2 unassigned", restaurantSub(3, 2))
        assertEquals("1 pending sale", barSub(1))
        assertEquals("5 pending sales", barSub(5))
        assertEquals("1 active request", laundrySub(1))
        assertEquals("2 active requests", laundrySub(2))
    }

    // ---- optional sections follow the enabled modules ----

    @Test fun sectionsFollowTheEnabledModules() {
        val none = opsVisibility(emptySet())
        assertFalse(none.housekeeping || none.restaurant || none.bar || none.laundry)
        val all = opsVisibility(setOf(ModuleKey.HOUSEKEEPING, ModuleKey.RESTAURANT, ModuleKey.BAR, ModuleKey.LAUNDRY, ModuleKey.GYM))
        assertTrue(all.housekeeping && all.restaurant && all.bar && all.laundry)
        val onlyBar = opsVisibility(setOf(ModuleKey.BAR))
        assertTrue(onlyBar.bar)
        assertFalse(onlyBar.housekeeping || onlyBar.restaurant || onlyBar.laundry)
    }

    // ---- Room Reservations ----

    private fun reservation(id: String, guest: String? = "Guest $id", type: String? = "Deluxe") = DashBooking(
        id = id, guestName = guest, roomType = type, status = "pending", source = "online",
        checkIn = dashAt("2026-10-14T14:00:00")
    )

    @Test fun reservationRowsShowGuestAndRoomType() {
        val rows = reservationRows(listOf(reservation("a", "Ada", "Deluxe")), DASH_LAGOS)
        assertEquals("Ada · Deluxe", rows.single().primary)
    }

    @Test fun reservationAssignDefaultsAreExact() {
        val row = reservationRows(listOf(reservation("b1", "Ada", "Deluxe")), DASH_LAGOS).single()
        val date = dashDate(dashAt("2026-10-14T14:00:00"), DASH_LAGOS)
        assertEquals(
            TaskDefaults(
                type = TaskType.BOOKING,
                title = "Confirm & process booking — Ada",
                relatedCollection = "bookings",
                relatedId = "b1",
                relatedLabel = "Deluxe · $date"
            ),
            row.defaults
        )
    }

    @Test fun reservationWithoutNameOrTypeFallsBackToGuest() {
        val row = reservationRows(listOf(reservation("c", guest = "  ", type = null)), DASH_LAGOS).single()
        assertEquals("Guest", row.primary)
        assertEquals("Confirm & process booking — Guest", row.defaults.title)
        assertEquals(dashDate(dashAt("2026-10-14T14:00:00"), DASH_LAGOS), row.defaults.relatedLabel)
    }

    @Test fun reservationRowsAreCappedAtSix() {
        val rows = reservationRows((1..9).map { reservation("r$it") }, DASH_LAGOS)
        assertEquals(6, rows.size)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r6"), rows.map { it.defaults.relatedId })
    }

    // ---- Room Readiness ----

    @Test fun readinessRowsAreTheCleaningRoomsOnly() {
        val rooms = listOf(
            DashRoom("1", "101", "cleaning"), DashRoom("2", "102", "available"), DashRoom("3", "103", "cleaning", isDeleted = true),
            DashRoom("4", "104", "cleaning")
        )
        assertEquals(listOf("Room 101", "Room 104"), readinessRows(rooms).map { it.primary })
    }

    @Test fun readinessAssignDefaultsAreExact() {
        val row = readinessRows(listOf(DashRoom("rm9", "204", "cleaning"))).single()
        assertEquals(
            TaskDefaults(
                type = TaskType.HOUSEKEEPING,
                title = "Clean Room 204",
                relatedCollection = "rooms",
                relatedId = "rm9",
                relatedLabel = "Room 204"
            ),
            row.defaults
        )
    }

    @Test fun readinessRowsAreCappedAtSix() {
        val rooms = (1..8).map { DashRoom("id$it", "30$it", "cleaning") }
        assertEquals(6, readinessRows(rooms).size)
    }

    // ---- Maintenance ----

    @Test fun maintenanceAssignDefaultsAreExact() {
        val m = DashMaintenance(id = "m1", status = "open", issue = "Leaking tap", title = "Plumbing", roomOrArea = "Room 12")
        val row = maintenanceRows(listOf(m)).single()
        assertEquals("Leaking tap", row.primary)
        assertEquals("Room 12", row.secondary)
        assertEquals(
            TaskDefaults(
                type = TaskType.MAINTENANCE,
                title = "Resolve: Leaking tap",
                relatedCollection = "maintenance",
                relatedId = "m1",
                relatedLabel = "Room 12"
            ),
            row.defaults
        )
    }

    @Test fun maintenanceRowTextFallsBackFromIssueToTitleToArea() {
        val byTitle = maintenanceRows(listOf(DashMaintenance(id = "a", title = "Lift service", roomOrArea = "Lobby"))).single()
        assertEquals("Lift service", byTitle.primary)
        val byArea = maintenanceRows(listOf(DashMaintenance(id = "b", roomOrArea = "Pool"))).single()
        assertEquals("Pool", byArea.primary)
        assertNull(byArea.secondary)            // the area is already the row text
        val bare = maintenanceRows(listOf(DashMaintenance(id = "c"))).single()
        assertEquals("Maintenance request", bare.primary)
    }

    @Test fun maintenanceTitleUsesTheFallbackIssueWordsWhenThereIsNoIssue() {
        val row = maintenanceRows(listOf(DashMaintenance(id = "d", title = "Lift service"))).single()
        assertEquals("Resolve: maintenance issue", row.defaults.title)
        assertNull(row.defaults.relatedLabel)
    }

    @Test fun maintenanceRowsAreCappedAtSix() {
        assertEquals(6, maintenanceRows((1..7).map { DashMaintenance(id = "m$it", issue = "Issue $it") }).size)
    }

    // ---- Restaurant ----

    @Test fun restaurantAssignDefaultsAreExact() {
        val withRoom = restaurantRows(listOf(DashOrder(id = "o1", status = "pending", roomNumber = "12"))).single()
        assertEquals("Room 12", withRoom.primary)
        assertEquals(
            TaskDefaults(
                type = TaskType.FOOD_ORDER,
                title = "Fulfill food order — Room 12",
                relatedCollection = "orders",
                relatedId = "o1",
                relatedLabel = "Room 12"
            ),
            withRoom.defaults
        )
    }

    @Test fun restaurantLabelIsRoomThenTableThenNothing() {
        fun label(o: DashOrder) = restaurantRows(listOf(o)).single().defaults.relatedLabel
        assertEquals("Room 3", label(DashOrder(id = "a", customerName = "Ada", roomNumber = "3", tableNumber = "9")))
        assertEquals("Table 9", label(DashOrder(id = "b", tableNumber = "9")))
        assertNull(label(DashOrder(id = "c", customerName = "Ada")))
    }

    @Test fun restaurantRowsUseThePartyLabelAndAreCappedAtSix() {
        val orders = (1..8).map { DashOrder(id = "o$it", customerName = if (it == 1) "Ada" else null, tableNumber = "$it") }
        val rows = restaurantRows(orders)
        assertEquals(6, rows.size)
        assertEquals("Ada", rows[0].primary)
        assertEquals("Table 2", rows[1].primary)
    }

    // ---- Bar ----

    @Test fun barAssignDefaultsAreExact() {
        val row = barRows(listOf(DashOrder(id = "b7", status = "pending", customerName = "Tolu"))).single()
        assertEquals("Tolu", row.primary)
        assertEquals(
            TaskDefaults(
                type = TaskType.DRINK_ORDER,
                title = "Fulfill drink order — Tolu",
                relatedCollection = "bar_orders",
                relatedId = "b7"
            ),
            row.defaults
        )
    }

    @Test fun barRowsAreCappedAtSix() {
        assertEquals(6, barRows((1..10).map { DashOrder(id = "b$it") }).size)
    }

    // ---- Laundry ----

    @Test fun laundryRowsShowGuestOrRoomWithACapitalisedStatusBadge() {
        val rows = laundryRows(
            listOf(
                DashLaundry(id = "l1", status = "in_progress", guestName = "Ada", roomNumber = "5"),
                DashLaundry(id = "l2", status = "pending", roomNumber = "7"),
                DashLaundry(id = "l3", status = "washing")
            )
        )
        assertEquals(listOf("Ada", "Room 7", "Guest"), rows.map { it.primary })
        assertEquals(listOf("In progress", "Pending", "Washing"), rows.map { it.badge })
    }

    @Test fun laundryAssignDefaultsAreExact() {
        val row = laundryRows(listOf(DashLaundry(id = "l9", status = "pending", guestName = "Ada", roomNumber = "5"))).single()
        assertEquals(
            TaskDefaults(
                type = TaskType.LAUNDRY,
                title = "Handle laundry request — Ada",
                relatedCollection = "laundry_requests",
                relatedId = "l9",
                relatedLabel = "Room 5"
            ),
            row.defaults
        )
    }

    @Test fun laundryTitleFallsBackToRoomThenGuestAndLabelIsOnlyTheRoom() {
        val byRoom = laundryRows(listOf(DashLaundry(id = "a", roomNumber = "7"))).single()
        assertEquals("Handle laundry request — Room 7", byRoom.defaults.title)
        assertEquals("Room 7", byRoom.defaults.relatedLabel)
        val bare = laundryRows(listOf(DashLaundry(id = "b"))).single()
        assertEquals("Handle laundry request — Guest", bare.defaults.title)
        assertNull(bare.defaults.relatedLabel)
        assertNull(bare.badge)
    }

    @Test fun laundryRowsAreCappedAtSix() {
        assertEquals(6, laundryRows((1..9).map { DashLaundry(id = "l$it", status = "pending") }).size)
    }

    // ---- Staff On Duty ----

    private fun staff(n: Int) = (1..n).map { DashAttendance(id = "a$it", staffId = "s$it", staffName = "Staff $it", status = "present") }

    @Test fun staffChipsShowTheFirstTenThenTheRemainingCount() {
        val chips = staffChips(staff(13))
        assertEquals((1..10).map { "Staff $it" }, chips.names)
        assertEquals(3, chips.more)
        assertEquals("+3 more", moreChipText(chips.more))
    }

    @Test fun noMoreChipForTenOrFewerPeople() {
        assertEquals(0, staffChips(staff(10)).more)
        assertEquals(10, staffChips(staff(10)).names.size)
        assertEquals(0, staffChips(staff(2)).more)
        assertTrue(staffChips(emptyList()).names.isEmpty())
    }

    @Test fun aBlankStaffNameStillGetsAChip() {
        val chips = staffChips(listOf(DashAttendance(id = "a", staffId = "s", staffName = " ", status = "present")))
        assertEquals(listOf("Staff"), chips.names)
    }

    // ---- header ----

    @Test fun subtitleIsInTheBusinessZone() {
        assertEquals("Welcome, Ada · Monday, October 12", operationsSubtitle("Ada", DASH_NOW, DASH_LAGOS))
        val late = java.time.Instant.parse("2026-10-11T23:30:00Z")
        assertEquals("Welcome, Ada · Monday, October 12", operationsSubtitle("Ada", late, DASH_LAGOS))
    }
}
