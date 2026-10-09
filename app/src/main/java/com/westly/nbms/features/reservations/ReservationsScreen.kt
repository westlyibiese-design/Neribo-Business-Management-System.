package com.westly.nbms.features.reservations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatusBadge
import kotlinx.datetime.TimeZone
import com.westly.nbms.core.rbac.Role as StaffRole

private val GreenButton = Color(0xFF16A34A)
private val OrangeText = Color(0xFFEA580C)
private val OrangeBorder = Color(0xFFFB923C)

private const val SUBTITLE =
    "Guests who booked through the website — confirm arrivals, check guests in, and manage reservation status"
private const val SEARCH_PLACEHOLDER = "Search by guest name, room number, email, or booking ID…"
private const val EMPTY_TEXT = "No room reservations found"

/** The Room Reservations page (Westly's RoomReservationsPage): website bookings, their status buttons and check-in. */
@Composable
fun ReservationsScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: ReservationsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val zone = remember(session.business.timezone) { reservationZone(session.business.timezone) }
    val symbol = session.business.currencySymbol
    val role = session.user.role

    val success = checkIn.success
    if (success != null) {
        ReservationCheckInSuccess(success, zone, symbol, onAnother = vm::checkInAnother, modifier = modifier)
        return
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Header()

        when (ui.status) {
            LoadStatus.LOADING -> LoadingState()
            LoadStatus.ERROR -> ErrorState(UI_LOAD_FAILED, onRetry = vm::retry)
            LoadStatus.READY -> {
                SearchBar(value = ui.query, onValueChange = vm::setQuery, placeholder = SEARCH_PLACEHOLDER)
                Chips(ui.chips, onSelect = vm::setFilter)
                if (ui.visible.isEmpty()) {
                    EmptyList()
                } else {
                    PagedList(items = ui.visible, key = { it.id }) { booking ->
                        ReservationCard(
                            booking = booking,
                            role = role,
                            zone = zone,
                            busy = busy,
                            onOpen = { vm.openCheckIn(booking) },
                            onAction = { action -> vm.onAction(booking, action) }
                        )
                    }
                }
            }
        }
    }

    val dialogBooking = checkIn.booking
    if (dialogBooking != null) {
        ReservationCheckInDialog(state = checkIn, booking = dialogBooking, zone = zone, symbol = symbol, vm = vm)
    }
}

// ---- Header, search, chips, empty ------------------------------------------------------------------

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            NbmsIcons.Globe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("Room Reservations", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(SUBTITLE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pill chips: selected = filled primary, the others muted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<ReservationChip>, onSelect: (ReservationFilter) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            Text(
                text = chip.text,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (chip.selected) scheme.onPrimary else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (chip.selected) scheme.primary else scheme.surfaceVariant)
                    .clickable(role = Role.Button) { onSelect(chip.filter) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun EmptyList() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            NbmsIcons.UserCheck,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp).alpha(0.3f)
        )
        Text(
            EMPTY_TEXT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ---- Reservation card ------------------------------------------------------------------------------

@Composable
private fun ReservationCard(
    booking: Booking,
    role: StaffRole,
    zone: TimeZone,
    busy: Boolean,
    onOpen: () -> Unit,
    onAction: (ReservationAction) -> Unit
) {
    val awaiting = ReservationRules.awaitingCheckIn(booking)
    val actions = ReservationRules.actionsFor(booking, role)

    NbmsCard(Modifier.fillMaxWidth()) {
        // The content opens the check-in dialog; the action row below is outside this tap area.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .then(if (awaiting) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
        ) {
            val sideBySide = maxWidth >= 480.dp
            val guest: @Composable (Modifier) -> Unit = { m -> GuestBlock(booking, m) }
            val room: @Composable (Modifier, Boolean) -> Unit = { m, end -> RoomBlock(booking, zone, end, m) }
            if (sideBySide) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    guest(Modifier.weight(1f))
                    room(Modifier, true)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    guest(Modifier.fillMaxWidth())
                    room(Modifier.padding(start = 52.dp), false)
                }
            }
        }

        if (actions.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            ActionRow(actions, busy, onAction)
        }
    }
}

/** The 40dp initial circle, the name, the email and the status pill. */
@Composable
private fun GuestBlock(booking: Booking, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = booking.guestName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = scheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                booking.guestName.ifBlank { "Guest" },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val email = booking.guestEmail
            if (!email.isNullOrBlank()) {
                Text(email, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BookingStatusBadge(booking.status)
        }
    }
}

/** Bed icon + "Room {n}", the room type and the check-in line. */
@Composable
private fun RoomBlock(booking: Booking, zone: TimeZone, alignEnd: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val horizontal = if (alignEnd) Alignment.End else Alignment.Start
    Column(modifier, horizontalAlignment = horizontal, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconLine(NbmsIcons.Bed) {
            Text(
                "Room ${booking.roomNumber}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurface
            )
        }
        val type = booking.roomType
        if (!type.isNullOrBlank()) {
            Text(type, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
        }
        IconLine(NbmsIcons.Calendar) {
            Text(
                "Check-in: ${Format.dateTime(cardCheckIn(booking), zone)}",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        text()
    }
}

// ---- Action buttons --------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(actions: List<ReservationAction>, busy: Boolean, onAction: (ReservationAction) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            val spec = actionSpec(action)
            when (action) {
                ReservationAction.CONFIRM_ARRIVAL -> ColoredActionButton(
                    spec.label, NbmsIcons.CheckCircle, GreenButton, Color.White, GreenButton, !busy
                ) { onAction(action) }
                ReservationAction.REJECT -> NbmsButton(
                    text = spec.label,
                    onClick = { onAction(action) },
                    variant = ButtonVariant.Destructive,
                    size = ButtonSize.Sm,
                    enabled = !busy,
                    leadingIcon = NbmsIcons.XCircle
                )
                ReservationAction.CHECK_IN -> NbmsButton(
                    text = spec.label,
                    onClick = { onAction(action) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    enabled = !busy,
                    leadingIcon = NbmsIcons.UserCheck
                )
                ReservationAction.CANCEL -> ColoredActionButton(
                    spec.label, NbmsIcons.XCircle, Color.Transparent, OrangeText, OrangeBorder, !busy
                ) { onAction(action) }
                ReservationAction.NO_SHOW -> ColoredActionButton(
                    spec.label, NbmsIcons.Clock, Color.Transparent, OrangeText, OrangeBorder, !busy
                ) { onAction(action) }
            }
        }
    }
}

/** A small (32dp) button with its own colours: the green "Confirm Arrival" and the orange outline ones. */
@Composable
private fun ColoredActionButton(
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    border: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}
