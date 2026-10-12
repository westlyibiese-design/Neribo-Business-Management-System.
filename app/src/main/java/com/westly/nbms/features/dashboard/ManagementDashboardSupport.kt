package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.dashZoneOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Small helpers shared by the Super Admin and Manager dashboards (Phase 33A2).
// No dashboard number is worked out here: every figure comes from DashboardMath.

internal const val ROUTE_BOOKINGS = "bookings"
internal const val ROUTE_ROOMS = "rooms"
private const val CLOCK_TICK_MS = 60_000L

/** Ticks every minute so "today" stays right while the dashboard stays open. */
internal fun dashClock(): Flow<Instant> = flow {
    while (true) {
        emit(Instant.now())
        delay(CLOCK_TICK_MS)
    }
}

/** The business zone of the signed-in session (Africa/Lagos when missing). */
internal fun zoneOfSession(session: SessionManager): ZoneId =
    dashZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)

/** True when any of the lists is still loading and none has failed yet. */
internal fun anyLoading(vararg r: Resource<*>): Boolean = r.any { it is Resource.Loading }

/** True when any of the lists failed to load. */
internal fun anyFailed(vararg r: Resource<*>): Boolean = r.any { it is Resource.Error }

/** The rows of a successful list; nothing while loading or after an error. */
internal fun <T> Resource<List<T>>.rowsOrEmpty(): List<T> = (this as? Resource.Success)?.data ?: emptyList()

private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)

/** "Monday, October 12, 2026" in the business zone. */
internal fun longDateText(now: Instant, zone: ZoneId): String = LONG_DATE.format(now.atZone(zone))

/** "Monday, October 12" in the business zone. */
internal fun dayDateText(now: Instant, zone: ZoneId): String = DAY_DATE.format(now.atZone(zone))

/** Super Admin header: "Good day, Ada 👋". */
internal fun superAdminTitle(firstName: String): String = "Good day, $firstName 👋"

/** Manager subtitle: "Welcome, Ada · Monday, October 12". */
internal fun managerSubtitle(name: String, now: Instant, zone: ZoneId): String =
    "Welcome, $name · ${dayDateText(now, zone)}"

/** Sub line of the Today's Activity card: "↑3 in · ↓2 out". */
internal fun activitySub(checkIns: Int, checkOuts: Int): String = "↑$checkIns in · ↓$checkOuts out"

/** Sub line of the Occupancy card: "5/10 rooms". */
internal fun occupancySub(occupied: Int, total: Int): String = "$occupied/$total rooms"

/** Status text of a booking badge: the status with "_" replaced by a space. */
internal fun statusText(status: String?): String = (status ?: "").replace('_', ' ')

/** Badge colour key: confirmed green, checked_in blue, pending yellow, anything else muted. */
internal fun bookingBadgeKey(status: String?): String = when (status) {
    "confirmed", "checked_in", "pending" -> status
    else -> "out_of_service"
}

/** Grid columns: tablets (600dp and wider) get [wide] columns, phones get [narrow]. */
internal fun columnsFor(width: Dp, narrow: Int, wide: Int): Int = if (width >= 600.dp) wide else narrow

/** Rows of [columns] equal-width cells; a short last row is padded with empty cells so the widths stay equal. */
@Composable
internal fun TileGrid(columns: Int, items: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item -> Column(Modifier.weight(1f)) { item() } }
                repeat(columns - rowItems.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}

/** Reports the available width to [content] so a dashboard can decide how many columns to draw. */
@Composable
internal fun WithWidth(modifier: Modifier = Modifier, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) { content(maxWidth) }
}

/** The kotlinx instant that `Format` expects, from the java instant the dashboard rows carry. */
internal fun Instant.toKx(): kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilli())

/** "12 Mar 2025" in the business zone, or a dash when the date is missing. */
internal fun dashDate(i: Instant?, zone: ZoneId): String =
    com.westly.nbms.core.util.Format.date(i?.toKx(), kotlinx.datetime.TimeZone.of(zone.id))
