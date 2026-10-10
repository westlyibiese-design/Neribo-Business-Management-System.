package com.westly.nbms.features.opslog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

/**
 * The detail sheet of one item. [item] is the live item, so a status change shows at once.
 * The Update Status controls show only when [canManageStatus] (Super Admin and Manager).
 */
@Composable
fun LostFoundDetailSheet(
    item: LostFoundItem,
    state: DetailUiState,
    canManageStatus: Boolean,
    onNote: (String) -> Unit,
    onMark: (ItemStatus) -> Unit,
    onClose: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val updating = state.updating != null
    NbmsBottomSheet(onDismiss = onClose, title = item.itemName) {
        Text("Room ${item.roomNumber}", fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)

        item.photoUrl?.let { url -> ItemPhoto(url, item.itemName) }

        FactsGrid(item)

        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Created by ${item.createdByName} · ${lostFoundDateTime(item.createdAt)}",
                fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
            )
            if (item.updatedByName != null) {
                Text(
                    "Last updated by ${item.updatedByName} · ${lostFoundDateTime(item.updatedAt)}",
                    fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant
                )
            }
        }

        if (canManageStatus) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Update Status")
                NbmsTextField(
                    value = state.note,
                    onValueChange = onNote,
                    label = "",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Optional note about this change",
                    enabled = !updating
                )
                lostFoundMarkTargets(item.status).forEach { target ->
                    NbmsButton(
                        text = lostFoundMarkLabel(target),
                        onClick = { onMark(target) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = ButtonVariant.Outline,
                        loading = state.updating == target,
                        enabled = !updating,
                        leadingIcon = target.icon()
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NbmsIcons.Clock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                SectionHeading("Item History")
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lostFoundHistoryNewestFirst(item.statusHistory).forEach { entry -> HistoryRow(entry) }
            }
        }

        NbmsButton(text = "Close", onClick = onClose, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
    }
}

/** Full-width, 160dp, rounded; hidden if it fails to load. */
@Composable
private fun ItemPhoto(url: String, itemName: String) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    AsyncImage(
        model = url,
        contentDescription = itemName,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp))
    )
}

/** Two-column facts: Status, Found At, Found By, Room, then Description and Notes full width when present. */
@Composable
private fun FactsGrid(item: LostFoundItem) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Fact("Status", Modifier.weight(1f)) { LostFoundStatusPill(item.status) }
            Fact("Found At", Modifier.weight(1f)) { FactText(lostFoundDateTime(item.foundAt)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Fact("Found By", Modifier.weight(1f)) { FactText(item.foundByName) }
            Fact("Room", Modifier.weight(1f)) { FactText("Room ${item.roomNumber}") }
        }
        item.description?.let { Fact("Description", Modifier.fillMaxWidth()) { FactText(it) } }
        item.notes?.let { Fact("Notes", Modifier.fillMaxWidth()) { FactText(it) } }
    }
}

@Composable
private fun Fact(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(), fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun FactText(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** "{Status label} by {name} · {date-time}" (status in medium weight) and the note below in muted text. */
@Composable
private fun HistoryRow(entry: StatusHistoryEntry) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(entry.statusLabel) }
                append(" by ${entry.changedByName} · ${lostFoundDateTime(entry.changedAt)}")
            },
            fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface
        )
        entry.note?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant) }
    }
}
