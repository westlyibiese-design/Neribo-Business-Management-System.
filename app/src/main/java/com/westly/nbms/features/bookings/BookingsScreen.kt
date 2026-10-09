package com.westly.nbms.features.bookings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant

private val TABLET_WIDTH = 600.dp

/** All Bookings: search, status filter and chips, a table on tablets and one card per booking on phones. */
@Composable
fun BookingsScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: BookingsViewModel = hiltViewModel()
) {
    val bookings by vm.bookings.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val role = session.user.role
    val symbol = session.business.currencySymbol

    var query by rememberSaveable { mutableStateOf("") }
    var statusKey by rememberSaveable { mutableStateOf("all") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var extendId by rememberSaveable { mutableStateOf<String?>(null) }

    val loaded: List<Booking> = (bookings as? Resource.Success)?.data.orEmpty()
    val subtitle = if (bookings is Resource.Success) bookingCountText(loaded.size) else null
    val canExtend = vm.extendStay != null && canExtendStay(role)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeader(title = "Bookings", subtitle = subtitle)

            when (val r = bookings) {
                is Resource.Loading -> LoadingState()
                is Resource.Error -> ErrorState("We couldn't load bookings.", onRetry = vm::retry)
                is Resource.Success -> {
                    FilterBar(
                        wide = wide,
                        query = query,
                        onQuery = { query = it },
                        statusKey = statusKey,
                        onStatus = { statusKey = it }
                    )
                    val counts = remember(r.data) { statusCounts(r.data) }
                    StatusChips(counts = counts, selected = statusKey, onSelect = { statusKey = it })

                    val visible = remember(r.data, query, statusKey) { filterBookings(r.data, query, statusKey) }
                    if (visible.isEmpty()) {
                        EmptyState(
                            icon = NbmsIcons.CalendarCheck,
                            title = "No bookings found",
                            message = if (r.data.isEmpty()) "Bookings will appear here once guests reserve or check in."
                            else "No bookings match your search or filter."
                        )
                    } else if (wide) {
                        BookingsTable(
                            bookings = visible,
                            symbol = symbol,
                            canExtend = canExtend,
                            onView = { selectedId = it.id },
                            onExtend = { extendId = it.id }
                        )
                    } else {
                        PagedList(items = visible, key = { it.id }) { booking ->
                            BookingCard(booking, symbol, onView = { selectedId = booking.id })
                        }
                    }
                }
            }
        }
    }

    val selected = loaded.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BookingDetailsDialog(
            booking = selected,
            symbol = symbol,
            canChangeStatus = canChangeStatus(role),
            canExtend = canExtend,
            busy = ui.busy,
            onChangeStatus = { status -> vm.changeStatus(selected, status) { selectedId = null } },
            onExtend = { extendId = selected.id },
            onDismiss = { selectedId = null }
        )
    }

    val extending = loaded.firstOrNull { it.id == extendId }
    val launcher = vm.extendStay
    if (extending != null && launcher != null && canExtendStay(role)) {
        launcher.Dialog(booking = extending, onDismiss = { extendId = null })
    }
}

// ---- Filter bar and chips ---------------------------------------------------------------------------

@Composable
private fun FilterBar(
    wide: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    statusKey: String,
    onStatus: (String) -> Unit
) {
    val dropdown: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "",
            options = STATUS_FILTER_OPTIONS,
            selected = STATUS_FILTER_OPTIONS.firstOrNull { it.first == statusKey },
            onSelect = { onStatus(it.first) },
            optionLabel = { it.second },
            modifier = m,
            placeholder = "Status"
        )
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search by guest, room, booking ID…",
                modifier = Modifier.weight(1f)
            )
            dropdown(Modifier.width(160.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = query, onValueChange = onQuery, placeholder = "Search by guest, room, booking ID…")
            dropdown(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(counts: Map<String, Int>, selected: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        STATUS_CHIP_KEYS.forEach { key ->
            val on = key == selected
            Text(
                chipLabel(key, counts),
                color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(if (on) scheme.primary else scheme.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(key) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

// ---- Phone card -------------------------------------------------------------------------------------

@Composable
private fun BookingCard(booking: Booking, symbol: String, onView: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    booking.guestName.ifBlank { "Guest" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                BookingStatusBadge(booking.status)
            }
            if (!booking.guestEmail.isNullOrBlank()) {
                Text(booking.guestEmail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Text(
                "Room ${booking.roomNumber} - ${booking.roomType.orDash()}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
            Text(
                "${Format.dateTime(booking.checkIn.toInstant())} → ${Format.dateTime(booking.checkOut.toInstant())}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Format.currency(booking.totalAmount, symbol),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                NbmsButton(
                    text = "View",
                    onClick = onView,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    leadingIcon = NbmsIcons.Eye
                )
            }
        }
    }
}

// ---- Tablet table -----------------------------------------------------------------------------------

private const val W_GUEST = 2.2f
private const val W_ROOM = 1.3f
private const val W_DATE = 1.5f
private const val W_AMOUNT = 1.1f
private const val W_STATUS = 1.2f
private const val W_ACTIONS = 2.0f

@Composable
private fun BookingsTable(
    bookings: List<Booking>,
    symbol: String,
    canExtend: Boolean,
    onView: (Booking) -> Unit,
    onExtend: (Booking) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderCell("Guest", W_GUEST)
            HeaderCell("Room", W_ROOM)
            HeaderCell("Check-In", W_DATE)
            HeaderCell("Check-Out", W_DATE)
            HeaderCell("Amount", W_AMOUNT)
            HeaderCell("Status", W_STATUS)
            HeaderCell("Actions", W_ACTIONS)
        }
        HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
        PagedList(items = bookings, key = { it.id }) { b ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(W_GUEST)) {
                        Text(
                            b.guestName.ifBlank { "Guest" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!b.guestEmail.isNullOrBlank()) {
                            Text(
                                b.guestEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Column(Modifier.weight(W_ROOM)) {
                        Text(
                            "Room ${b.roomNumber}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurface
                        )
                        Text(b.roomType.orDash(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Text(
                        Format.dateTime(b.checkIn.toInstant()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(W_DATE)
                    )
                    Text(
                        Format.dateTime(b.checkOut.toInstant()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(W_DATE)
                    )
                    Text(
                        Format.currency(b.totalAmount, symbol),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(W_AMOUNT)
                    )
                    Row(Modifier.weight(W_STATUS)) { BookingStatusBadge(b.status) }
                    Row(Modifier.weight(W_ACTIONS), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        NbmsButton(
                            text = "View",
                            onClick = { onView(b) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            leadingIcon = NbmsIcons.Eye
                        )
                        if (canExtend && b.status == BookingStatus.CHECKED_IN.key) {
                            NbmsButton(
                                text = "Extend Stay",
                                onClick = { onExtend(b) },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                leadingIcon = NbmsIcons.CalendarClock
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight)
    )
}

// ---- Buttons shared with the detail dialog ----------------------------------------------------------

internal enum class ActionStyle { Confirm, OrangeOutline }

/** A small (32dp) button for the Change Status row. Confirm is solid green; OrangeOutline is an orange outline. */
@Composable
internal fun StatusActionButton(
    text: String,
    icon: ImageVector,
    style: ActionStyle,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.small
    val orange = if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    val container = if (style == ActionStyle.Confirm) nbms.success else Color.Transparent
    val content = if (style == ActionStyle.Confirm) nbms.onSuccess else orange
    val border = if (style == ActionStyle.Confirm) nbms.success else Color(0xFFFB923C)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(14.dp))
        Text(
            text,
            color = content.copy(alpha = if (enabled) 1f else 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
