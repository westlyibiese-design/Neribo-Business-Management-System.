package com.westly.nbms.features.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import com.westly.nbms.features.users.models.AuditLogEntry
import com.westly.nbms.features.users.models.roleLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val ALL_COLLECTIONS = "All Collections"

/** Audit Log: who did what, newest first, with search, a collection filter and CSV export. */
@Composable
fun AuditLogScreen(
    @Suppress("UNUSED_PARAMETER") session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: AuditLogViewModel = hiltViewModel()
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export: () -> Unit = {
        val (csv, fileName) = vm.csvForExport()
        scope.launch {
            try {
                AuditCsvExporter.share(context, csv, fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.exportFailed()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(NbmsIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                if (wide) {
                    PageHeader(title = "Audit Log", subtitle = entryCountText(filtered.size), modifier = Modifier.weight(1f)) {
                        NbmsButton(
                            text = "Export CSV",
                            onClick = export,
                            variant = ButtonVariant.Outline,
                            enabled = filtered.isNotEmpty(),
                            leadingIcon = NbmsIcons.Download
                        )
                    }
                } else {
                    PageHeader(title = "Audit Log", subtitle = entryCountText(filtered.size), modifier = Modifier.weight(1f))
                }
            }
            if (!wide) {
                NbmsButton(
                    text = "Export CSV",
                    onClick = export,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline,
                    enabled = filtered.isNotEmpty(),
                    leadingIcon = NbmsIcons.Download
                )
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    SearchBar(
                        value = search,
                        onValueChange = vm::onSearch,
                        placeholder = "Search actions, users…",
                        modifier = Modifier.weight(2f)
                    )
                    CollectionDropdown(collections, collection, vm::onCollection, Modifier.weight(1f))
                }
            } else {
                SearchBar(value = search, onValueChange = vm::onSearch, placeholder = "Search actions, users…")
                CollectionDropdown(collections, collection, vm::onCollection, Modifier.fillMaxWidth())
            }

            when (val r = entries) {
                is Resource.Loading -> LoadingState()
                is Resource.Error -> ErrorState("We couldn't load the audit log.", onRetry = vm::retry)
                is Resource.Success -> {
                    when {
                        r.data.isEmpty() -> EmptyState(
                            icon = NbmsIcons.History,
                            title = "No audit log entries yet",
                            message = "Actions taken in the app will be recorded here."
                        )
                        filtered.isEmpty() -> EmptyState(
                            icon = NbmsIcons.Search,
                            title = "No matching entries",
                            message = "Try a different search or collection."
                        )
                        else -> {
                            val shown = filtered.take(AUDIT_PAGE_LIMIT)
                            if (wide) AuditTable(shown) else AuditCards(shown)
                            if (filtered.size > AUDIT_PAGE_LIMIT) {
                                Text(
                                    "Showing latest $AUDIT_PAGE_LIMIT",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionDropdown(
    collections: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier
) {
    NbmsDropdown(
        label = "Collection",
        options = listOf(ALL_COLLECTIONS) + collections,
        selected = selected ?: ALL_COLLECTIONS,
        onSelect = { onSelect(if (it == ALL_COLLECTIONS) null else it) },
        optionLabel = { it },
        modifier = modifier
    )
}

// ---- Phone: one card per entry ----------------------------------------------------------------

@Composable
private fun AuditCards(entries: List<AuditLogEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { e ->
            NbmsCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill(e.action)
                        Text(
                            timeText(e),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                    }
                    UserCell(e)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.collection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        DocIdText(e.documentId)
                    }
                }
            }
        }
    }
}

// ---- Tablet: a table ---------------------------------------------------------------------------

@Composable
private fun AuditTable(entries: List<AuditLogEntry>) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeaderCell("Timestamp", Modifier.weight(1.4f))
                HeaderCell("User", Modifier.weight(1.4f))
                HeaderCell("Action", Modifier.weight(1.4f))
                HeaderCell("Collection", Modifier.weight(1f))
                HeaderCell("Document", Modifier.weight(1.2f))
            }
            entries.forEach { e ->
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(timeText(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.4f))
                    Column(Modifier.weight(1.4f)) { UserCell(e) }
                    Row(Modifier.weight(1.4f)) { ActionPill(e.action) }
                    Text(e.collection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Row(Modifier.weight(1.2f)) { DocIdText(e.documentId) }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Pieces --------------------------------------------------------------------------------------

private fun timeText(e: AuditLogEntry): String = Format.dateTime(e.timestamp.toInstant())

@Composable
private fun UserCell(e: AuditLogEntry) {
    Column {
        Text(
            e.userName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            e.roleLabel(),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DocIdText(documentId: String) {
    Text(
        shortDocId(documentId),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun ActionPill(action: String) {
    NbmsPill(action, pillColorsFor(toneForAction(action)))
}

/** Maps the action colour family onto the Westly status/role palette (light and dark variants included). */
@Composable
private fun pillColorsFor(tone: AuditTone): PillColors {
    val nbms = MaterialTheme.nbms
    return when (tone) {
        AuditTone.GREEN -> nbms.statusPill("available")
        AuditTone.BLUE -> nbms.statusPill("reserved")
        AuditTone.TEAL -> nbms.rolePill("housekeeping")
        AuditTone.GRAY -> nbms.statusPill("out_of_service")
        AuditTone.PURPLE -> nbms.rolePill("super_admin")
        AuditTone.RED -> nbms.statusPill("occupied")
        AuditTone.YELLOW -> nbms.statusPill("cleaning")
        AuditTone.ORANGE -> nbms.statusPill("maintenance")
        AuditTone.MUTED -> PillColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
