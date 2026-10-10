package com.westly.nbms.features.sales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.LabelValueRow
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone
import java.time.YearMonth

private val TABLET_WIDTH = 840.dp
private const val MONTH_CHOICES = 24

/** Sales History: every sale (staff: only their own), newest first, with search and a month filter. */
@Composable
fun SalesHistoryScreen(session: SessionState.SignedIn, vm: SalesHistoryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { zoneOf(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    val ready = view as? SalesHistoryView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("Sales History", ready?.let { historySubtitle(it.rows, symbol) })

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchBar(filters.search, vm::setSearch, "Search staff, customer…", Modifier.fillMaxWidth())
                MonthField(filters.month, vm::setMonth, session.business.timezone)
            }

            when (val v = view) {
                is SalesHistoryView.Loading -> LoadingState()
                is SalesHistoryView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is SalesHistoryView.Ready ->
                    if (v.rows.isEmpty()) {
                        EmptyState(NbmsIcons.ShoppingCart, "No sales found", "")
                    } else if (wide) {
                        SalesTable(v.rows, v.total, symbol, tz)
                    } else {
                        PagedList(v.rows, key = { it.id }) { SaleCard(it, symbol, tz) }
                        TotalRow(v.total, symbol)
                    }
            }
        }
    }
}

/** Month picker as a dropdown: "All months" (blank) plus the last 24 months; the current month is the default. */
@Composable
private fun MonthField(month: String, onMonth: (String) -> Unit, timezone: String?) {
    val options = remember(timezone) {
        val now = YearMonth.parse(currentMonth(zoneOf(timezone)))
        listOf("") + (0 until MONTH_CHOICES).map { now.minusMonths(it.toLong()).toString() }
    }
    val shown = if (month in options || month.isBlank()) options else listOf(month) + options
    NbmsDropdown(
        label = "Month", options = shown, selected = month, onSelect = onMonth,
        optionLabel = { if (it.isBlank()) "All months" else monthLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SaleCard(sale: Sale, symbol: String, tz: TimeZone) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Format.dateTime(sale.createdAt.toInstant(), tz), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                Text(Format.currency(sale.total, symbol), fontWeight = FontWeight.Bold)
            }
            Text(sale.staffName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(customerText(sale), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            ItemChips(sale)
            Text(paymentText(sale.paymentMethod), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemChips(sale: Sale) {
    val (chips, more) = itemChips(sale.items)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { NbmsBadge(it, BadgeTone.Secondary) }
        if (more > 0) NbmsBadge("+$more", BadgeTone.Secondary)
    }
}

@Composable
private fun TotalRow(total: Double, symbol: String) {
    LabelValueRow(
        label = "Total",
        value = Format.currency(total, symbol),
        modifier = Modifier.padding(horizontal = 4.dp),
        valueWeight = FontWeight.Bold,
        labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        valueColor = MaterialTheme.colorScheme.primary,
        labelColor = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SalesTable(rows: List<Sale>, total: Double, symbol: String, tz: TimeZone) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                HeaderCell("Date & Time", 1.4f); HeaderCell("Staff", 1f); HeaderCell("Customer", 1f)
                HeaderCell("Items", 2f); HeaderCell("Total", 1f, TextAlign.End); HeaderCell("Payment", 1f)
            }
            HorizontalDivider()
            PagedList(rows, key = { it.id }, modifier = Modifier.padding(top = 8.dp)) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.dateTime(s.createdAt.toInstant(), tz), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f)
                    )
                    Text(s.staffName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(customerText(s), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(Modifier.weight(2f)) { ItemChips(s) }
                    Text(
                        Format.currency(s.total, symbol), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                    )
                    Text(paymentText(s.paymentMethod), fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            LabelValueRow(
        label = "Total",
        value = Format.currency(total, symbol),
        modifier = Modifier.padding(top = 12.dp),
        valueWeight = FontWeight.Bold,
        labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        valueColor = MaterialTheme.colorScheme.primary,
        labelColor = MaterialTheme.colorScheme.primary
    )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align, modifier = Modifier.weight(weight)
    )
}
