package com.westly.nbms.features.laundry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import kotlinx.datetime.Instant as KInstant

private val LAUNDRY_TABLET_WIDTH = 840.dp
private val LAUNDRY_LARGE_PHONE_WIDTH = 600.dp
private val LAUNDRY_STACK_ACTIONS_BELOW = 420.dp

/** Manage Laundry: the live list of active requests with the status summary strip. */
@Composable
fun LaundryScreen(session: SessionState.SignedIn, vm: LaundryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val chargeTarget by vm.chargeTarget.collectAsStateWithLifecycle()
    val pinEnding by vm.pinEnding.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val ready = view as? LaundryView.Ready
        LaundryHeader(activeCount = ready?.active?.size ?: 0, onNew = vm::openSheet)

        when (val v = view) {
            is LaundryView.Loading -> LoadingState()
            is LaundryView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is LaundryView.Ready -> {
                SummaryStrip(v.counts)
                if (v.active.isEmpty()) {
                    EmptyState(NbmsIcons.CheckCircle, "No active laundry requests", "Log a new request to get started.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        v.active.forEach { request ->
                            RequestCard(
                                request = request,
                                symbol = symbol,
                                busy = request.id in busy,
                                onAdvance = { vm.advance(request) },
                                onTogglePaid = { vm.togglePaid(request) },
                                onCharge = { vm.openCharge(request) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (sheet.open) {
        NewLaundryRequestSheet(
            state = sheet,
            symbol = symbol,
            onChange = vm::updateForm,
            onCancel = vm::closeSheet,
            onSubmit = vm::submit
        )
    }

    chargeTarget?.let { target ->
        ChargeDialog(
            request = target,
            symbol = symbol,
            onSave = { text -> vm.saveCharge(text, symbol) },
            onDismiss = vm::closeCharge
        )
    }

    if (pinEnding) PinSessionEndingOverlay()
}

@Composable
private fun LaundryHeader(activeCount: Int, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            NbmsIcons.Laundry,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("Laundry", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                laundryActiveSubtitle(activeCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NbmsButton(text = "New Request", onClick = onNew, leadingIcon = NbmsIcons.Plus)
    }
}

// ── summary strip ──

@Composable
private fun SummaryStrip(counts: Map<LaundryStatus, Int>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= LAUNDRY_TABLET_WIDTH -> 6
            maxWidth >= LAUNDRY_LARGE_PHONE_WIDTH -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LAUNDRY_SUMMARY_STATUSES.chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { status -> SummaryCard(status, counts[status] ?: 0, Modifier.weight(1f)) }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(status: LaundryStatus, count: Int, modifier: Modifier) {
    val colors = status.pillColors()
    NbmsCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(status.icon(), contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
            Text(count.toString(), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                status.label, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── request card ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestCard(
    request: LaundryRequest,
    symbol: String,
    busy: Boolean,
    onAdvance: () -> Unit,
    onTogglePaid: () -> Unit,
    onCharge: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val colors = request.status.pillColors()
    val next = nextStatus(request.status)
    NbmsCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val stacked = maxWidth < LAUNDRY_STACK_ACTIONS_BELOW
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(colors.container),
                        contentAlignment = Alignment.Center
                    ) { Icon(request.status.icon(), null, Modifier.size(20.dp), tint = colors.content) }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                guestOrRoom(request), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (request.guestName != null && request.roomNumber != null) {
                                Text("Room ${request.roomNumber}", fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LaundryStatusPill(request.status)
                            PaymentBadge(request.paymentStatus, onClick = if (busy) null else onTogglePaid)
                        }
                        laundryItemsLine(request)?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant) }
                        Text(
                            "Logged by ${request.laundryValetName} · ${laundryDateTime(request.createdAt)}",
                            fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
                        )
                    }

                    if (!stacked) {
                        ActionsColumn(request, symbol, busy, next, onAdvance, onCharge, Alignment.End)
                    }
                }
                if (stacked) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ChargeText(request, symbol, busy, onCharge)
                        if (next != null) AdvanceButton(next, busy, onAdvance)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsColumn(
    request: LaundryRequest, symbol: String, busy: Boolean, next: LaundryStatus?,
    onAdvance: () -> Unit, onCharge: () -> Unit, align: Alignment.Horizontal
) {
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChargeText(request, symbol, busy, onCharge)
        if (next != null) AdvanceButton(next, busy, onAdvance)
    }
}

/** The charge as a bold primary amount with a banknote icon. Tap → Update Charge. */
@Composable
private fun ChargeText(request: LaundryRequest, symbol: String, busy: Boolean, onCharge: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = !busy, onClick = onCharge)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(NbmsIcons.Banknote, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            Format.currency(request.charge, symbol),
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
    }
}

/** "Mark {next label}": spinner and disabled while this row is busy. */
@Composable
private fun AdvanceButton(next: LaundryStatus, busy: Boolean, onClick: () -> Unit) {
    NbmsButton(
        text = "Mark ${next.label}",
        onClick = onClick,
        size = ButtonSize.Sm,
        loading = busy,
        enabled = !busy,
        leadingIcon = NbmsIcons.CheckCircle
    )
}

private fun laundryDateTime(at: java.time.Instant?): String =
    Format.dateTime(at?.let { KInstant.fromEpochMilliseconds(it.toEpochMilli()) })

// ── shared-device sign-out overlay ──

/** Full-screen dim layer shown for 2.5 seconds after a PIN user logs or delivers a request, just before the session ends. */
@Composable
private fun PinSessionEndingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("Ending session for security…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}
