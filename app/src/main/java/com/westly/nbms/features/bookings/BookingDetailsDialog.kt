package com.westly.nbms.features.bookings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant

/** "Booking Details": the booking's facts, the Extend Stay button and, for permitted roles, the Change Status buttons. */
@Composable
fun BookingDetailsDialog(
    booking: Booking,
    symbol: String,
    canChangeStatus: Boolean,
    canExtend: Boolean,
    busy: Boolean,
    onChangeStatus: (BookingStatus) -> Unit,
    onExtend: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val twoColumns = LocalConfiguration.current.screenWidthDp >= 600
    val status = BookingStatus.fromKey(booking.status)
    val transitions = if (status != null) allowedTransitions(status) else emptySet()

    NbmsDialog(
        title = "Booking Details",
        description = bookingCode(booking),
        onDismiss = onDismiss,
        onConfirm = null
    ) {
        val left: List<@Composable () -> Unit> = listOf(
            { Field("Guest") { ValueText(booking.guestName.orDash()) } },
            { Field("Email") { ValueText(booking.guestEmail.orDash()) } },
            { Field("Phone") { ValueText(Format.phone(booking.guestPhone)) } },
            { Field("Guests") { ValueText(guestsText(booking)) } }
        )
        val right: List<@Composable () -> Unit> = listOf(
            {
                Field("Room") {
                    Text(
                        "Room ${booking.roomNumber.orDash()}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface
                    )
                    if (!booking.roomType.isNullOrBlank()) {
                        Text(booking.roomType, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            },
            { Field("Check-In") { ValueText(Format.dateTime(booking.checkIn.toInstant())) } },
            { Field("Check-Out") { ValueText(Format.dateTime(booking.checkOut.toInstant())) } },
            {
                Field("Total Amount") {
                    Text(
                        Format.currency(booking.totalAmount, symbol),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                }
            }
        )

        if (twoColumns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { left.forEach { it() } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { right.forEach { it() } }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                left.forEach { it() }
                right.forEach { it() }
            }
        }

        if (!booking.specialRequests.isNullOrBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceVariant)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Special Requests", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
                Text(booking.specialRequests, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
        }

        if (canExtend && booking.status == BookingStatus.CHECKED_IN.key) {
            NbmsButton(
                text = "Extend Stay",
                onClick = onExtend,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                leadingIcon = NbmsIcons.CalendarClock
            )
        }

        if (canChangeStatus && transitions.isNotEmpty()) {
            ChangeStatusSection(transitions, busy, onChangeStatus)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeStatusSection(
    transitions: Set<BookingStatus>,
    busy: Boolean,
    onChangeStatus: (BookingStatus) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "CHANGE STATUS",
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BookingStatus.CONFIRMED in transitions) {
                StatusActionButton("Confirm", NbmsIcons.CheckCircle, ActionStyle.Confirm, enabled = !busy) {
                    onChangeStatus(BookingStatus.CONFIRMED)
                }
            }
            if (BookingStatus.REJECTED in transitions) {
                NbmsButton(
                    text = "Reject",
                    onClick = { onChangeStatus(BookingStatus.REJECTED) },
                    variant = ButtonVariant.Destructive,
                    size = ButtonSize.Sm,
                    enabled = !busy,
                    leadingIcon = NbmsIcons.XCircle
                )
            }
            // From "pending" the Cancel option is allowed by the rules, but Westly offers only Confirm / Reject there.
            if (BookingStatus.CANCELLED in transitions && BookingStatus.REJECTED !in transitions) {
                StatusActionButton("Cancel", NbmsIcons.XCircle, ActionStyle.OrangeOutline, enabled = !busy) {
                    onChangeStatus(BookingStatus.CANCELLED)
                }
            }
            if (BookingStatus.NO_SHOW in transitions) {
                StatusActionButton("No Show", NbmsIcons.Clock, ActionStyle.OrangeOutline, enabled = !busy) {
                    onChangeStatus(BookingStatus.NO_SHOW)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        value()
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        overflow = TextOverflow.Ellipsis
    )
}
