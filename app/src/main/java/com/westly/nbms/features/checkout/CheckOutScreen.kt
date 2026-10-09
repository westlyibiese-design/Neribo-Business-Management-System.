package com.westly.nbms.features.checkout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.bookings.Booking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

private val TABLET_WIDTH = 600.dp

/** The Check Out page: the checked-in guests, the confirm dialog, then the success screen. */
@Composable
fun CheckOutScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: CheckOutViewModel = hiltViewModel()
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val guests by vm.guests.collectAsStateWithLifecycle()
    val officialTime by vm.checkOutTime.collectAsStateWithLifecycle()
    val zone = remember(session.business.timezone) { zoneOrLagos(session.business.timezone) }
    val symbol = session.business.currencySymbol
    val success = ui.success

    // While saving: keep the screen awake and ignore Back.
    KeepScreenOn(ui.busy)
    BackHandler(enabled = ui.busy) { }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= TABLET_WIDTH
        if (success != null) {
            CheckOutSuccessPanel(
                success = success,
                symbol = symbol,
                zone = zone,
                usesPin = session.user.usesPin,
                onShare = {
                    scope.launch {
                        try {
                            ReceiptSharer.share(context, success.receipt)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            vm.receiptFailed()
                        }
                    }
                },
                onAnother = vm::checkOutAnother
            )
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                PageHeader(
                    title = "Check Out",
                    subtitle = if (!guests.loading && !guests.failed) guestCountText(guests.guests.size) else null
                )
                when {
                    guests.loading -> LoadingState()
                    guests.failed -> ErrorState(MSG_LOAD_ERROR, onRetry = vm::retry)
                    else -> GuestList(
                        guests = guests.guests,
                        filters = ui.filters,
                        officialTime = officialTime,
                        zone = zone,
                        symbol = symbol,
                        wide = wide,
                        vm = vm
                    )
                }
            }
        }
    }

    val selected = guests.guests.firstOrNull { it.id == ui.selectedId }
    if (selected != null && success == null) {
        CheckOutConfirmDialog(
            booking = selected,
            draft = ui.draft,
            busy = ui.busy,
            slow = ui.slow,
            officialTime = officialTime,
            zone = zone,
            symbol = symbol,
            vm = vm
        )
    }
}

// ---- The list --------------------------------------------------------------------------------------

@Composable
private fun GuestList(
    guests: List<Booking>,
    filters: CheckOutFilters,
    officialTime: String,
    zone: TimeZone,
    symbol: String,
    wide: Boolean,
    vm: CheckOutViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchBar(value = filters.query, onValueChange = vm::setQuery, placeholder = SEARCH_PLACEHOLDER)
        DueFilterBar(filters, vm)
    }

    val today = Clock.System.now().localDate(zone)
    val visible = remember(guests, filters, officialTime, zone, today) {
        filterGuests(guests, filters, officialTime, zone, today)
    }
    if (visible.isEmpty()) {
        EmptyGuests(
            title = if (guests.isEmpty() && !filters.active) MSG_NO_GUESTS else MSG_NO_MATCH,
            showClear = filters.active,
            onClear = vm::clearFilters
        )
    } else {
        PagedList(items = visible, key = { it.id }) { booking ->
            GuestCard(booking, officialTime, zone, symbol, wide, onClick = { vm.open(booking) })
        }
    }
}

@Composable
private fun DueFilterBar(filters: CheckOutFilters, vm: CheckOutViewModel) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.FilterList, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text("Filter by due checkout date", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DueFilter.entries.forEach { option ->
                DueChip(option.label, selected = filters.due == option, onClick = { vm.setDue(option) })
            }
        }
        when (filters.due) {
            DueFilter.SPECIFIC -> NbmsDatePickerField(
                label = "",
                value = filters.specificDate,
                onChange = vm::setSpecificDate,
                modifier = Modifier.fillMaxWidth()
            )
            DueFilter.RANGE -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NbmsDatePickerField(label = "", value = filters.rangeStart, onChange = vm::setRangeStart, modifier = Modifier.weight(1f))
                Text("to", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                NbmsDatePickerField(label = "", value = filters.rangeEnd, onChange = vm::setRangeEnd, modifier = Modifier.weight(1f))
            }
            else -> Unit
        }
    }
}

/** A fully rounded 32dp chip: filled primary when selected, outlined otherwise. */
@Composable
private fun DueChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else MaterialTheme.nbms.inputBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimary else scheme.onBackground,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyGuests(title: String, showClear: Boolean, onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.LogOut, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(30.dp).alpha(0.3f))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        if (showClear) {
            NbmsButton(text = "Clear filters", onClick = onClear, variant = ButtonVariant.Link, size = ButtonSize.Sm)
        }
    }
}

// ---- One guest -------------------------------------------------------------------------------------

@Composable
private fun GuestCard(
    booking: Booking,
    officialTime: String,
    zone: TimeZone,
    symbol: String,
    wide: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val scheduled = scheduledCheckOutAt(booking, officialTime, zone)
    val paid = isRoomPaid(booking.roomPaymentStatus)

    val identity: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(nbms.infoContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    guestInitial(booking.guestName),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = nbms.info
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    booking.guestName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    booking.guestEmail.orEmpty(),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    val details: @Composable (Modifier, Alignment.Horizontal) -> Unit = { m, align ->
        Column(m, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconText(NbmsIcons.Bed, "Room ${booking.roomNumber}", bold = true)
            IconText(NbmsIcons.Calendar, "Scheduled: ${Format.dateTime(scheduled, zone)}", bold = false)
            Text(
                Format.currency(booking.totalAmount, symbol),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            StatusPill(
                text = if (paid) "Paid at Check-In" else "Payment Pending",
                colors = nbms.statusPill(if (paid) "available" else "maintenance")
            )
        }
    }

    NbmsCard(Modifier.fillMaxWidth(), onClick = onClick) {
        if (wide) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                identity(Modifier.weight(1f))
                details(Modifier, Alignment.End)
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                identity(Modifier.fillMaxWidth())
                details(Modifier.fillMaxWidth(), Alignment.Start)
            }
        }
    }
}

@Composable
private fun IconText(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, bold: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        if (bold) {
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
        } else {
            Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
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
