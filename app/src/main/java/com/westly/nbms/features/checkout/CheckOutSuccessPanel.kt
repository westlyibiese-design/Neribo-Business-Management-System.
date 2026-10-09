package com.westly.nbms.features.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.util.Format
import kotlinx.datetime.TimeZone

/** "Check-Out Complete!": the totals, how early or late, Share Receipt and Check Out Another Guest. */
@Composable
internal fun CheckOutSuccessPanel(
    success: CheckOutSuccess,
    symbol: String,
    zone: TimeZone,
    usesPin: Boolean,
    onShare: () -> Unit,
    onAnother: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val s = success.summary

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
                    .background(nbms.infoContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(NbmsIcons.CheckCircle, contentDescription = null, tint = nbms.info, modifier = Modifier.size(40.dp))
            }
            Text("Check-Out Complete!", style = nbmsPageTitleStyle(), color = scheme.onBackground, textAlign = TextAlign.Center)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append(success.guestName) }
                    append(" checked out of ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)) { append("Room ${success.roomNumber}") }
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Room has been queued for cleaning.",
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
                SummaryRow("Total Stay Cost") { Strong(Format.currency(s.finalAmount, symbol)) }
                SummaryRow("Charged at Check-Out") {
                    if (s.amountToCharge > 0.0) Strong(Format.currency(s.amountToCharge, symbol))
                    else Text("None — paid at check-in", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                SummaryRow("Method") {
                    Text(methodLabel(s.paymentMethodKey), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                RowDivider()
                SummaryRow("Scheduled Checkout") {
                    Text(Format.dateTime(s.scheduledAt, zone), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
                SummaryRow("Actual Checkout") { Strong(Format.dateTime(s.actualAt, zone)) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    val colors = when (s.timing.kind) {
                        TimingKind.EARLY -> nbms.statusPill("reserved")
                        TimingKind.LATE -> nbms.statusPill("occupied")
                        TimingKind.ON_TIME -> nbms.statusPill("available")
                    }
                    StatusPill(
                        text = timingPillText(s.timing),
                        colors = colors,
                        icon = if (s.timing.kind == TimingKind.ON_TIME) Icons.Outlined.AlarmOn else NbmsIcons.Clock
                    )
                }
            }

            NbmsButton(
                text = "Share Receipt",
                onClick = onShare,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline,
                leadingIcon = Icons.Outlined.Share
            )
            if (usesPin) {
                Text(
                    MSG_PIN_ENDING,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                NbmsButton(
                    text = "Check Out Another Guest",
                    onClick = onAnother,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun Strong(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
}
