package com.westly.nbms.features.bookings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.Avatar
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant

/** "Guest Profile": who the guest is and every stay of theirs, newest first. */
@Composable
fun GuestProfileDialog(guest: Guest, stays: List<Booking>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsDialog(title = "Guest Profile", onDismiss = onDismiss, onConfirm = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name = guest.name, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    guest.name.ifBlank { "Guest" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                NbmsBadge(text = stayCountText(stays.size), tone = BadgeTone.Outline)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!guest.email.isNullOrBlank()) InfoRow(NbmsIcons.Mail, guest.email)
            if (!guest.phone.isNullOrBlank()) InfoRow(NbmsIcons.Phone, Format.phone(guest.phone))
            if (!guest.nationality.isNullOrBlank()) InfoRow(NbmsIcons.Globe, guest.nationality)
            if (!guest.idDocumentRef.isNullOrBlank()) {
                Text("ID: ${guest.idDocumentRef}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stay History", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            if (stays.isEmpty()) {
                Text("No stays recorded yet.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 192.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    stays.forEachIndexed { index, b ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                        StayRow(b)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StayRow(b: Booking) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Room ${b.roomNumber}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurface
            )
            Text(
                "${Format.dateTime((b.checkInAt ?: b.checkIn).toInstant())} → ${Format.dateTime((b.checkOutAt ?: b.checkOut).toInstant())}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        NbmsBadge(text = statusTitle(b.status), tone = BadgeTone.Outline)
    }
}
