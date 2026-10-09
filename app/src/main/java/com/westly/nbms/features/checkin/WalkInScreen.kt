package com.westly.nbms.features.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomSearchSelect

private val TABLET_WIDTH = 600.dp
private val OrangeDue = Color(0xFFEA580C)

private const val SUBTITLE = "Register a walk-in guest who arrived without a prior booking and check them in"
private const val ROOM_PLACEHOLDER = "Search rooms by number, name, type, or status…"
private const val SUBMIT_TEXT = "Register Walk-In & Check In"
private const val BUSY_TEXT = "Processing…"
private const val PIN_ENDING_TEXT = "Ending session for security — enter your PIN again to register another guest."

/** The Check-In page (Westly's Walk-In page): the form, or the success screen after a saved walk-in. */
@Composable
fun WalkInScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: WalkInViewModel = hiltViewModel()
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val roomsState by vm.rooms.collectAsStateWithLifecycle()
    val checkOutTime by vm.checkOutTime.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol
    val success = ui.success

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= TABLET_WIDTH
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (success != null) {
                SuccessPanel(
                    success = success,
                    symbol = symbol,
                    zoneId = session.business.timezone,
                    usesPin = session.user.usesPin,
                    onRegisterAnother = vm::registerAnother
                )
            } else {
                PageHeader(title = "Check-In", subtitle = SUBTITLE)
                val form = ui.form
                val selectedRoom = roomsState.rooms.firstOrNull { it.id == form.roomId }
                val errors = if (ui.showErrors) fieldErrors(form) else null

                GuestCard(form, errors, wide, vm)
                RoomAndDatesCard(form, roomsState, checkOutTime, wide, vm)
                PaymentCard(form, selectedRoom, symbol, vm)
                SubmitButton(busy = ui.busy, enabled = canSubmit(ui.busy, selectedRoom), onClick = vm::submit)
            }
        }
    }
}

// ---- Cards -----------------------------------------------------------------------------------------

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun FormCard(title: String, content: @Composable () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardTitle(title)
            content()
        }
    }
}

/** Two fields side by side on wide screens, stacked on phones. */
@Composable
private fun TwoUp(wide: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GuestCard(form: WalkInForm, errors: WalkInFieldErrors?, wide: Boolean, vm: WalkInViewModel) {
    FormCard("Guest Information") {
        TwoUp(
            wide,
            first = { m ->
                NbmsTextField(
                    value = form.fullName,
                    onValueChange = vm::setFullName,
                    label = "Full Name *",
                    modifier = m,
                    placeholder = "John Doe",
                    error = errors?.name
                )
            },
            second = { m ->
                NbmsTextField(
                    value = form.phone,
                    onValueChange = vm::setPhone,
                    label = "Phone *",
                    modifier = m,
                    placeholder = "+234 …",
                    keyboardType = KeyboardType.Phone,
                    error = errors?.phone
                )
            }
        )
        TwoUp(
            wide,
            first = { m ->
                NbmsTextField(
                    value = form.email,
                    onValueChange = vm::setEmail,
                    label = "Email",
                    modifier = m,
                    placeholder = "optional",
                    keyboardType = KeyboardType.Email,
                    error = errors?.email
                )
            },
            second = { m ->
                NbmsTextField(
                    value = form.nationality,
                    onValueChange = vm::setNationality,
                    label = "Nationality",
                    modifier = m,
                    placeholder = "optional"
                )
            }
        )
        NbmsTextField(
            value = form.idDocumentRef,
            onValueChange = vm::setIdDocumentRef,
            label = "ID Document Reference",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Passport / National ID number (recommended)"
        )
    }
}

@Composable
private fun RoomAndDatesCard(
    form: WalkInForm,
    roomsState: WalkInRooms,
    checkOutTime: String,
    wide: Boolean,
    vm: WalkInViewModel
) {
    val scheme = MaterialTheme.colorScheme
    FormCard("Room & Dates") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Select Room *")
            RoomSearchSelect(
                rooms = roomsState.rooms,
                valueRoomId = form.roomId,
                onChange = vm::setRoom,
                modifier = Modifier.fillMaxWidth(),
                loading = roomsState.loading,
                placeholder = ROOM_PLACEHOLDER
            )
            val notice = roomsNotice(roomsState.failed, !roomsState.loading, roomsState.rooms)
            if (notice != null) {
                Text(
                    notice,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (roomsState.failed) scheme.error else scheme.onSurfaceVariant
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Check-In Date & Time *")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsDatePickerField(
                    label = "",
                    value = form.checkInDate,
                    onChange = vm::setCheckInDate,
                    modifier = Modifier.weight(1.4f)
                )
                NbmsTimePickerField(
                    label = "",
                    value = form.checkInTime,
                    onChange = vm::setCheckInTime,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsDatePickerField(
                label = "Check-Out Date *",
                value = form.checkOutDate,
                onChange = vm::setCheckOutDate,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                checkOutCaption(checkOutTime),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant
            )
        }

        TwoUp(
            wide = true,
            first = { m ->
                NbmsDropdown(
                    label = "Adults",
                    options = ADULT_OPTIONS,
                    selected = form.adults,
                    onSelect = vm::setAdults,
                    optionLabel = { it.toString() },
                    modifier = m
                )
            },
            second = { m ->
                NbmsDropdown(
                    label = "Children",
                    options = CHILD_OPTIONS,
                    selected = form.children,
                    onSelect = vm::setChildren,
                    optionLabel = { it.toString() },
                    modifier = m
                )
            }
        )
    }
}

@Composable
private fun PaymentCard(form: WalkInForm, room: Room?, symbol: String, vm: WalkInViewModel) {
    val scheme = MaterialTheme.colorScheme
    val checkIn = form.checkInDate
    val checkOut = form.checkOutDate
    val nights = remember(checkIn, checkOut) { if (checkIn != null && checkOut != null) stayNights(checkIn, checkOut) else 1 }

    FormCard("Payment") {
        if (room != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceVariant)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    priceLine(room.price, nights, symbol),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Format.currency(stayTotal(room.price, nights), symbol),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsDropdown(
                label = "Payment Option *",
                options = PaymentOption.entries,
                selected = form.paymentOption,
                onSelect = vm::setPaymentOption,
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
            Text(form.paymentOption.help, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
        }

        if (form.paymentOption == PaymentOption.PAY_AT_CHECKIN) {
            NbmsDropdown(
                label = "Payment Method",
                options = PaymentMethod.entries,
                selected = form.paymentMethod,
                onSelect = vm::setPaymentMethod,
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
        }

        NbmsTextField(
            value = form.notes,
            onValueChange = vm::setNotes,
            label = "Notes",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Optional notes",
            singleLine = false
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

// ---- Submit button ---------------------------------------------------------------------------------

/** Full width, large, gold. While busy it shows a spinner and "Processing…" and cannot be tapped. */
@Composable
private fun SubmitButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (busy) {
        val nbms = MaterialTheme.nbms
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.5f)
                .heightIn(min = 40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(nbms.gold)
                .padding(horizontal = 32.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = nbms.goldForeground, strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(BUSY_TEXT, style = MaterialTheme.typography.labelLarge, color = nbms.goldForeground, maxLines = 1)
        }
    } else {
        NbmsButton(
            text = SUBMIT_TEXT,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Gold,
            size = ButtonSize.Lg,
            enabled = enabled,
            leadingIcon = Icons.Outlined.PersonAdd
        )
    }
}

// ---- Success screen --------------------------------------------------------------------------------

@Composable
private fun SuccessPanel(
    success: WalkInSuccess,
    symbol: String,
    zoneId: String,
    usesPin: Boolean,
    onRegisterAnother: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
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
            Text("Walk-In Registered!", style = nbmsPageTitleStyle(), color = scheme.onBackground, textAlign = TextAlign.Center)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append(success.guestName) }
                    append(" is now checked in to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append("Room ${success.roomNumber}") }
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium,
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
                SummaryRow("Check-Out") {
                    Text(
                        Format.dateTime(success.checkOut, zoneOrLagos(zoneId)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface
                    )
                }
                if (success.paidNow) {
                    SummaryRow("Total Paid") {
                        Text(
                            Format.currency(success.total, symbol),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface
                        )
                    }
                } else {
                    SummaryRow("Payment") {
                        Text(
                            "${Format.currency(success.total, symbol)} due at check-out",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = OrangeDue
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            if (usesPin) {
                Text(
                    PIN_ENDING_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                NbmsButton(
                    text = "Register Another Walk-In",
                    onClick = onRegisterAnother,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}
