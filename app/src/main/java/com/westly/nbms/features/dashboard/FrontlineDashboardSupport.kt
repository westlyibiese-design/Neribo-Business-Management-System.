package com.westly.nbms.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.housekeeping.TaskPriority
import java.time.Instant

// Small helpers shared by the four front-line dashboards of Phase 34A2 (Receptionist, Staff, Waiter, Housekeeping).
// No dashboard number is worked out here: every figure comes from FrontlineMath.

internal const val ROUTE_CHECKIN = "checkin"
internal const val ROUTE_CHECKOUT = "checkout"
internal const val ROUTE_MESSAGES = "messages"
internal const val ROUTE_SALES_NEW = "sales/new"
internal const val ROUTE_SALES_HISTORY = "sales/history"
internal const val ROUTE_ORDERS_NEW = "orders/new"
internal const val ROUTE_ORDERS_HISTORY = "orders/history"

/** Footnote of the Staff and Waiter dashboards. */
internal const val OWN_ACTIVITY_NOTE = "Amounts here are your own sales activity — revenue counts once the Accountant approves it."

internal const val MSG_RECEPTION_LOAD_FAILED = "We couldn't load today's bookings."
internal const val MSG_ORDERS_LOAD_FAILED = "We couldn't load your orders."
internal const val MSG_QUEUE_LOAD_FAILED = "We couldn't load your queue."

/** Largest number of rows a Receptionist arrivals / departures card shows. */
internal const val RECEPTION_LIST_COUNT = 6

/** The signed-in person's id; blank when signed out (the repository then reads nothing rather than someone else's records). */
internal fun uidOfSession(session: SessionManager): String =
    (session.state.value as? SessionState.SignedIn)?.user?.uid.orEmpty()

/** Now, or the real clock until the first tick of the dashboard clock has arrived. */
internal fun nowOrReal(now: Instant): Instant = if (now == Instant.EPOCH) Instant.now() else now

/** "Room 101"; a dash when the room number is missing. */
internal fun roomLabel(roomNumber: String?): String = "Room ${roomNumber?.takeIf { it.isNotBlank() } ?: "—"}"

/** Guest name of a booking row, "Guest" when blank. */
internal fun guestLabel(name: String?): String = name?.takeIf { it.isNotBlank() } ?: "Guest"

/** Phase 23's priority for a stored key; null when the key is missing or unknown (the row then shows no badge). */
internal fun priorityOf(key: String?): TaskPriority? = TaskPriority.entries.firstOrNull { it.key == key }

@Composable
internal fun orangeTone(): Color = if (MaterialTheme.nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)

@Composable
internal fun yellowTone(): Color = if (MaterialTheme.nbms.isDark) Color(0xFFFACC15) else Color(0xFFCA8A04)

@Composable
internal fun redTone(): Color = if (MaterialTheme.nbms.isDark) Color(0xFFF87171) else Color(0xFFDC2626)

@Composable
internal fun purpleTone(): Color = if (MaterialTheme.nbms.isDark) Color(0xFFC084FC) else Color(0xFF9333EA)

/**
 * Loading / error wrapper with the dashboard's own error text. A failure wins over loading; the content is drawn straight into
 * the caller's column.
 */
@Composable
internal fun FrontlineStateHost(
    loading: Boolean,
    failed: Boolean,
    errorMessage: String,
    onRetry: () -> Unit,
    content: @Composable () -> Unit
) {
    when {
        failed -> ErrorState(message = errorMessage, onRetry = onRetry)
        loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
        else -> content()
    }
}

/** Tinted, centred stat card: the number (30sp, 20sp when long such as an amount) in [tone] over a 12sp label. */
@Composable
internal fun FrontlineStatCard(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    NbmsCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tone.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                fontSize = if (value.length > 6) 20.sp else 30.sp,
                lineHeight = if (value.length > 6) 28.sp else 36.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Card with a coloured heading icon, a 14sp semibold title, optional trailing content in the heading row, then [content]. */
@Composable
internal fun FrontlineSectionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    NbmsCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (headerTrailing != null) headerTrailing()
            }
            content()
        }
    }
}

/** A line of muted 14sp text used by the empty states of the cards. */
@Composable
internal fun FrontlineEmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

/** 11sp muted footnote. */
@Composable
internal fun FrontlineFootnote(text: String) {
    Text(text, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Tinted rounded box used for the Low Stock card and the yellow queue rows. */
internal fun Modifier.tintedBox(tone: Color, cornerDp: Int = 12): Modifier =
    this.clip(RoundedCornerShape(cornerDp.dp))
        .background(tone.copy(alpha = 0.10f))
        .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(cornerDp.dp))
