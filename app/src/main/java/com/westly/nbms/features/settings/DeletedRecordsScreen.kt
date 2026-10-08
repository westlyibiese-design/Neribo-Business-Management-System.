package com.westly.nbms.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import com.westly.nbms.features.settings.models.DeletedRecord

/** Deleted Records: review soft-deleted records, restore them or purge them for good. Super Admin only. */
@Composable
fun DeletedRecordsScreen(
    modifier: Modifier = Modifier,
    vm: DeletedRecordsViewModel = hiltViewModel()
) {
    val records by vm.records.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()

    val total = (records as? Resource.Success<List<DeletedRecord>>)?.data?.size

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(NbmsIcons.Archive, contentDescription = null, tint = MaterialTheme.nbms.destructive, modifier = Modifier.size(28.dp))
            PageHeader(
                title = "Deleted Records",
                subtitle = "Review soft-deleted records. Restore to return them, or purge to permanently remove them. All actions are audited.",
                modifier = Modifier.weight(1f)
            )
        }
        if (total != null) NbmsBadge("$total records", BadgeTone.Secondary)

        WarningCard()

        when (val r = records) {
            is Resource.Loading -> LoadingState()
            is Resource.Error -> ErrorState("We couldn't load deleted records.", onRetry = vm::retry)
            is Resource.Success -> {
                if (r.data.isEmpty()) {
                    EmptyState(
                        icon = NbmsIcons.CheckCircle,
                        title = "No Deleted Records",
                        message = "Nothing has been deleted."
                    )
                } else {
                    FilterChips(collections, collection?.takeIf { it in collections }, vm::onCollection)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        filtered.forEach { rec ->
                            RecordCard(
                                rec,
                                onRestore = { vm.ask(rec, ArchiveAction.RESTORE) },
                                onPurge = { vm.ask(rec, ArchiveAction.PURGE) }
                            )
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        val label = collectionLabel(p.record.originalCollection)
        if (p.action == ArchiveAction.RESTORE) {
            NbmsDialog(
                title = "Restore record?",
                onDismiss = vm::cancel,
                confirmText = "Restore",
                onConfirm = vm::confirm,
                loading = processing
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text(
                        "This $label will become active again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            NbmsDialog(
                title = "Permanently delete?",
                onDismiss = vm::cancel,
                confirmText = "Delete permanently",
                onConfirm = vm::confirm,
                destructive = true,
                loading = processing
            ) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = MaterialTheme.nbms.destructive, modifier = Modifier.size(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "This action cannot be undone.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.nbms.destructive
                        )
                        Text(
                            "The $label will be removed forever.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningCard() {
    val nbms = MaterialTheme.nbms
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.background(nbms.destructiveContainer.copy(alpha = 0.5f)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = nbms.destructive, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Super Admin Only", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = nbms.destructive)
                Text(
                    "Purging is irreversible. Restore a record to make it active again, or purge only if the record is confirmed as obsolete. Every action generates an audit log entry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun FilterChips(collections: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Chip("All Collections", selected == null) { onSelect(null) }
        collections.forEach { c -> Chip(collectionLabel(c), selected == c) { onSelect(c) } }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) scheme.primary else scheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun pillColorsFor(tone: CollectionTone): PillColors {
    val nbms = MaterialTheme.nbms
    return when (tone) {
        CollectionTone.BLUE -> nbms.statusPill("reserved")
        CollectionTone.PURPLE -> nbms.rolePill("super_admin")
        CollectionTone.GREEN -> nbms.statusPill("available")
        CollectionTone.RED -> nbms.statusPill("occupied")
        CollectionTone.YELLOW -> nbms.statusPill("cleaning")
        CollectionTone.ORANGE -> nbms.statusPill("maintenance")
        CollectionTone.TEAL -> nbms.rolePill("housekeeping")
        CollectionTone.GRAY -> nbms.statusPill("out_of_service")
    }
}

@Composable
private fun RecordCard(rec: DeletedRecord, onRestore: () -> Unit, onPurge: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsPill(collectionLabel(rec.originalCollection), pillColorsFor(collectionTone(rec.originalCollection)))
            Text(displayLabel(rec), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
            Text(
                "${deletedByText(rec)} · ${Format.dateTime(rec.deletedAt.toInstant())}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            val reason = rec.reason?.trim().orEmpty()
            if (reason.isNotEmpty()) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = scheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NbmsButton("Restore", onRestore, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Icons.Outlined.RestartAlt)
                NbmsButton("Purge", onPurge, variant = ButtonVariant.Destructive, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Trash)
            }
        }
    }
}
