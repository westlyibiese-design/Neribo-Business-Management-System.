package com.westly.nbms.core.notify

import com.westly.nbms.core.notify.RoleGroups.FINANCE
import com.westly.nbms.core.notify.RoleGroups.FRONT_DESK
import com.westly.nbms.core.notify.RoleGroups.GYM_OVERSIGHT
import com.westly.nbms.core.notify.RoleGroups.MGMT
import com.westly.nbms.core.notify.RoleGroups.OPS
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.util.Format

/** Most rooms listed by name in one message; more are shown as "+N more". */
internal const val MAX_ROOMS_IN_MESSAGE = 6

/** Longest part of a contact-message body shown in a notification. */
internal const val CONTACT_MESSAGE_LIMIT = 140

/** "101, 102, 103 +2 more": all rooms when there are 6 or fewer, otherwise the first 6 and " +N more". */
internal fun roomList(roomNumbers: List<String>): String {
    if (roomNumbers.size <= MAX_ROOMS_IN_MESSAGE) return roomNumbers.joinToString(", ")
    val shown = roomNumbers.take(MAX_ROOMS_IN_MESSAGE).joinToString(", ")
    return "$shown +${roomNumbers.size - MAX_ROOMS_IN_MESSAGE} more"
}

/** " — {text}" when [text] has content, otherwise "" (the optional reason / instructions part). */
internal fun dashSuffix(text: String?): String {
    val clean = text?.trim().orEmpty()
    return if (clean.isEmpty()) "" else " — $clean"
}

/** First [CONTACT_MESSAGE_LIMIT] characters of the message, with "…" added only when it was cut. */
internal fun truncateContactMessage(message: String): String =
    if (message.length > CONTACT_MESSAGE_LIMIT) message.take(CONTACT_MESSAGE_LIMIT) + "…" else message

/** Warning for urgent / high priority tasks, otherwise info. */
internal fun prioritySeverity(priority: String): String =
    if (priority == "urgent" || priority == "high") NotificationSeverity.WARNING.key else NotificationSeverity.INFO.key

/** A whole number prints without decimals ("3"), anything else as is ("2.5"). */
internal fun quantityText(qty: Number): String {
    val d = qty.toDouble()
    return if (d % 1.0 == 0.0 && !d.isInfinite()) d.toLong().toString() else qty.toString()
}

/** The text of the "Record Deleted" alert sent when a record is soft-deleted. */
internal fun recordDeletedMessage(userName: String, collection: String, reason: String?): String =
    "$userName deleted a ${collection.replace('_', ' ')} record${dashSuffix(reason)}."

/**
 * Creates notifications. Every wrapper below builds the exact Westly text and calls [notify].
 * Only [notify] is abstract; the wrappers are ready-made, so a test fake needs just one function.
 * All failures are swallowed by the real implementation: a notification problem never breaks the feature that triggered it.
 */
interface Notifier {

    /** Currency symbol of the signed-in business, used by wrappers that show money. */
    val currencySymbol: String get() = "₦"

    suspend fun notify(
        type: String,
        title: String,
        message: String,
        severity: String = "info",
        link: String? = null,
        forRoles: List<Role> = emptyList(),
        forUserIds: List<String> = emptyList(),
        excludeActor: Boolean = false
    )

    // ── bookings ──

    suspend fun notifyNewBooking(guestName: String, roomType: String, checkIn: String, checkOut: String) = notify(
        NotificationType.NEW_BOOKING, "New Booking Received",
        "$guestName booked a $roomType from $checkIn to $checkOut.",
        "success", "/admin/bookings", forRoles = FRONT_DESK
    )

    suspend fun notifyBookingCancelled(guestName: String, roomType: String, cancelledBy: String) = notify(
        NotificationType.BOOKING_CANCELLED, "Booking Cancelled",
        "$guestName's $roomType booking was cancelled by $cancelledBy.",
        "warning", "/admin/bookings", forRoles = FRONT_DESK
    )

    suspend fun notifyBookingModified(guestName: String, summary: String, modifiedBy: String) = notify(
        NotificationType.BOOKING_MODIFIED, "Booking Modified",
        "$guestName's booking was updated ($summary) by $modifiedBy.",
        "info", "/admin/bookings", forRoles = FRONT_DESK
    )

    suspend fun notifyStayExtended(guest: String, roomNumber: String, newCheckOut: String, extendedBy: String) = notify(
        NotificationType.STAY_EXTENDED, "Guest Stay Extended",
        "$guest's stay in Room $roomNumber was extended to $newCheckOut by $extendedBy.",
        "info", "/admin/bookings", forRoles = withRoles(FRONT_DESK, Role.OPERATIONS_MANAGER)
    )

    suspend fun notifyBookingApproval(guest: String, approved: Boolean, decidedBy: String) = notify(
        if (approved) NotificationType.BOOKING_APPROVED else NotificationType.BOOKING_REJECTED,
        if (approved) "Booking Approved" else "Booking Rejected",
        "$guest's room reservation was ${if (approved) "approved" else "rejected"} by $decidedBy.",
        if (approved) "success" else "warning", "/admin/room-reservations", forRoles = FRONT_DESK
    )

    // ── front desk ──

    suspend fun notifyCheckIn(guest: String, room: String, by: String) = notify(
        NotificationType.CHECK_IN, "Guest Checked In",
        "$guest checked into Room $room (by $by).",
        "success", "/admin/checkin", forRoles = withRoles(FRONT_DESK, Role.OPERATIONS_MANAGER)
    )

    suspend fun notifyCheckOut(guest: String, room: String, by: String) = notify(
        NotificationType.CHECK_OUT, "Guest Checked Out",
        "$guest checked out of Room $room (by $by).",
        "info", "/admin/checkout", forRoles = withRoles(FRONT_DESK, Role.OPERATIONS_MANAGER)
    )

    suspend fun notifyWalkIn(guest: String, roomNumber: String) = notify(
        NotificationType.WALK_IN, "New Walk-In",
        "$guest checked into Room $roomNumber",
        "info", null, forRoles = listOf(Role.SUPER_ADMIN, Role.MANAGER)
    )

    // ── money ──

    suspend fun notifyNewSale(staffName: String, amount: Double, category: String, link: String = "/admin/sales/history") = notify(
        NotificationType.NEW_SALE, "New Sale Recorded",
        "$staffName recorded a ${Format.currency(amount, currencySymbol)} sale in $category.",
        "success", link, forRoles = FINANCE
    )

    suspend fun notifyPaymentReceived(amount: Double, method: String, guest: String, by: String) = notify(
        NotificationType.PAYMENT_RECEIVED, "Payment Received",
        "${Format.currency(amount, currencySymbol)} ($method) received from $guest, recorded by $by.",
        "success", "/admin/payments", forRoles = withRoles(FINANCE, Role.RECEPTIONIST)
    )

    suspend fun notifyPaymentApproved(amount: Double, guest: String, by: String) = notify(
        NotificationType.PAYMENT_APPROVED, "Payment Approved",
        "${Format.currency(amount, currencySymbol)} payment for $guest was approved by $by.",
        "success", "/admin/payments", forRoles = FINANCE
    )

    suspend fun notifyRefundIssued(amount: Double, guest: String, by: String, reason: String?) = notify(
        NotificationType.REFUND_ISSUED, "Refund Issued",
        "${Format.currency(amount, currencySymbol)} refunded to $guest by $by${dashSuffix(reason)}.",
        "warning", "/admin/payments", forRoles = FINANCE
    )

    suspend fun notifyLargeExpense(title: String, amount: Double, by: String) = notify(
        NotificationType.EXPENSE_RECORDED, "Large Expense Recorded",
        "$title: ${Format.currency(amount, currencySymbol)} — recorded by $by.",
        "warning", "/admin/expenses", forRoles = FINANCE
    )

    // ── lost & found ──

    suspend fun notifyLostFoundItem(item: String, room: String, by: String) = notify(
        NotificationType.LOST_FOUND_ITEM, "Lost & Found Item Logged",
        "$item found in Room $room by $by.",
        "info", "/admin/lost-found", forRoles = withRoles(OPS, Role.HOUSEKEEPING)
    )

    suspend fun notifyLostFoundClaimed(item: String, guest: String, by: String) = notify(
        NotificationType.LOST_FOUND_CLAIMED, "Lost & Found Item Claimed",
        "$item was returned to $guest (by $by).",
        "success", "/admin/lost-found", forRoles = OPS
    )

    // ── housekeeping ──

    suspend fun notifyHousekeepingTask(taskType: String, room: String, by: String) = notify(
        NotificationType.HOUSEKEEPING_TASK, "New Housekeeping Task",
        "$taskType requested for Room $room by $by.",
        "info", "/admin/housekeeping", forRoles = withRoles(OPS, Role.HOUSEKEEPING)
    )

    suspend fun notifyHousekeepingDone(room: String, by: String) = notify(
        NotificationType.HOUSEKEEPING_TASK_DONE, "Housekeeping Task Completed",
        "Room $room was cleaned/serviced by $by.",
        "success", "/admin/housekeeping", forRoles = OPS
    )

    suspend fun notifyRoomsAssigned(
        assignedBy: String,
        housekeeperId: String,
        housekeeperName: String,
        roomNumbers: List<String>,
        startDate: String,
        endDate: String?
    ) = notify(
        NotificationType.ROOM_ASSIGNED, "Rooms Assigned to You",
        "$assignedBy assigned you Room(s) ${roomList(roomNumbers)} (" +
            (if (endDate.isNullOrBlank()) "starting $startDate, ongoing" else "$startDate – $endDate") + ").",
        "info", "/admin/housekeeping", forUserIds = listOf(housekeeperId)
    )

    suspend fun notifyRoomsReassigned(previousHousekeeperId: String, roomNumbers: List<String>, reassignedTo: String, by: String) = notify(
        NotificationType.ROOM_REASSIGNED, "Rooms Reassigned",
        "Room(s) ${roomList(roomNumbers)} moved from you to $reassignedTo, by $by.",
        "info", "/admin/housekeeping", forUserIds = listOf(previousHousekeeperId)
    )

    suspend fun notifyRoomAssignmentEnded(housekeeperId: String, roomNumbers: List<String>, by: String) = notify(
        NotificationType.ROOM_ASSIGNMENT_ENDED, "Room Assignment Ended",
        "Your assignment for Room(s) ${roomList(roomNumbers)} was ended by $by.",
        "info", "/admin/housekeeping", forUserIds = listOf(housekeeperId)
    )

    suspend fun notifyHousekeepingTaskQueued(assignedToId: String, room: String, by: String, priority: String, instructions: String?) = notify(
        NotificationType.HOUSEKEEPING_TASK_SCHEDULED, "New Cleaning Task",
        "Room $room added to your queue by $by ($priority priority)${dashSuffix(instructions)}.",
        prioritySeverity(priority), "/admin/housekeeping", forUserIds = listOf(assignedToId)
    )

    suspend fun notifyRoomStatusChange(room: String, newStatus: String, by: String) = notify(
        NotificationType.ROOM_STATUS_CHANGE, "Room Status Changed",
        "Room $room marked \"$newStatus\" by $by.",
        if (newStatus == "out_of_order") "warning" else "info", "/admin/rooms",
        forRoles = withRoles(FRONT_DESK, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER)
    )

    // ── maintenance ──

    suspend fun notifyMaintenanceRequest(issue: String, roomOrArea: String, by: String) = notify(
        NotificationType.MAINTENANCE_REQUEST, "New Maintenance Request",
        "$issue reported for $roomOrArea by $by.",
        "warning", "/admin/maintenance", forRoles = OPS
    )

    suspend fun notifyMaintenanceResolved(roomOrArea: String, by: String) = notify(
        NotificationType.MAINTENANCE_RESOLVED, "Maintenance Request Resolved",
        "Maintenance issue at $roomOrArea resolved by $by.",
        "success", "/admin/maintenance", forRoles = OPS
    )

    // ── laundry ──

    suspend fun notifyNewLaundryRequest(by: String, itemCount: Int, guestOrRoom: String) = notify(
        NotificationType.LAUNDRY_REQUEST, "New Laundry Request",
        "$by logged a laundry request ($itemCount item(s)) for $guestOrRoom.",
        "info", "/admin/laundry", forRoles = withRoles(OPS, Role.LAUNDRY_VALET)
    )

    suspend fun notifyLaundryReady(guestOrRoom: String, by: String) = notify(
        NotificationType.LAUNDRY_READY, "Laundry Ready for Collection",
        "Laundry for $guestOrRoom is ready for collection/delivery ($by).",
        "success", "/admin/laundry", forRoles = withRoles(OPS, Role.RECEPTIONIST)
    )

    // ── tasks ──

    suspend fun notifyTaskAssigned(assignedBy: String, taskTitle: String, priority: String, assignedToIds: List<String>) = notify(
        NotificationType.TASK_ASSIGNED, "New Task Assigned",
        "$assignedBy assigned you: \"$taskTitle\" ($priority priority).",
        prioritySeverity(priority), "/admin/my-tasks", forUserIds = assignedToIds
    )

    suspend fun notifyTaskReassigned(by: String, task: String, assignedToIds: List<String>) = notify(
        NotificationType.TASK_REASSIGNED, "Task Reassigned to You",
        "$by reassigned \"$task\" to you.",
        "info", "/admin/my-tasks", forUserIds = assignedToIds
    )

    suspend fun notifyTaskCompleted(completedBy: String, task: String, assignedBy: String?) = notify(
        NotificationType.TASK_COMPLETED, "Task Completed",
        "$completedBy completed: \"$task\".",
        "success", "/admin/tasks", forRoles = OPS,
        forUserIds = if (assignedBy != null) listOf(assignedBy) else emptyList()
    )

    // ── shifts ──

    suspend fun notifyShiftAssigned(by: String, label: String, date: String, startTime: String, endTime: String, staffIds: List<String>) = notify(
        NotificationType.SHIFT_ASSIGNED, "New Shift Scheduled",
        "$by scheduled you for \"$label\" on $date, $startTime–$endTime.",
        "info", "/admin/my-tasks", forUserIds = staffIds
    )

    suspend fun notifyShiftUpdated(by: String, label: String, date: String, staffIds: List<String>) = notify(
        NotificationType.SHIFT_UPDATED, "Shift Updated",
        "$by updated your \"$label\" shift on $date.",
        "info", "/admin/my-tasks", forUserIds = staffIds
    )

    suspend fun notifyShiftCancelled(by: String, label: String, date: String, staffIds: List<String>) = notify(
        NotificationType.SHIFT_CANCELLED, "Shift Cancelled",
        "$by cancelled your \"$label\" shift on $date.",
        "warning", "/admin/my-tasks", forUserIds = staffIds
    )

    // ── inventory, reviews, website ──

    suspend fun notifyLowInventory(item: String, qty: Number, unit: String = "units") = notify(
        NotificationType.LOW_INVENTORY, "Low Inventory Alert",
        "$item is low: only ${quantityText(qty)} $unit remaining.",
        "warning", "/admin/inventory", forRoles = withRoles(MGMT, Role.ACCOUNTANT)
    )

    suspend fun notifyNewReview(name: String, rating: Int?) = notify(
        NotificationType.NEW_REVIEW, "New Guest Review Awaiting Approval",
        "$name left a ${if (rating != null) "$rating-star " else ""}review — review it on the Guest Reviews page.",
        "info", "/admin/reviews", forRoles = MGMT
    )

    suspend fun notifyContactMessage(name: String, email: String, subject: String, message: String) = notify(
        NotificationType.CONTACT_MESSAGE, "New Contact Inquiry",
        "From: $name <$email>\nSubject: $subject\n\n${truncateContactMessage(message)}",
        "info", "/admin/messages", forRoles = FRONT_DESK
    )

    // ── gym ──

    suspend fun notifyGymMembershipRegistered(member: String, packageName: String, by: String) = notify(
        NotificationType.GYM_MEMBERSHIP_REGISTERED, "New Gym Membership",
        "$member registered for $packageName by $by.",
        "info", "/admin/gym/members", forRoles = GYM_OVERSIGHT, excludeActor = true
    )

    suspend fun notifyGymMembershipRenewed(member: String, packageName: String, by: String) = notify(
        NotificationType.GYM_MEMBERSHIP_RENEWED, "Gym Membership Renewed",
        "$member's $packageName membership was renewed by $by.",
        "info", "/admin/gym/members", forRoles = GYM_OVERSIGHT, excludeActor = true
    )

    suspend fun notifyGymMembershipExpiring(member: String, days: Int) = notify(
        NotificationType.GYM_MEMBERSHIP_EXPIRING, "Membership Expiring Soon",
        "$member's gym membership expires in $days day(s).",
        "warning", "/admin/gym/members", forRoles = withRoles(GYM_OVERSIGHT, Role.GYM_STAFF)
    )

    suspend fun notifyGymMembershipSuspended(member: String, by: String, reason: String?) = notify(
        NotificationType.GYM_MEMBERSHIP_SUSPENDED, "Gym Membership Suspended",
        "$member's membership was suspended by $by${dashSuffix(reason)}.",
        "warning", "/admin/gym/members", forRoles = GYM_OVERSIGHT, excludeActor = true
    )

    suspend fun notifyGymCheckIn(member: String, by: String) = notify(
        NotificationType.GYM_CHECK_IN, "Gym Check-In",
        "$member checked in (by $by).",
        "info", "/admin/gym/checkin", forRoles = listOf(Role.GYM_STAFF), excludeActor = true
    )

    suspend fun notifyGymCheckOut(member: String, by: String) = notify(
        NotificationType.GYM_CHECK_OUT, "Gym Check-Out",
        "$member checked out (by $by).",
        "info", "/admin/gym/checkin", forRoles = listOf(Role.GYM_STAFF), excludeActor = true
    )

    // ── alerts ──

    suspend fun notifyStaffAlert(title: String, message: String, severity: String = "warning") = notify(
        NotificationType.STAFF_ALERT, title, message, severity, "/admin/audit-log", forRoles = MGMT
    )

    suspend fun notifySystemAlert(title: String, message: String, severity: String = "critical") = notify(
        NotificationType.SYSTEM_ALERT, title, message, severity, "/admin/dashboard", forRoles = listOf(Role.SUPER_ADMIN)
    )
}
