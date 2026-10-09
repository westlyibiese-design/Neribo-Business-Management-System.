package com.westly.nbms.features.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import kotlinx.datetime.TimeZone

private val BAR_HISTORY_TABLET_WIDTH = 840.dp

/** Sales History (`bar/sales-history`): bar sales newest first, with search, status and month filters, and Mark Served. */
@Composable
fun BarSalesHistoryScreen(session: SessionState.SignedIn, vm: BarSalesHistoryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol
    val role = session.user.role
    val zone = remember(session.business.timezone) { barHistoryZone(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    val ready = view as? BarHistoryView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= BAR_HISTORY_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("Bar Sales History", ready?.let { barHistorySubtitle(it.rows, symbol) })

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchBar(filters.search, vm::setSearch, "Search attendant, guest, room…", Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusField(filters.status, vm::setStatus, Modifier.weight(1f))
                    MonthField(filters.month, vm::setMonth, zone, Modifier.weight(1f))
                }
            }

            when (val v = view) {
                is BarHistoryView.Loading -> LoadingState()
                is BarHistoryView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is BarHistoryView.Ready ->
                    if (v.rows.isEmpty()) {
                        EmptyState(NbmsIcons.Wine, "No bar sales found", "")
                    } else if (wide) {
                        BarSalesTable(v.rows, symbol, tz, role, busy, vm::markServed)
                    } else {
                        PagedList(v.rows, key = { it.id }) { sale ->
                            BarSaleCard(sale, symbol, tz, role, sale.id in busy, vm::markServed)
                        }
                    }
            }
        }
    }
}

@Composable
private fun StatusField(status: String, onStatus: (String) -> Unit, modifier: Modifier) {
    val options = remember { barHistoryStatusOptions() }
    NbmsDropdown(
        label = "Status",
        options = options,
        selected = options.firstOrNull { it.first == status },
        onSelect = { onStatus(it.first) },
        optionLabel = { it.second },
        modifier = modifier
    )
}

/** Month picker as a dropdown: "All months" (blank) plus the last 24 months; the current month is the default. */
@Composable
private fun MonthField(month: String, onMonth: (String) -> Unit, zone: java.time.ZoneId, modifier: Modifier) {
    val options = remember(zone) { barHistoryMonthOptions(zone) }
    val shown = if (month in options) options else listOf(month) + options
    NbmsDropdown(
        label = "Month",
        options = shown,
        selected = month,
        onSelect = onMonth,
        optionLabel = { if (it.isBlank()) "All months" else barHistoryMonthLabel(it) },
        modifier = modifier
    )
}

// ── pieces shared by the card and the table row ──

@Composable
private fun StatusPill(sale: BarSale) {
    NbmsPill(sale.status.label, MaterialTheme.nbms.statusPill(sale.status.key))
}

@Composable
private fun GuestBlock(sale: BarSale) {
    Column {
        Text(barHistoryGuestText(sale), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        barHistoryLocationText(sale)?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ItemChips(sale: BarSale) {
    val (chips, more) = barHistoryChips(sale.items)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { NbmsBadge(it, BadgeTone.Secondary) }
        if (more > 0) NbmsBadge("+$more", BadgeTone.Secondary)
    }
}

/** Green "Mark Served" button with a tiny spinner while the update is saving. */
@Composable
private fun MarkServedButton(loading: Boolean, onClick: () -> Unit) {
    val green = MaterialTheme.nbms.success
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (loading) green.copy(alpha = 0.7f) else green)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
        Text("Mark Served", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
    }
}

// ── phone card ──

@Composable
private fun BarSaleCard(
    sale: BarSale, symbol: String, tz: TimeZone, role: com.westly.nbms.core.rbac.Role,
    saving: Boolean, onMarkServed: (BarSale) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    barHistoryDateTime(sale.createdAt, tz), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                StatusPill(sale)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sale.barAttendantName.ifBlank { "—" }, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(Format.currency(sale.total, symbol), fontWeight = FontWeight.Bold)
            }
            GuestBlock(sale)
            ItemChips(sale)
            if (showMarkServed(role, sale)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MarkServedButton(saving) { onMarkServed(sale) }
                }
            }
        }
    }
}

// ── tablet table ──

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align, modifier = Modifier.weight(weight)
    )
}

@Composable
private fun BarSalesTable(
    rows: List<BarSale>, symbol: String, tz: TimeZone, role: com.westly.nbms.core.rbac.Role,
    busy: Set<String>, onMarkServed: (BarSale) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                HeaderCell("Date & Time", 1.4f); HeaderCell("Attendant", 1f); HeaderCell("Guest", 1.2f)
                HeaderCell("Items", 2f); HeaderCell("Total", 1f, TextAlign.End); HeaderCell("Status", 1f); HeaderCell("", 1.3f)
            }
            HorizontalDivider()
            PagedList(rows, key = { it.id }, modifier = Modifier.padding(top = 8.dp)) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        barHistoryDateTime(s.createdAt, tz), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f)
                    )
                    Text(
                        s.barAttendantName.ifBlank { "—" }, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Column(Modifier.weight(1.2f)) { GuestBlock(s) }
                    Column(Modifier.weight(2f)) { ItemChips(s) }
                    Text(
                        Format.currency(s.total, symbol), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                    )
                    Row(Modifier.weight(1f)) { StatusPill(s) }
                    Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.End) {
                        if (showMarkServed(role, s)) MarkServedButton(s.id in busy) { onMarkServed(s) }
                    }
                }
            }
        }
    }
}
