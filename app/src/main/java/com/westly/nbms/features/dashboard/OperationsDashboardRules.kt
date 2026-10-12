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
import java.time.ZoneId
import java.util.Locale

// Pure rules of the Operations Manager dashboard (Phase 33B). Everything here is unit-tested and free of Compose.
// Counts and lists come from DashboardMath; this file only decides wording, row caps and the Assign pre-fills.

/** Every section card shows at most this many rows. */
internal const val OPS_ROW_CAP = 6

/** Staff On Duty shows this many name chips, then a "+n more" chip. */
internal const val OPS_STAFF_CHIP_CAP = 10

internal const val ROUTE_SHIFTS = "shifts"
internal const val ROUTE_TASKS = "tasks"
internal const val ROUTE_ROOM_ASSIGNMENTS = "housekeeping/assignments"
internal const val ROUTE_ROOM_RESERVATIONS = "room-reservations"
internal const val ROUTE_HOUSEKEEPING = "housekeeping"
internal const val ROUTE_MAINTENANCE = "maintenance"
internal const val ROUTE_ORDER_HISTORY = "orders/history"
internal const val ROUTE_BAR_SALES_HISTORY = "bar/sales-history"
internal const val ROUTE_LAUNDRY = "laundry"
internal const val ROUTE_ATTENDANCE = "attendance"

private const val GUEST_FALLBACK = "Guest"
private const val MAINTENANCE_ISSUE_FALLBACK = "maintenance issue"
private const val MAINTENANCE_ROW_FALLBACK = "Maintenance request"

private fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

private fun clean(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

/** "Room 101" for a room number, or null when there is none. */
private fun roomText(roomNumber: String?): String? = clean(roomNumber)?.let { "Room $it" }

/** One alert pill under the header. */
internal data class OpsPill(val text: String, val tone: AlertTone)

/** Blue "{n} unconfirmed reservation(s)", red "{n} overdue task(s)", orange "{n} open maintenance request(s)": only those that apply. */
internal fun opsAlertPills(reservations: Int, overdueTasks: Int, openMaintenance: Int): List<OpsPill> = buildList {
    if (reservations > 0) add(OpsPill("$reservations unconfirmed ${plural(reservations, "reservation", "reservations")}", AlertTone.BLUE))
    if (overdueTasks > 0) add(OpsPill("$overdueTasks overdue ${plural(overdueTasks, "task", "tasks")}", AlertTone.RED))
    if (openMaintenance > 0) {
        add(OpsPill("$openMaintenance open maintenance ${plural(openMaintenance, "request", "requests")}", AlertTone.ORANGE))
    }
}

/** Which optional sections the business has. The three order/laundry sections and Room Readiness each need their module. */
internal data class OpsVisibility(val housekeeping: Boolean, val restaurant: Boolean, val bar: Boolean, val laundry: Boolean)

internal fun opsVisibility(modules: Set<ModuleKey>): OpsVisibility = OpsVisibility(
    housekeeping = ModuleKey.HOUSEKEEPING in modules,
    restaurant = ModuleKey.RESTAURANT in modules,
    bar = ModuleKey.BAR in modules,
    laundry = ModuleKey.LAUNDRY in modules
)

/** A row of a section card: what to show, plus what the Assign button pre-fills. */
internal data class OpsRow(
    val primary: String,
    val secondary: String? = null,
    val badge: String? = null,
    val defaults: TaskDefaults
)

/** A section card's sub line and its rows (already capped). */
internal data class OpsSection(val sub: String?, val rows: List<OpsRow>)

/** Room Readiness: the four numbers, then the rooms waiting to be cleaned. */
internal data class OpsReadiness(val available: Int, val occupied: Int, val cleaning: Int, val maintenance: Int, val rows: List<OpsRow>)

// ---- Room Reservations ----

internal fun reservationsSub(count: Int): String =
    "$count unconfirmed ${plural(count, "reservation", "reservations")} awaiting front-desk action"

/** [reservations] are the unconfirmed online reservations (DashboardMath.pendingReservations). */
internal fun reservationRows(reservations: List<DashBooking>, zone: ZoneId): List<OpsRow> =
    reservations.take(OPS_ROW_CAP).map { b ->
        val guest = clean(b.guestName) ?: GUEST_FALLBACK
        val roomType = clean(b.roomType)
        val date = dashDate(b.checkIn, zone)
        OpsRow(
            primary = if (roomType != null) "$guest · $roomType" else guest,
            defaults = TaskDefaults(
                type = TaskType.BOOKING,
                title = "Confirm & process booking — $guest",
                relatedCollection = "bookings",
                relatedId = b.id,
                relatedLabel = if (roomType != null) "$roomType · $date" else date
            )
        )
    }

// ---- Room Readiness ----

/** Rooms with status "cleaning" (deleted rooms never show). */
internal fun cleaningRooms(rooms: List<DashRoom>): List<DashRoom> = rooms.filter { !it.isDeleted && it.status == "cleaning" }

internal fun readinessRows(rooms: List<DashRoom>): List<OpsRow> =
    cleaningRooms(rooms).take(OPS_ROW_CAP).map { r ->
        val label = "Room ${clean(r.roomNumber) ?: "—"}"
        OpsRow(
            primary = label,
            defaults = TaskDefaults(
                type = TaskType.HOUSEKEEPING,
                title = "Clean $label",
                relatedCollection = "rooms",
                relatedId = r.id,
                relatedLabel = label
            )
        )
    }

// ---- Maintenance ----

/** [open] are the unresolved requests (DashboardMath.openMaintenance). */
internal fun maintenanceRows(open: List<DashMaintenance>): List<OpsRow> =
    open.take(OPS_ROW_CAP).map { m ->
        val area = clean(m.roomOrArea)
        val primary = clean(m.issue) ?: clean(m.title) ?: area ?: MAINTENANCE_ROW_FALLBACK
        OpsRow(
            primary = primary,
            secondary = area?.takeIf { it != primary },
            defaults = TaskDefaults(
                type = TaskType.MAINTENANCE,
                title = "Resolve: ${clean(m.issue) ?: MAINTENANCE_ISSUE_FALLBACK}",
                relatedCollection = "maintenance",
                relatedId = m.id,
                relatedLabel = area
            )
        )
    }

// ---- Restaurant ----

internal fun restaurantSub(active: Int, unassigned: Int): String =
    "$active active ${plural(active, "order", "orders")} · $unassigned unassigned"

/** "Room 12" or "Table 4" when the order has one (room first), else null. */
private fun placeLabel(roomNumber: String?, tableNumber: String?): String? =
    roomText(roomNumber) ?: clean(tableNumber)?.let { "Table $it" }

/** [unassigned] are the pending orders with no waiter (DashboardMath.unassignedFood). */
internal fun restaurantRows(unassigned: List<DashOrder>): List<OpsRow> =
    unassigned.take(OPS_ROW_CAP).map { o ->
        val party = DashboardMath.partyLabel(o.customerName, o.roomNumber, o.tableNumber)
        OpsRow(
            primary = party,
            defaults = TaskDefaults(
                type = TaskType.FOOD_ORDER,
                title = "Fulfill food order — $party",
                relatedCollection = "orders",
                relatedId = o.id,
                relatedLabel = placeLabel(o.roomNumber, o.tableNumber)
            )
        )
    }

// ---- Bar ----

internal fun barSub(pending: Int): String = "$pending pending ${plural(pending, "sale", "sales")}"

/** [pending] are the pending bar orders (DashboardMath.pendingFood on the bar orders). */
internal fun barRows(pending: List<DashOrder>): List<OpsRow> =
    pending.take(OPS_ROW_CAP).map { o ->
        val party = DashboardMath.partyLabel(o.customerName, o.roomNumber, o.tableNumber)
        OpsRow(
            primary = party,
            defaults = TaskDefaults(
                type = TaskType.DRINK_ORDER,
                title = "Fulfill drink order — $party",
                relatedCollection = "bar_orders",
                relatedId = o.id
            )
        )
    }

// ---- Laundry ----

internal fun laundrySub(active: Int): String = "$active active ${plural(active, "request", "requests")}"

/** "in_progress" becomes "In progress" for the small status badge. */
internal fun capitalisedStatus(status: String?): String {
    val text = statusText(status).trim()
    return if (text.isEmpty()) text else text.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}

/** [active] are the requests that are not delivered or cancelled (DashboardMath.activeLaundry). */
internal fun laundryRows(active: List<DashLaundry>): List<OpsRow> =
    active.take(OPS_ROW_CAP).map { l ->
        val room = roomText(l.roomNumber)
        val who = clean(l.guestName) ?: room ?: GUEST_FALLBACK
        OpsRow(
            primary = who,
            badge = capitalisedStatus(l.status).ifEmpty { null },
            defaults = TaskDefaults(
                type = TaskType.LAUNDRY,
                title = "Handle laundry request — $who",
                relatedCollection = "laundry_requests",
                relatedId = l.id,
                relatedLabel = room
            )
        )
    }

// ---- Staff On Duty ----

/** The name chips to draw and how many more people are on duty. */
internal data class StaffChips(val names: List<String>, val more: Int)

/** [onDuty] are today's present staff (DashboardMath.staffOnDuty): the first 10 names, then the rest as a count. */
internal fun staffChips(onDuty: List<DashAttendance>): StaffChips = StaffChips(
    names = onDuty.take(OPS_STAFF_CHIP_CAP).map { clean(it.staffName) ?: "Staff" },
    more = (onDuty.size - OPS_STAFF_CHIP_CAP).coerceAtLeast(0)
)

internal fun moreChipText(more: Int): String = "+$more more"

/** "Welcome, Ada · Monday, October 12" in the business zone. */
internal fun operationsSubtitle(name: String, now: java.time.Instant, zone: ZoneId): String =
    "Welcome, $name · ${dayDateText(now, zone)}"
