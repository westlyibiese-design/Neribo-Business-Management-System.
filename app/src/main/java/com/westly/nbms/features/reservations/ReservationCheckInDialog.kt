package com.westly.nbms.features.reservations

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.westly.nbms.core.design.AdaptiveTwoColumn
import com.westly.nbms.core.design.LabelValueRowSlot
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.design.nbmsShadow
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.bookings.Booking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Amber = Color(0xFFD97706)
private val OrangeDue = Color(0xFFEA580C)

/** "Check-in time: 2:30 PM" uses this: hour without a leading zero, AM / PM in capitals. */
internal fun checkInTimeText(at: Instant, zone: TimeZone): String {
    val javaZone = try {
        ZoneId.of(zone.id)
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }
    return java.time.Instant.ofEpochSecond(at.epochSeconds, at.nanosecondsOfSecond.toLong())
        .atZone(javaZone)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
}

/** "1 night(s)" - the literal Westly wording. */
internal fun nightsText(n: Int): String = "$n night(s)"

/** "Room 101 (Deluxe Room)", or just "Room 101" when the booking has no room type. */
internal fun roomSummary(b: Booking): String {
    val type = b.roomType
    return if (type.isNullOrBlank()) "Room ${b.roomNumber}" else "Room ${b.roomNumber} ($type)"
}

// ---- The dialog ------------------------------------------------------------------------------------

/** "Confirm Check-In". While saving it cannot be dismissed (back, tap outside and the X do nothing) and the screen stays on. */
@Composable
internal fun ReservationCheckInDialog(
    state: CheckInDialogState,
    booking: Booking,
    zone: TimeZone,
    symbol: String,
    vm: ReservationsViewModel
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val busy = state.busy
    val nights = ReservationRules.nightsPaid(booking)

    KeepScreenOn(busy)

    Dialog(
        onDismissRequest = { if (!busy) vm.closeCheckIn() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            usePlatformDefaultWidth = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 512.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .nbmsShadow(8.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.background)
                .border(1.dp, nbms.cardBorder, MaterialTheme.shapes.medium)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    Modifier.padding(end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(NbmsIcons.UserCheck, contentDescription = null, tint = scheme.onBackground, modifier = Modifier.size(20.dp))
                    Text("Confirm Check-In", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
                }

                // Summary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(scheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryRow("Guest") { Medium(booking.guestName) }
                    SummaryRow("Room") { Medium(roomSummary(booking)) }
                    SummaryRow("Nights Paid") { Medium(nightsText(nights)) }
                    SummaryRow("Total") {
                        Text(
                            Format.currency(booking.totalAmount, symbol),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface
                        )
                    }
                }

                // Date and time
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Check-In Date & Time *", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    AdaptiveTwoColumn {
                        NbmsDatePickerField(
                            label = "",
                            value = state.date,
                            onChange = vm::setCheckInDate,
                            enabled = !busy
                        )
                        NbmsTimePickerField(
                            label = "",
                            value = state.time,
                            onChange = vm::setCheckInTime,
                            enabled = !busy
                        )
                    }
                }

                // Guest may stay until
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(scheme.primary.copy(alpha = 0.05f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.2f), MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "GUEST MAY STAY UNTIL",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        Format.dateTime(state.entitledCheckOut, zone),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface
                    )
                    Text(
                        "Automatically calculated from ${nightsText(nights)} paid for, ending at the hotel's official " +
                            "check-out time (${state.officialTime}, set by the Super Admin in Settings). This isn't " +
                            "editable here — if the guest leaves earlier or later, record the actual time on the " +
                            "Check-Out page when they depart.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant
                    )
                }

                NbmsTextField(
                    value = state.idDocumentRef,
                    onValueChange = vm::setIdDocumentRef,
                    label = "ID Document Reference (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Passport / National ID number",
                    enabled = !busy
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsDropdown(
                        label = "Payment Option *",
                        options = PaymentOption.entries,
                        selected = state.paymentOption,
                        onSelect = vm::setPaymentOption,
                        optionLabel = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                    Text(state.paymentOption.help(), fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                }

                if (state.paymentOption == PaymentOption.PAY_AT_CHECK_IN) {
                    NbmsDropdown(
                        label = "Payment Method",
                        options = PaymentMethod.entries,
                        selected = state.paymentMethod,
                        onSelect = vm::setPaymentMethod,
                        optionLabel = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }

                NbmsTextField(
                    value = state.notes,
                    onValueChange = vm::setNotes,
                    label = "Notes (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Any notes for this check-in",
                    singleLine = false,
                    enabled = !busy
                )

                if (busy && state.slow) {
                    Text(
                        UI_MSG_STILL_WORKING,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                        textAlign = TextAlign.Center
                    )
                }

                // Footer: the main action on top, Cancel below (phone layout).
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompleteButton(
                        busy = busy,
                        enabled = !busy && state.date != null && state.entitledCheckOut != null,
                        onClick = vm::submitCheckIn
                    )
                    NbmsButton(
                        text = "Cancel",
                        onClick = vm::closeCheckIn,
                        modifier = Modifier.fillMaxWidth(),
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Default,
                        enabled = !busy
                    )
                }
            }
            Icon(
                imageVector = NbmsIcons.Close,
                contentDescription = "Close",
                tint = scheme.onBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(16.dp)
                    .alpha(if (busy) 0.3f else 0.7f)
                    .clickable(enabled = !busy, role = Role.Button, onClick = vm::closeCheckIn)
            )
        }
    }
}

/** "Complete Check-In"; while saving a spinner and "Processing…" and it cannot be tapped. */
@Composable
private fun CompleteButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (busy) {
        val scheme = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.5f)
                .heightIn(min = 36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(scheme.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = scheme.onPrimary, strokeWidth = 2.dp)
            Text("Processing…", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 1)
        }
    } else {
        NbmsButton(
            text = "Complete Check-In",
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingIcon = NbmsIcons.UserCheck
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: @Composable () -> Unit) {
    LabelValueRowSlot(label = label, value = value)
}

@Composable
private fun Medium(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End
    )
}

// ---- Success screen --------------------------------------------------------------------------------

/** "Check-In Complete!": replaces the list until "Check In Another Guest" is tapped. */
@Composable
internal fun ReservationCheckInSuccess(
    outcome: CheckInOutcome,
    zone: TimeZone,
    symbol: String,
    onAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 384.dp)
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(nbms.successContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = nbms.success, modifier = Modifier.size(40.dp))
            }
            Text("Check-In Complete!", style = nbmsPageTitleStyle(), color = scheme.onBackground, textAlign = TextAlign.Center)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append(outcome.guestName) }
                    append(" has been checked into ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append("Room ${outcome.roomNumber}") }
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Check-in time: ${checkInTimeText(outcome.checkInAt, zone)}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(scheme.surfaceVariant)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryRow("Room Type") { Medium(outcome.roomType?.takeIf { it.isNotBlank() } ?: "—") }
                SummaryRow("Guest May Stay Until") { Medium(Format.dateTime(outcome.entitledCheckOut, zone)) }
                if (outcome.paidNow) {
                    SummaryRow("Total Paid") {
                        Text(
                            Format.currency(outcome.totalAmount, symbol),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface
                        )
                    }
                } else {
                    SummaryRow("Payment") {
                        Text(
                            "${Format.currency(outcome.totalAmount, symbol)} due at check-out",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = OrangeDue,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            NbmsButton(
                text = "Check In Another Guest",
                onClick = onAnother,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---- Keep the screen awake while saving ------------------------------------------------------------

@Composable
private fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        val window = context.findActivity()?.window
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (on) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
