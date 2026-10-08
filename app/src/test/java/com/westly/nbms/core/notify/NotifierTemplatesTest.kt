package com.westly.nbms.core.notify

import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what a wrapper asked [Notifier.notify] to create. */
private class FakeNotifier(override val currencySymbol: String = "₦") : Notifier {
    data class Call(
        val type: String, val title: String, val message: String, val severity: String, val link: String?,
        val forRoles: List<Role>, val forUserIds: List<String>, val excludeActor: Boolean
    )

    var last: Call? = null
    var count = 0

    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        count++
        last = Call(type, title, message, severity, link, forRoles, forUserIds, excludeActor)
    }
}

private val MGMT_ROLES = listOf(Role.SUPER_ADMIN, Role.MANAGER)
private val FRONT = MGMT_ROLES + Role.RECEPTIONIST

class NotifierTemplatesTest {

    private suspend fun capture(block: suspend Notifier.() -> Unit): FakeNotifier.Call {
        val n = FakeNotifier()
        n.block()
        assertEquals(1, n.count)
        return n.last!!
    }

    // ── role groups ──

    @Test fun roleGroupsAreComposedExactly() {
        assertEquals(MGMT_ROLES, RoleGroups.MGMT)
        assertEquals(FRONT, RoleGroups.FRONT_DESK)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT, RoleGroups.FINANCE)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, RoleGroups.OPS)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, RoleGroups.GYM_OVERSIGHT)
    }

    @Test fun withRolesAddsExtrasWithoutDuplicates() {
        assertEquals(FRONT + Role.HOUSEKEEPING, RoleGroups.withRoles(RoleGroups.FRONT_DESK, Role.HOUSEKEEPING))
        assertEquals(FRONT, RoleGroups.withRoles(RoleGroups.FRONT_DESK, Role.RECEPTIONIST, Role.MANAGER))
        assertEquals(1, withRoles(RoleGroups.MGMT, Role.GYM_STAFF, Role.GYM_STAFF).count { it == Role.GYM_STAFF })
    }

    // ── severity ──

    @Test fun severityMapping() {
        assertEquals(NotificationSeverity.INFO, NotificationSeverity.fromKey(null))
        assertEquals(NotificationSeverity.INFO, NotificationSeverity.fromKey("nonsense"))
        assertEquals(NotificationSeverity.SUCCESS, NotificationSeverity.fromKey("success"))
        assertEquals(NotificationSeverity.WARNING, NotificationSeverity.fromKey("warning"))
        assertEquals(NotificationSeverity.CRITICAL, NotificationSeverity.fromKey("critical"))
        assertEquals(NotificationSeverity.INFO, NotificationSeverity.fromKey("info"))
    }

    @Test fun prioritySeverityIsWarningOnlyForUrgentAndHigh() {
        assertEquals("warning", prioritySeverity("urgent"))
        assertEquals("warning", prioritySeverity("high"))
        assertEquals("info", prioritySeverity("normal"))
        assertEquals("info", prioritySeverity("low"))
    }

    // ── helpers ──

    @Test fun roomListShowsAllUpToSix() {
        assertEquals("101", roomList(listOf("101")))
        assertEquals("101, 102, 103, 104, 105, 106", roomList(listOf("101", "102", "103", "104", "105", "106")))
    }

    @Test fun roomListTruncatesAfterSix() {
        val rooms = (101..109).map { it.toString() }
        assertEquals("101, 102, 103, 104, 105, 106 +3 more", roomList(rooms))
        assertEquals("101, 102, 103, 104, 105, 106 +1 more", roomList((101..107).map { it.toString() }))
    }

    @Test fun contactMessageTruncatesAt140() {
        val exact = "a".repeat(140)
        assertEquals(exact, truncateContactMessage(exact))
        assertEquals("a".repeat(140) + "…", truncateContactMessage("a".repeat(141)))
        assertEquals("short", truncateContactMessage("short"))
    }

    @Test fun optionalDashPartIsOmittedWhenBlank() {
        assertEquals("", dashSuffix(null))
        assertEquals("", dashSuffix(""))
        assertEquals("", dashSuffix("   "))
        assertEquals(" — late fee", dashSuffix(" late fee "))
    }

    @Test fun quantityTextDropsWholeDecimals() {
        assertEquals("3", quantityText(3))
        assertEquals("3", quantityText(3.0))
        assertEquals("2.5", quantityText(2.5))
    }

    // ── bookings ──

    @Test fun newBooking() = runTest {
        val c = capture { notifyNewBooking("Ada", "Deluxe Room", "1 Mar 2026", "3 Mar 2026") }
        assertEquals(NotificationType.NEW_BOOKING, c.type)
        assertEquals("New Booking Received", c.title)
        assertEquals("Ada booked a Deluxe Room from 1 Mar 2026 to 3 Mar 2026.", c.message)
        assertEquals("success", c.severity)
        assertEquals("/admin/bookings", c.link)
        assertEquals(FRONT, c.forRoles)
    }

    @Test fun bookingCancelled() = runTest {
        val c = capture { notifyBookingCancelled("Ada", "Deluxe Room", "Tolu") }
        assertEquals(NotificationType.BOOKING_CANCELLED, c.type)
        assertEquals("Booking Cancelled", c.title)
        assertEquals("Ada's Deluxe Room booking was cancelled by Tolu.", c.message)
        assertEquals("warning", c.severity)
        assertEquals(FRONT, c.forRoles)
    }

    @Test fun bookingModified() = runTest {
        val c = capture { notifyBookingModified("Ada", "new dates", "Tolu") }
        assertEquals(NotificationType.BOOKING_MODIFIED, c.type)
        assertEquals("Booking Modified", c.title)
        assertEquals("Ada's booking was updated (new dates) by Tolu.", c.message)
        assertEquals("info", c.severity)
        assertEquals("/admin/bookings", c.link)
        assertEquals(FRONT, c.forRoles)
    }

    @Test fun stayExtended() = runTest {
        val c = capture { notifyStayExtended("Ada", "204", "9 Mar 2026", "Tolu") }
        assertEquals(NotificationType.STAY_EXTENDED, c.type)
        assertEquals("Guest Stay Extended", c.title)
        assertEquals("Ada's stay in Room 204 was extended to 9 Mar 2026 by Tolu.", c.message)
        assertEquals(FRONT + Role.OPERATIONS_MANAGER, c.forRoles)
        assertEquals("/admin/bookings", c.link)
    }

    @Test fun bookingApprovedAndRejected() = runTest {
        val a = capture { notifyBookingApproval("Ada", true, "Tolu") }
        assertEquals(NotificationType.BOOKING_APPROVED, a.type)
        assertEquals("Booking Approved", a.title)
        assertEquals("Ada's room reservation was approved by Tolu.", a.message)
        assertEquals("success", a.severity)
        assertEquals("/admin/room-reservations", a.link)
        val r = capture { notifyBookingApproval("Ada", false, "Tolu") }
        assertEquals(NotificationType.BOOKING_REJECTED, r.type)
        assertEquals("Booking Rejected", r.title)
        assertEquals("Ada's room reservation was rejected by Tolu.", r.message)
        assertEquals("warning", r.severity)
        assertEquals(FRONT, r.forRoles)
    }

    // ── front desk ──

    @Test fun checkInAndOut() = runTest {
        val i = capture { notifyCheckIn("Ada", "204", "Tolu") }
        assertEquals(NotificationType.CHECK_IN, i.type)
        assertEquals("Guest Checked In", i.title)
        assertEquals("Ada checked into Room 204 (by Tolu).", i.message)
        assertEquals("success", i.severity)
        assertEquals("/admin/checkin", i.link)
        assertEquals(FRONT + Role.OPERATIONS_MANAGER, i.forRoles)
        val o = capture { notifyCheckOut("Ada", "204", "Tolu") }
        assertEquals(NotificationType.CHECK_OUT, o.type)
        assertEquals("Guest Checked Out", o.title)
        assertEquals("Ada checked out of Room 204 (by Tolu).", o.message)
        assertEquals("info", o.severity)
        assertEquals("/admin/checkout", o.link)
        assertEquals(FRONT + Role.OPERATIONS_MANAGER, o.forRoles)
    }

    @Test fun walkIn() = runTest {
        val c = capture { notifyWalkIn("Ada", "204") }
        assertEquals(NotificationType.WALK_IN, c.type)
        assertEquals("New Walk-In", c.title)
        assertEquals("Ada checked into Room 204", c.message)
        assertEquals("info", c.severity)
        assertNull(c.link)
        assertEquals(MGMT_ROLES, c.forRoles)
    }

    // ── money ──

    @Test fun newSaleUsesBusinessCurrencyAndDefaultLink() = runTest {
        val n = FakeNotifier("$")
        n.notifyNewSale("Tolu", 12500.0, "Bar")
        val c = n.last!!
        assertEquals(NotificationType.NEW_SALE, c.type)
        assertEquals("New Sale Recorded", c.title)
        assertEquals("Tolu recorded a $12,500 sale in Bar.", c.message)
        assertEquals("success", c.severity)
        assertEquals("/admin/sales/history", c.link)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT, c.forRoles)
        n.notifyNewSale("Tolu", 100.5, "Bar", link = "/admin/bar/sales-history")
        assertEquals("/admin/bar/sales-history", n.last!!.link)
        assertEquals("Tolu recorded a $100.50 sale in Bar.", n.last!!.message)
    }

    @Test fun paymentReceived() = runTest {
        val c = capture { notifyPaymentReceived(45000.0, "Transfer", "Ada", "Tolu") }
        assertEquals(NotificationType.PAYMENT_RECEIVED, c.type)
        assertEquals("Payment Received", c.title)
        assertEquals("₦45,000 (Transfer) received from Ada, recorded by Tolu.", c.message)
        assertEquals("success", c.severity)
        assertEquals("/admin/payments", c.link)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT + Role.RECEPTIONIST, c.forRoles)
    }

    @Test fun paymentApproved() = runTest {
        val c = capture { notifyPaymentApproved(45000.0, "Ada", "Tolu") }
        assertEquals(NotificationType.PAYMENT_APPROVED, c.type)
        assertEquals("Payment Approved", c.title)
        assertEquals("₦45,000 payment for Ada was approved by Tolu.", c.message)
        assertEquals("success", c.severity)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT, c.forRoles)
    }

    @Test fun refundWithAndWithoutReason() = runTest {
        val with = capture { notifyRefundIssued(5000.0, "Ada", "Tolu", "Double charge") }
        assertEquals(NotificationType.REFUND_ISSUED, with.type)
        assertEquals("Refund Issued", with.title)
        assertEquals("₦5,000 refunded to Ada by Tolu — Double charge.", with.message)
        assertEquals("warning", with.severity)
        assertEquals("/admin/payments", with.link)
        val without = capture { notifyRefundIssued(5000.0, "Ada", "Tolu", null) }
        assertEquals("₦5,000 refunded to Ada by Tolu.", without.message)
        val blank = capture { notifyRefundIssued(5000.0, "Ada", "Tolu", "  ") }
        assertEquals("₦5,000 refunded to Ada by Tolu.", blank.message)
    }

    @Test fun largeExpense() = runTest {
        val c = capture { notifyLargeExpense("Generator fuel", 250000.0, "Tolu") }
        assertEquals(NotificationType.EXPENSE_RECORDED, c.type)
        assertEquals("Large Expense Recorded", c.title)
        assertEquals("Generator fuel: ₦250,000 — recorded by Tolu.", c.message)
        assertEquals("warning", c.severity)
        assertEquals("/admin/expenses", c.link)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT, c.forRoles)
    }

    // ── lost & found ──

    @Test fun lostFound() = runTest {
        val i = capture { notifyLostFoundItem("Black wallet", "204", "Tolu") }
        assertEquals(NotificationType.LOST_FOUND_ITEM, i.type)
        assertEquals("Lost & Found Item Logged", i.title)
        assertEquals("Black wallet found in Room 204 by Tolu.", i.message)
        assertEquals("info", i.severity)
        assertEquals("/admin/lost-found", i.link)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER + Role.HOUSEKEEPING, i.forRoles)
        val c = capture { notifyLostFoundClaimed("Black wallet", "Ada", "Tolu") }
        assertEquals(NotificationType.LOST_FOUND_CLAIMED, c.type)
        assertEquals("Lost & Found Item Claimed", c.title)
        assertEquals("Black wallet was returned to Ada (by Tolu).", c.message)
        assertEquals("success", c.severity)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, c.forRoles)
    }

    // ── housekeeping ──

    @Test fun housekeepingTaskAndDone() = runTest {
        val t = capture { notifyHousekeepingTask("Deep clean", "204", "Tolu") }
        assertEquals(NotificationType.HOUSEKEEPING_TASK, t.type)
        assertEquals("New Housekeeping Task", t.title)
        assertEquals("Deep clean requested for Room 204 by Tolu.", t.message)
        assertEquals("info", t.severity)
        assertEquals("/admin/housekeeping", t.link)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER + Role.HOUSEKEEPING, t.forRoles)
        val d = capture { notifyHousekeepingDone("204", "Tolu") }
        assertEquals(NotificationType.HOUSEKEEPING_TASK_DONE, d.type)
        assertEquals("Housekeeping Task Completed", d.title)
        assertEquals("Room 204 was cleaned/serviced by Tolu.", d.message)
        assertEquals("success", d.severity)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, d.forRoles)
    }

    @Test fun roomsAssignedWithEndDate() = runTest {
        val c = capture { notifyRoomsAssigned("Tolu", "hk1", "Bisi", listOf("101", "102"), "1 Mar 2026", "7 Mar 2026") }
        assertEquals(NotificationType.ROOM_ASSIGNED, c.type)
        assertEquals("Rooms Assigned to You", c.title)
        assertEquals("Tolu assigned you Room(s) 101, 102 (1 Mar 2026 – 7 Mar 2026).", c.message)
        assertEquals("info", c.severity)
        assertEquals("/admin/housekeeping", c.link)
        assertEquals(listOf("hk1"), c.forUserIds)
        assertTrue(c.forRoles.isEmpty())
    }

    @Test fun roomsAssignedOngoingAndTruncated() = runTest {
        val rooms = (101..108).map { it.toString() }
        val c = capture { notifyRoomsAssigned("Tolu", "hk1", "Bisi", rooms, "1 Mar 2026", null) }
        assertEquals(
            "Tolu assigned you Room(s) 101, 102, 103, 104, 105, 106 +2 more (starting 1 Mar 2026, ongoing).",
            c.message
        )
        val blank = capture { notifyRoomsAssigned("Tolu", "hk1", "Bisi", listOf("101"), "1 Mar 2026", "") }
        assertEquals("Tolu assigned you Room(s) 101 (starting 1 Mar 2026, ongoing).", blank.message)
    }

    @Test fun roomsReassignedAndEnded() = runTest {
        val r = capture { notifyRoomsReassigned("hk1", listOf("101", "102"), "Chidi", "Tolu") }
        assertEquals(NotificationType.ROOM_REASSIGNED, r.type)
        assertEquals("Rooms Reassigned", r.title)
        assertEquals("Room(s) 101, 102 moved from you to Chidi, by Tolu.", r.message)
        assertEquals(listOf("hk1"), r.forUserIds)
        val e = capture { notifyRoomAssignmentEnded("hk1", (1..7).map { it.toString() }, "Tolu") }
        assertEquals(NotificationType.ROOM_ASSIGNMENT_ENDED, e.type)
        assertEquals("Room Assignment Ended", e.title)
        assertEquals("Your assignment for Room(s) 1, 2, 3, 4, 5, 6 +1 more was ended by Tolu.", e.message)
        assertEquals("info", e.severity)
        assertEquals(listOf("hk1"), e.forUserIds)
    }

    @Test fun housekeepingTaskQueued() = runTest {
        val urgent = capture { notifyHousekeepingTaskQueued("hk1", "204", "Tolu", "urgent", "Guest arriving at 2pm") }
        assertEquals(NotificationType.HOUSEKEEPING_TASK_SCHEDULED, urgent.type)
        assertEquals("New Cleaning Task", urgent.title)
        assertEquals("Room 204 added to your queue by Tolu (urgent priority) — Guest arriving at 2pm.", urgent.message)
        assertEquals("warning", urgent.severity)
        assertEquals(listOf("hk1"), urgent.forUserIds)
        val normal = capture { notifyHousekeepingTaskQueued("hk1", "204", "Tolu", "normal", null) }
        assertEquals("Room 204 added to your queue by Tolu (normal priority).", normal.message)
        assertEquals("info", normal.severity)
        assertEquals("warning", capture { notifyHousekeepingTaskQueued("hk1", "204", "Tolu", "high", null) }.severity)
    }

    @Test fun roomStatusChange() = runTest {
        val c = capture { notifyRoomStatusChange("204", "cleaning", "Tolu") }
        assertEquals(NotificationType.ROOM_STATUS_CHANGE, c.type)
        assertEquals("Room Status Changed", c.title)
        assertEquals("Room 204 marked \"cleaning\" by Tolu.", c.message)
        assertEquals("info", c.severity)
        assertEquals("/admin/rooms", c.link)
        assertEquals(FRONT + Role.HOUSEKEEPING + Role.OPERATIONS_MANAGER, c.forRoles)
        assertEquals("warning", capture { notifyRoomStatusChange("204", "out_of_order", "Tolu") }.severity)
    }

    // ── maintenance ──

    @Test fun maintenance() = runTest {
        val r = capture { notifyMaintenanceRequest("Leaking tap", "Room 204", "Tolu") }
        assertEquals(NotificationType.MAINTENANCE_REQUEST, r.type)
        assertEquals("New Maintenance Request", r.title)
        assertEquals("Leaking tap reported for Room 204 by Tolu.", r.message)
        assertEquals("warning", r.severity)
        assertEquals("/admin/maintenance", r.link)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, r.forRoles)
        val d = capture { notifyMaintenanceResolved("Room 204", "Chidi") }
        assertEquals(NotificationType.MAINTENANCE_RESOLVED, d.type)
        assertEquals("Maintenance Request Resolved", d.title)
        assertEquals("Maintenance issue at Room 204 resolved by Chidi.", d.message)
        assertEquals("success", d.severity)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, d.forRoles)
    }

    // ── laundry ──

    @Test fun laundry() = runTest {
        val r = capture { notifyNewLaundryRequest("Tolu", 5, "Room 204") }
        assertEquals(NotificationType.LAUNDRY_REQUEST, r.type)
        assertEquals("New Laundry Request", r.title)
        assertEquals("Tolu logged a laundry request (5 item(s)) for Room 204.", r.message)
        assertEquals("info", r.severity)
        assertEquals("/admin/laundry", r.link)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER + Role.LAUNDRY_VALET, r.forRoles)
        val d = capture { notifyLaundryReady("Room 204", "Tolu") }
        assertEquals(NotificationType.LAUNDRY_READY, d.type)
        assertEquals("Laundry Ready for Collection", d.title)
        assertEquals("Laundry for Room 204 is ready for collection/delivery (Tolu).", d.message)
        assertEquals("success", d.severity)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER + Role.RECEPTIONIST, d.forRoles)
    }

    // ── tasks ──

    @Test fun taskAssigned() = runTest {
        val c = capture { notifyTaskAssigned("Tolu", "Restock minibars", "high", listOf("u1", "u2")) }
        assertEquals(NotificationType.TASK_ASSIGNED, c.type)
        assertEquals("New Task Assigned", c.title)
        assertEquals("Tolu assigned you: \"Restock minibars\" (high priority).", c.message)
        assertEquals("warning", c.severity)
        assertEquals("/admin/my-tasks", c.link)
        assertEquals(listOf("u1", "u2"), c.forUserIds)
        assertTrue(c.forRoles.isEmpty())
        assertEquals("info", capture { notifyTaskAssigned("Tolu", "x", "medium", listOf("u1")) }.severity)
    }

    @Test fun taskReassigned() = runTest {
        val c = capture { notifyTaskReassigned("Tolu", "Restock minibars", listOf("u1")) }
        assertEquals(NotificationType.TASK_REASSIGNED, c.type)
        assertEquals("Task Reassigned to You", c.title)
        assertEquals("Tolu reassigned \"Restock minibars\" to you.", c.message)
        assertEquals("info", c.severity)
        assertEquals("/admin/my-tasks", c.link)
        assertEquals(listOf("u1"), c.forUserIds)
    }

    @Test fun taskCompletedTargetsAssignerWhenKnown() = runTest {
        val with = capture { notifyTaskCompleted("Chidi", "Restock minibars", "u9") }
        assertEquals(NotificationType.TASK_COMPLETED, with.type)
        assertEquals("Task Completed", with.title)
        assertEquals("Chidi completed: \"Restock minibars\".", with.message)
        assertEquals("success", with.severity)
        assertEquals("/admin/tasks", with.link)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, with.forRoles)
        assertEquals(listOf("u9"), with.forUserIds)
        val without = capture { notifyTaskCompleted("Chidi", "Restock minibars", null) }
        assertTrue(without.forUserIds.isEmpty())
    }

    // ── shifts ──

    @Test fun shifts() = runTest {
        val a = capture { notifyShiftAssigned("Tolu", "Morning", "2 Mar 2026", "08:00", "16:00", listOf("u1")) }
        assertEquals(NotificationType.SHIFT_ASSIGNED, a.type)
        assertEquals("New Shift Scheduled", a.title)
        assertEquals("Tolu scheduled you for \"Morning\" on 2 Mar 2026, 08:00–16:00.", a.message)
        assertEquals("info", a.severity)
        assertEquals("/admin/my-tasks", a.link)
        assertEquals(listOf("u1"), a.forUserIds)
        val u = capture { notifyShiftUpdated("Tolu", "Morning", "2 Mar 2026", listOf("u1", "u2")) }
        assertEquals(NotificationType.SHIFT_UPDATED, u.type)
        assertEquals("Shift Updated", u.title)
        assertEquals("Tolu updated your \"Morning\" shift on 2 Mar 2026.", u.message)
        assertEquals("info", u.severity)
        assertEquals(listOf("u1", "u2"), u.forUserIds)
        val c = capture { notifyShiftCancelled("Tolu", "Morning", "2 Mar 2026", listOf("u1")) }
        assertEquals(NotificationType.SHIFT_CANCELLED, c.type)
        assertEquals("Shift Cancelled", c.title)
        assertEquals("Tolu cancelled your \"Morning\" shift on 2 Mar 2026.", c.message)
        assertEquals("warning", c.severity)
    }

    // ── inventory, reviews, website ──

    @Test fun lowInventory() = runTest {
        val c = capture { notifyLowInventory("Towels", 4) }
        assertEquals(NotificationType.LOW_INVENTORY, c.type)
        assertEquals("Low Inventory Alert", c.title)
        assertEquals("Towels is low: only 4 units remaining.", c.message)
        assertEquals("warning", c.severity)
        assertEquals("/admin/inventory", c.link)
        assertEquals(MGMT_ROLES + Role.ACCOUNTANT, c.forRoles)
        assertEquals("Rice is low: only 2.5 kg remaining.", capture { notifyLowInventory("Rice", 2.5, "kg") }.message)
    }

    @Test fun newReviewWithAndWithoutRating() = runTest {
        val with = capture { notifyNewReview("Ada", 5) }
        assertEquals(NotificationType.NEW_REVIEW, with.type)
        assertEquals("New Guest Review Awaiting Approval", with.title)
        assertEquals("Ada left a 5-star review — review it on the Guest Reviews page.", with.message)
        assertEquals("info", with.severity)
        assertEquals("/admin/reviews", with.link)
        assertEquals(MGMT_ROLES, with.forRoles)
        val without = capture { notifyNewReview("Ada", null) }
        assertEquals("Ada left a review — review it on the Guest Reviews page.", without.message)
    }

    @Test fun contactMessageShortAndLong() = runTest {
        val short = capture { notifyContactMessage("Ada", "ada@example.com", "Booking", "Do you have rooms?") }
        assertEquals(NotificationType.CONTACT_MESSAGE, short.type)
        assertEquals("New Contact Inquiry", short.title)
        assertEquals("From: Ada <ada@example.com>\nSubject: Booking\n\nDo you have rooms?", short.message)
        assertEquals("info", short.severity)
        assertEquals("/admin/messages", short.link)
        assertEquals(FRONT, short.forRoles)
        val long = capture { notifyContactMessage("Ada", "ada@example.com", "Booking", "b".repeat(200)) }
        assertEquals("From: Ada <ada@example.com>\nSubject: Booking\n\n" + "b".repeat(140) + "…", long.message)
    }

    // ── gym ──

    @Test fun gymMembershipWrappers() = runTest {
        val oversight = MGMT_ROLES + Role.OPERATIONS_MANAGER
        val reg = capture { notifyGymMembershipRegistered("Ngozi", "Monthly", "Tolu") }
        assertEquals(NotificationType.GYM_MEMBERSHIP_REGISTERED, reg.type)
        assertEquals("New Gym Membership", reg.title)
        assertEquals("Ngozi registered for Monthly by Tolu.", reg.message)
        assertEquals("info", reg.severity)
        assertEquals("/admin/gym/members", reg.link)
        assertEquals(oversight, reg.forRoles)
        assertTrue(reg.excludeActor)
        val ren = capture { notifyGymMembershipRenewed("Ngozi", "Monthly", "Tolu") }
        assertEquals(NotificationType.GYM_MEMBERSHIP_RENEWED, ren.type)
        assertEquals("Gym Membership Renewed", ren.title)
        assertEquals("Ngozi's Monthly membership was renewed by Tolu.", ren.message)
        assertEquals(oversight, ren.forRoles)
        assertTrue(ren.excludeActor)
        val exp = capture { notifyGymMembershipExpiring("Ngozi", 3) }
        assertEquals(NotificationType.GYM_MEMBERSHIP_EXPIRING, exp.type)
        assertEquals("Membership Expiring Soon", exp.title)
        assertEquals("Ngozi's gym membership expires in 3 day(s).", exp.message)
        assertEquals("warning", exp.severity)
        assertEquals(oversight + Role.GYM_STAFF, exp.forRoles)
        assertFalse(exp.excludeActor)
    }

    @Test fun gymSuspendedWithAndWithoutReason() = runTest {
        val with = capture { notifyGymMembershipSuspended("Ngozi", "Tolu", "Unpaid fees") }
        assertEquals(NotificationType.GYM_MEMBERSHIP_SUSPENDED, with.type)
        assertEquals("Gym Membership Suspended", with.title)
        assertEquals("Ngozi's membership was suspended by Tolu — Unpaid fees.", with.message)
        assertEquals("warning", with.severity)
        assertEquals(MGMT_ROLES + Role.OPERATIONS_MANAGER, with.forRoles)
        assertTrue(with.excludeActor)
        assertEquals("Ngozi's membership was suspended by Tolu.", capture { notifyGymMembershipSuspended("Ngozi", "Tolu", null) }.message)
    }

    @Test fun gymCheckInAndOut() = runTest {
        val i = capture { notifyGymCheckIn("Ngozi", "Tolu") }
        assertEquals(NotificationType.GYM_CHECK_IN, i.type)
        assertEquals("Gym Check-In", i.title)
        assertEquals("Ngozi checked in (by Tolu).", i.message)
        assertEquals("info", i.severity)
        assertEquals("/admin/gym/checkin", i.link)
        assertEquals(listOf(Role.GYM_STAFF), i.forRoles)
        assertTrue(i.excludeActor)
        val o = capture { notifyGymCheckOut("Ngozi", "Tolu") }
        assertEquals(NotificationType.GYM_CHECK_OUT, o.type)
        assertEquals("Gym Check-Out", o.title)
        assertEquals("Ngozi checked out (by Tolu).", o.message)
        assertEquals(listOf(Role.GYM_STAFF), o.forRoles)
        assertTrue(o.excludeActor)
    }

    // ── alerts ──

    @Test fun staffAndSystemAlerts() = runTest {
        val s = capture { notifyStaffAlert("Odd login", "Someone signed in at 3am") }
        assertEquals(NotificationType.STAFF_ALERT, s.type)
        assertEquals("Odd login", s.title)
        assertEquals("Someone signed in at 3am", s.message)
        assertEquals("warning", s.severity)
        assertEquals("/admin/audit-log", s.link)
        assertEquals(MGMT_ROLES, s.forRoles)
        assertEquals("critical", capture { notifyStaffAlert("a", "b", "critical") }.severity)
        val y = capture { notifySystemAlert("Backup failed", "Check the server") }
        assertEquals(NotificationType.SYSTEM_ALERT, y.type)
        assertEquals("Backup failed", y.title)
        assertEquals("Check the server", y.message)
        assertEquals("critical", y.severity)
        assertEquals("/admin/dashboard", y.link)
        assertEquals(listOf(Role.SUPER_ADMIN), y.forRoles)
    }

    // ── Record Deleted alert text ──

    @Test fun recordDeletedMessage() {
        assertEquals("Tolu deleted a booking dates record.", recordDeletedMessage("Tolu", "booking_dates", null))
        assertEquals("Tolu deleted a guests record — Duplicate entry.", recordDeletedMessage("Tolu", "guests", "Duplicate entry"))
        assertEquals("Tolu deleted a lost found record.", recordDeletedMessage("Tolu", "lost_found", "  "))
    }

    // ── what gets written / sent ──

    @Test fun notificationFieldsHaveTheAgreedShape() {
        val f = notificationFields(
            "new_booking", "T", "M", "success", "/admin/bookings",
            listOf(Role.MANAGER, Role.RECEPTIONIST), listOf("u1"), excludeActor = true, actorUid = "me"
        )
        assertEquals(
            setOf("type", "title", "message", "severity", "link", "forRoles", "forUserIds", "excludeUserId", "actorId", "readBy", "deletedBy"),
            f.keys
        )
        assertEquals(listOf("manager", "receptionist"), f["forRoles"])
        assertEquals("me", f["excludeUserId"])
        assertEquals("me", f["actorId"])
        assertEquals(emptyList<String>(), f["readBy"])
        assertEquals(emptyList<String>(), f["deletedBy"])
        val noExclude = notificationFields("t", "T", "M", "info", null, emptyList(), emptyList(), false, "me")
        assertNull(noExclude["excludeUserId"])
        assertNull(noExclude["link"])
    }

    @Test fun pushBodyDefaultsLinkToDashboard() {
        val body = pushRequestBody(listOf(Role.MANAGER), listOf("u1"), null, "T", "B", null, "n1")
        assertEquals("\"/admin/dashboard\"", body["link"].toString())
        assertEquals("\"n1\"", body["notificationId"].toString())
        assertEquals("[\"manager\"]", body["forRoles"].toString())
        assertEquals("null", body["excludeUserId"].toString())
        assertEquals("\"B\"", body["body"].toString())
    }
}
