package com.westly.nbms.features.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.bookings.Booking
import kotlinx.coroutines.flow.Flow

/** "Extend Stay": more nights for a guest who is staying on, instead of checking them out. */
@Composable
internal fun ExtendStayDialog(booking: Booking, onDismiss: () -> Unit) {
    val vm: ExtendStayViewModel = hiltViewModel(key = "extend-${booking.id}")
    val busy by vm.busy.collectAsStateWithLifecycle()
    val officialTime by vm.checkOutTime.collectAsStateWithLifecycle()
    val roomPriceFlow: Flow<Double?> = remember(booking.roomId) { vm.roomPrice(booking.roomId) }
    val roomPrice by roomPriceFlow.collectAsStateWithLifecycle(initialValue = null)

    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val zone = vm.zone
    val allowed = mayExtend(vm.role)

    var nightsText by rememberSaveable { mutableStateOf("1") }
    var methodKey by rememberSaveable { mutableStateOf(ExtendPaymentMethod.CASH.key) }
    val method = ExtendPaymentMethod.entries.firstOrNull { it.key == methodKey } ?: ExtendPaymentMethod.CASH

    val extraNights = parseExtraNights(nightsText)
    val rate = nightlyRate(booking.pricePerNight, booking.totalAmount, booking.nights, roomPrice)
    val additional = additionalAmount(rate, extraNights.coerceAtLeast(0))
    val checkOut = booking.checkOut.asInstant()
    val currentText = checkOut?.let { Format.dateTime(combineDateAndTime(it.localDate(zone), officialTime, zone), zone) } ?: "—"
    val newText = checkOut?.let {
        Format.dateTime(combineDateAndTime(addDays(it, extraNights.coerceAtLeast(0), zone).localDate(zone), officialTime, zone), zone)
    } ?: "—"
    val symbol = vm.currencySymbol

    ActionDialog(
        title = "Extend Stay",
        description = EXTEND_DESCRIPTION,
        locked = busy,
        onDismiss = onDismiss,
        titleIcon = NbmsIcons.CalendarClock,
        footer = {
            if (allowed) {
                BusyButton(
                    text = "Confirm Extension",
                    busyText = "Extending…",
                    busy = busy,
                    enabled = !busy,
                    onClick = { vm.confirm(booking, extraNights, method, onDismiss) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            NbmsButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                enabled = !busy
            )
        }
    ) {
        if (!allowed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(nbms.destructiveContainer)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = nbms.onDestructiveContainer, modifier = Modifier.size(16.dp))
                Text(MSG_NO_PERMISSION_BOX, style = MaterialTheme.typography.bodyMedium, color = nbms.onDestructiveContainer)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceVariant)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRow("Guest") { Medium(booking.guestName) }
                SummaryRow("Room") { Medium("Room ${booking.roomNumber}") }
                SummaryRow("Current checkout") { Medium(currentText) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Additional Nights", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_NIGHTS.forEach { n ->
                        NightChip(chipText(n), selected = extraNights == n, enabled = !busy) { nightsText = n.toString() }
                    }
                }
                NbmsTextField(
                    value = nightsText,
                    onValueChange = { nightsText = it.filter { c -> c in '0'..'9' }.take(3) },
                    label = "",
                    modifier = Modifier.width(80.dp),
                    keyboardType = KeyboardType.Number,
                    enabled = !busy
                )
            }

            NbmsDropdown(
                label = "Payment Method",
                options = ExtendPaymentMethod.entries,
                selected = method,
                onSelect = { methodKey = it.key },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, nbms.cardBorder, MaterialTheme.shapes.medium)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRow("Current checkout") { Medium(currentText) }
                SummaryRow("Extension") { Medium(extensionText(extraNights.coerceAtLeast(0))) }
                SummaryRow("New checkout") { Bold(newText) }
                RowDivider()
                SummaryRow("Additional accommodation") { Medium(Format.currency(additional, symbol)) }
                SummaryRow("Total additional amount") { Bold(Format.currency(additional, symbol)) }
            }
        }
    }
}

@Composable
private fun NightChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else MaterialTheme.nbms.inputBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onPrimary else scheme.onBackground,
            maxLines = 1
        )
    }
}

@Composable
private fun Medium(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Bold(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
}
