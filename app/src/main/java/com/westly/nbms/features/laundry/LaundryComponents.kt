package com.westly.nbms.features.laundry

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms

// Shared laundry components (Part interface, section 2.0). Part 22B imports these exactly as written and never copies or edits them.

/** Light text / background and dark text / background of one status pill (Tailwind palette, section 2.3.1). */
private class StatusLook(val lightBg: Long, val lightText: Long, val darkBg: Long, val darkText: Long)

private fun LaundryStatus.look(): StatusLook = when (this) {
    LaundryStatus.RECEIVED -> StatusLook(0xFFF1F5F9, 0xFF334155, 0x4D334155, 0xFFCBD5E1)      // slate
    LaundryStatus.WASHING -> StatusLook(0xFFDBEAFE, 0xFF1E40AF, 0x4D1E3A8A, 0xFF60A5FA)       // blue
    LaundryStatus.DRYING -> StatusLook(0xFFCFFAFE, 0xFF155E75, 0x4D164E63, 0xFF22D3EE)        // cyan
    LaundryStatus.IRONING -> StatusLook(0xFFF3E8FF, 0xFF6B21A8, 0x4D581C87, 0xFFC084FC)       // purple
    LaundryStatus.READY -> StatusLook(0xFFFEF3C7, 0xFF92400E, 0x4D78350F, 0xFFFBBF24)         // amber
    LaundryStatus.DELIVERED -> StatusLook(0xFFDCFCE7, 0xFF166534, 0x4D14532D, 0xFF4ADE80)     // green
    LaundryStatus.CANCELLED -> StatusLook(0xFFFEE2E2, 0xFF991B1B, 0x4D7F1D1D, 0xFFF87171)     // red
}

/** Pill colours of a status for the current theme (also used for the icon tile and the summary cards). */
@Composable
internal fun LaundryStatus.pillColors(): PillColors {
    val l = look()
    return if (MaterialTheme.nbms.isDark) PillColors(Color(l.darkBg), Color(l.darkText))
    else PillColors(Color(l.lightBg), Color(l.lightText))
}

/** Coloured status pill, light and dark. */
@Composable
fun LaundryStatusPill(status: LaundryStatus, modifier: Modifier = Modifier) {
    NbmsPill(text = status.label, colors = status.pillColors(), modifier = modifier)
}

/** Paid = filled primary, Unpaid = outlined. Tappable only when [onClick] is given. */
@Composable
fun PaymentBadge(status: PaymentStatus, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tap = if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick) else Modifier
    NbmsBadge(
        text = status.label,
        tone = if (status == PaymentStatus.PAID) BadgeTone.Default else BadgeTone.Outline,
        modifier = modifier.then(tap)
    )
}

/** Package-check, droplets, wind, sparkles, clock, truck (Cancelled gets the cross). */
fun LaundryStatus.icon(): ImageVector = when (this) {
    LaundryStatus.RECEIVED -> NbmsIcons.Package
    LaundryStatus.WASHING -> Icons.Outlined.WaterDrop
    LaundryStatus.DRYING -> Icons.Outlined.Air
    LaundryStatus.IRONING -> NbmsIcons.Sparkles
    LaundryStatus.READY -> NbmsIcons.Clock
    LaundryStatus.DELIVERED -> Icons.Outlined.LocalShipping
    LaundryStatus.CANCELLED -> NbmsIcons.XCircle
}

/** Guest name, else "Room {n}", else "Guest". */
fun guestOrRoom(r: LaundryRequest): String = guestOrRoomLabel(r.guestName, r.roomNumber)
