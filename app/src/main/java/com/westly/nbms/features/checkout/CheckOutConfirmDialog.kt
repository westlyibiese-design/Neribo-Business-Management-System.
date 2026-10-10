package com.westly.nbms.features.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.AdaptiveTwoColumn
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.TimeZone

private val Amber = Color(0xFFD97706)

/** "Confirm Check-Out": the summary, the actual date and time, extra charges, payment method and notes. */
@Composable
internal fun CheckOutConfirmDialog(
    booking: Booking,
    draft: CheckOutDraft,
    busy: Boolean,
    slow: Boolean,
    officialTime: String,
    zone: TimeZone,
    symbol: String,
    vm: CheckOutViewModel
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val scheduled = scheduledCheckOutAt(booking, officialTime, zone)
    val extras = parseExtras(draft.extrasText)
    val paid = isRoomPaid(booking.roomPaymentStatus)
    val due = totalDue(booking.totalAmount, booking.roomPaymentStatus, extras)
    val actual = draft.actualAt(zone)
    val timing = if (actual != null && scheduled != null) checkoutTiming(actual, scheduled) else null

    ActionDialog(
        title = "Confirm Check-Out",
        description = null,
        locked = busy,
        onDismiss = vm::closeDialog,
        footer = {
            BusyButton(
                text = "Complete Check-Out",
                busyText = "Processing…",
                busy = busy,
                enabled = !busy && actual != null,
                onClick = vm::submit,
                modifier = Modifier.fillMaxWidth(),
                icon = NbmsIcons.LogOut
            )
            NbmsButton(
                text = "Cancel",
                onClick = vm::closeDialog,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                enabled = !busy
            )
        }
    ) {
        // Summary box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryRow("Guest") { BoldText(booking.guestName) }
            SummaryRow("Room") { BoldText("Room ${booking.roomNumber}") }
            SummaryRow("Scheduled Checkout") { BoldText(Format.dateTime(scheduled, zone)) }
            SummaryRow(
                "Room Charges",
                note = if (paid) "(paid at check-in)" else null,
                noteColor = nbms.success
            ) { BoldText(Format.currency(booking.totalAmount, symbol)) }
            if (extras > 0.0) {
                SummaryRow("Extra Charges") {
                    Text(
                        "+${Format.currency(extras, symbol)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = OrangeText
                    )
                }
            }
            RowDivider()
            SummaryRow("Due Now") {
                Text(
                    Format.currency(due, symbol),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
            }
            if (paid) {
                Text(MSG_ROOM_ALREADY_PAID, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
            }
        }

        // Actual date and time
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Actual Check-Out Date & Time *", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            AdaptiveTwoColumn {
                NbmsDatePickerField(
                    label = "",
                    value = draft.actualDate,
                    onChange = vm::setActualDate,
                    enabled = !busy
                )
                NbmsTimePickerField(
                    label = "",
                    value = draft.actualTime,
                    onChange = vm::setActualTime,
                    enabled = !busy
                )
            }
            Text(
                "Defaults to right now — the exact moment you're processing this check-out. Adjust it only if the " +
                    "guest actually left at a different, known time. The hotel's official check-out time " +
                    "($officialTime) is used only to compute the scheduled time above, never the actual one.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant
            )
            if (timing != null) {
                val color = when (timing.kind) {
                    TimingKind.ON_TIME -> nbms.success
                    TimingKind.EARLY -> nbms.info
                    TimingKind.LATE -> scheme.error
                }
                Text(
                    timingLine(timing),
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
        }

        NbmsTextField(
            value = draft.extrasText,
            onValueChange = vm::setExtras,
            label = "Extra Charges (e.g. minibar, damages)",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            enabled = !busy
        )

        NbmsDropdown(
            label = "Payment Method",
            options = CheckOutPaymentMethod.entries,
            selected = draft.method,
            onSelect = vm::setMethod,
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )

        NbmsTextField(
            value = draft.notes,
            onValueChange = vm::setNotes,
            label = "Notes (optional)",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Any notes",
            singleLine = false,
            enabled = !busy
        )

        if (busy && slow) {
            Text(
                MSG_STILL_WORKING,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BoldText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End
    )
}
