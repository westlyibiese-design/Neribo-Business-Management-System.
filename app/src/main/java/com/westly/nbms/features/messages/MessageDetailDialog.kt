package com.westly.nbms.features.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format

/**
 * One enquiry in full: name, received time, e-mail and phone (both tappable), the message text,
 * the reply status buttons, Reply by Email and Remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageDetailDialog(
    message: InboxMessage,
    busy: Boolean,
    onSetReplyStatus: (ReplyStatus) -> Unit,
    onReplyByEmail: () -> Unit,
    onEmail: () -> Unit,
    onPhone: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    NbmsDialog(
        title = message.subject?.takeIf { it.isNotBlank() } ?: "Website Enquiry",
        onDismiss = onDismiss
    ) {
        Field("NAME") {
            Text(message.name.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
        Field("RECEIVED") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(NbmsIcons.Clock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(Format.dateTime(message.createdAt), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
        }
        Field("EMAIL") {
            Text(
                message.email.ifBlank { "—" },
                modifier = if (message.email.isNotBlank()) Modifier.clickable(role = Role.Button, onClick = onEmail) else Modifier,
                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                color = scheme.primary
            )
        }
        if (!message.phone.isNullOrBlank()) {
            Field("PHONE") {
                Text(
                    message.phone,
                    modifier = Modifier.clickable(role = Role.Button) { onPhone(message.phone) },
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    color = scheme.primary
                )
            }
        }
        Field("MESSAGE") {
            Text(
                message.message,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceVariant)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reply status:", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplyStatus.entries.forEach { status ->
                    NbmsButton(
                        text = status.label,
                        onClick = { if (status != message.replyStatus) onSetReplyStatus(status) },
                        variant = if (status == message.replyStatus) ButtonVariant.Default else ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        enabled = !busy
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Reply by Email",
                onClick = onReplyByEmail,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.AutoMirrored.Outlined.Reply
            )
            RemoveButton(enabled = !busy, onClick = onRemove)
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

/** Ghost button with destructive (red) text and a trash icon. */
@Composable
private fun RemoveButton(enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.nbms.destructive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(NbmsIcons.Trash, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            "Remove",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}
