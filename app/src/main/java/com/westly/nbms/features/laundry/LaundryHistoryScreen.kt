package com.westly.nbms.features.laundry

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import kotlinx.datetime.TimeZone
import java.io.File
import com.westly.nbms.core.util.Branding

private val LAUNDRY_HISTORY_TABLET_WIDTH = 840.dp

/**
 * Laundry History (`laundry/history`): laundry requests newest first, with search, status and month filters, a total and Export CSV.
 * Read-only: nothing on this page changes a request.
 */
@Composable
fun LaundryHistoryScreen(session: SessionState.SignedIn, vm: LaundryHistoryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { laundryHistoryZone(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    val ready = view as? LaundryHistoryView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= LAUNDRY_HISTORY_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("Laundry History", ready?.let { laundryHistorySubtitle(it.rows, symbol) }) {
                NbmsButton(
                    text = "Export CSV",
                    onClick = {
                        vm.exportFile()?.let { file ->
                            shareLaundryHistoryCsv(context, file).exceptionOrNull()?.let(vm::shareFailed)
                        }
                    },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    enabled = ready != null,
                    leadingIcon = NbmsIcons.Download
                )
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchBar(filters.search, vm::setSearch, "Search guest, room, valet…", Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LaundryHistoryStatusField(filters.status, vm::setStatus, Modifier.weight(1f))
                    LaundryHistoryMonthField(filters.month, vm::setMonth, zone, Modifier.weight(1f))
                }
            }

            when (val v = view) {
                is LaundryHistoryView.Loading -> LoadingState()
                is LaundryHistoryView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is LaundryHistoryView.Ready ->
                    if (v.rows.isEmpty()) {
                        EmptyState(NbmsIcons.Laundry, "No laundry requests found", "")
                    } else if (wide) {
                        LaundryHistoryTable(v.rows, symbol, tz)
                    } else {
                        PagedList(v.rows, key = { it.id }) { request -> LaundryHistoryCard(request, symbol, tz) }
                    }
            }
        }
    }
}

@Composable
private fun LaundryHistoryStatusField(status: String, onStatus: (String) -> Unit, modifier: Modifier) {
    val options = remember { laundryHistoryStatusOptions() }
    NbmsDropdown(
        label = "Status",
        options = options,
        selected = options.firstOrNull { it.first == status },
        onSelect = { onStatus(it.first) },
        optionLabel = { it.second },
        modifier = modifier
    )
}

/** Month picker as a dropdown: "All months" (blank, shows everything) plus the last 24 months; the current month is the default. */
@Composable
private fun LaundryHistoryMonthField(month: String, onMonth: (String) -> Unit, zone: java.time.ZoneId, modifier: Modifier) {
    val options = remember(zone) { laundryHistoryMonthOptions(zone) }
    val shown = if (month in options) options else listOf(month) + options
    NbmsDropdown(
        label = "Month",
        options = shown,
        selected = month,
        onSelect = onMonth,
        optionLabel = { if (it.isBlank()) "All months" else laundryHistoryMonthLabel(it) },
        modifier = modifier
    )
}

// ── pieces shared by the card and the table row ──

@Composable
private fun LaundryHistoryGuestBlock(request: LaundryRequest) {
    Column {
        Text(guestOrRoom(request), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        laundryHistoryRoomLine(request)?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── phone card ──

@Composable
private fun LaundryHistoryCard(request: LaundryRequest, symbol: String, tz: TimeZone) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(laundryHistoryDateTime(request.createdAt, tz), fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                LaundryStatusPill(request.status)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { LaundryHistoryGuestBlock(request) }
                Text(Format.currency(request.charge, symbol), fontWeight = FontWeight.Bold)
            }
            Text(laundryHistoryItemsText(request), fontSize = 12.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Logged by ${request.laundryValetName}", fontSize = 12.sp, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                PaymentBadge(request.paymentStatus)
            }
        }
    }
}

// ── tablet table ──

@Composable
private fun RowScope.LaundryHistoryHeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align, modifier = Modifier.weight(weight)
    )
}

@Composable
private fun LaundryHistoryTable(rows: List<LaundryRequest>, symbol: String, tz: TimeZone) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                LaundryHistoryHeaderCell("Date", 1.3f); LaundryHistoryHeaderCell("Guest / Room", 1.3f)
                LaundryHistoryHeaderCell("Items", 2f); LaundryHistoryHeaderCell("Logged By", 1.1f)
                LaundryHistoryHeaderCell("Charge", 1f, TextAlign.End); LaundryHistoryHeaderCell("Payment", 0.9f)
                LaundryHistoryHeaderCell("Status", 1.4f)
            }
            HorizontalDivider()
            PagedList(rows, key = { it.id }, modifier = Modifier.padding(top = 8.dp)) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        laundryHistoryDateTime(r.createdAt, tz), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.3f)
                    )
                    Column(Modifier.weight(1.3f)) { LaundryHistoryGuestBlock(r) }
                    Text(
                        laundryHistoryItemsText(r), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f)
                    )
                    Text(
                        r.laundryValetName, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f)
                    )
                    Text(
                        Format.currency(r.charge, symbol), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                    )
                    Row(Modifier.weight(0.9f)) { PaymentBadge(r.paymentStatus) }
                    Row(Modifier.weight(1.4f)) { LaundryStatusPill(r.status) }
                }
            }
        }
    }
}

// ── Export CSV: write the file to the cache, then open the Android share sheet ──

/**
 * The Phase 8 FileProvider (authority `${applicationId}.fileprovider`) exposes only the cache folder "exports/", so the `shared/` folder
 * of this export lives INSIDE it. A file anywhere else in the cache cannot be handed to the share sheet.
 */
private const val LAUNDRY_HISTORY_SHARE_FOLDER = "exports/shared"

/** Private to Laundry History (Part 22B). Never throws: a problem comes back as a failed [Result]. */
private fun shareLaundryHistoryCsv(context: Context, export: LaundryHistoryExport): Result<Unit> = runCatching {
    val safeName = File(export.fileName).name
    require(safeName.isNotBlank()) { "File name is empty" }

    val dir = File(context.cacheDir, LAUNDRY_HISTORY_SHARE_FOLDER).apply { mkdirs() }
    val file = File(dir, safeName)
    file.writeBytes(Branding.csvWithCopyright(export.content).toByteArray(Charsets.UTF_8))

    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(safeName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, safeName).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
