package com.westly.nbms.features.restaurant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone
import java.time.YearMonth

private val ORDER_HISTORY_TABLET_WIDTH = 840.dp
private const val ORDER_MONTH_CHOICES = 24

/** Order History: every order (waiter: only their own), newest first, with search, status and month filters. */
@Composable
fun OrderHistoryScreen(session: SessionState.SignedIn, vm: OrderHistoryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { orderZoneOf(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    val canChange = canChangeOrderStatus(session.user.role)
    val ready = view as? OrderHistoryView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= ORDER_HISTORY_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader("Order History", ready?.let { orderHistorySubtitle(it.rows, symbol) })

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchBar(filters.search, vm::setSearch, "Search guest, room…", Modifier.fillMaxWidth())
                StatusField(filters.status, vm::setStatus)
                MonthField(filters.month, vm::setMonth, session.business.timezone)
            }

            when (val v = view) {
                is OrderHistoryView.Loading -> LoadingState()
                is OrderHistoryView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is OrderHistoryView.Ready ->
                    if (v.rows.isEmpty()) {
                        EmptyState(NbmsIcons.Coffee, "No orders found", "")
                    } else if (wide) {
                        OrdersTable(v.rows, symbol, tz, canChange, busy, vm::changeStatus)
                    } else {
                        PagedList(v.rows, key = { it.id }) { OrderCard(it, symbol, tz, canChange, busy[it.id], busy.containsKey(it.id), vm::changeStatus) }
                    }
            }
        }
    }
}

@Composable
private fun StatusField(status: String, onStatus: (String) -> Unit) {
    val options = remember { listOf(ORDER_FILTER_ALL) + OrderStatus.entries.map { it.key } }
    NbmsDropdown(
        label = "Status", options = options, selected = status, onSelect = onStatus,
        optionLabel = { if (it == ORDER_FILTER_ALL) "All Status" else orderStatusText(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Month picker as a dropdown: "All months" (blank) plus the last 24 months; the current month is the default. */
@Composable
private fun MonthField(month: String, onMonth: (String) -> Unit, timezone: String?) {
    val options = remember(timezone) {
        val now = YearMonth.parse(orderCurrentMonth(orderZoneOf(timezone)))
        listOf("") + (0 until ORDER_MONTH_CHOICES).map { now.minusMonths(it.toLong()).toString() }
    }
    val shown = if (month in options || month.isBlank()) options else listOf(month) + options
    NbmsDropdown(
        label = "Month", options = shown, selected = month, onSelect = onMonth,
        optionLabel = { if (it.isBlank()) "All months" else orderMonthLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

// ── rows ──

@Composable
private fun OrderCard(
    order: Order, symbol: String, tz: TimeZone, canChange: Boolean,
    busyTarget: OrderStatus?, rowBusy: Boolean, onChange: (Order, OrderStatus) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    Format.dateTime(order.createdAt.toInstant(), tz), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                StatusPill(order.status)
            }
            Text(order.waiterName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GuestLines(order)
            OrderItemChips(order)
            Text(Format.currency(order.total, symbol), fontWeight = FontWeight.Bold)
            if (canChange) OrderActions(order, busyTarget, rowBusy, onChange)
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    NbmsPill(orderStatusText(status), MaterialTheme.nbms.statusPill(status))
}

@Composable
private fun GuestLines(order: Order) {
    Column {
        Text(orderGuestText(order), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        orderPlaceLines(order).forEach { line ->
            Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun OrderItemChips(order: Order) {
    val (chips, more) = orderItemChips(order.items)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { NbmsBadge(it, BadgeTone.Secondary) }
        if (more > 0) NbmsBadge("+$more", BadgeTone.Secondary)
    }
}

/**
 * pending: "Preparing" (blue) and "Served" (green); preparing: "Mark Served" (green); served and cancelled: nothing.
 * While the row is busy every button is disabled and the tapped one shows a tiny spinner.
 */
@Composable
private fun OrderActions(order: Order, busyTarget: OrderStatus?, rowBusy: Boolean, onChange: (Order, OrderStatus) -> Unit) {
    val next = allowedNextStatuses(order.status)
    if (next.isEmpty()) return
    val blue = MaterialTheme.nbms.info
    val green = MaterialTheme.nbms.success
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        next.forEach { target ->
            val isPendingOrder = order.status == OrderStatus.PENDING.key
            val label = when {
                target == OrderStatus.PREPARING -> "Preparing"
                isPendingOrder -> "Served"
                else -> "Mark Served"
            }
            StatusActionButton(
                label = label,
                color = if (target == OrderStatus.PREPARING) blue else green,
                loading = busyTarget == target,
                enabled = !rowBusy,
                onClick = { onChange(order, target) }
            )
        }
    }
}

@Composable
private fun StatusActionButton(label: String, color: Color, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = if (enabled || loading) color else color.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun OrdersTable(
    rows: List<Order>, symbol: String, tz: TimeZone, canChange: Boolean,
    busy: Map<String, OrderStatus>, onChange: (Order, OrderStatus) -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                HeaderCell("Date", 1.3f); HeaderCell("Waiter", 1f); HeaderCell("Guest / Room", 1.2f)
                HeaderCell("Items", 2f); HeaderCell("Total", 1f, TextAlign.End); HeaderCell("Status", 1f)
                HeaderCell(if (canChange) "Actions" else "", 1.6f)
            }
            HorizontalDivider()
            PagedList(rows, key = { it.id }, modifier = Modifier.padding(top = 8.dp)) { o ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.dateTime(o.createdAt.toInstant(), tz), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.3f)
                    )
                    Text(o.waiterName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(Modifier.weight(1.2f)) { GuestLines(o) }
                    Column(Modifier.weight(2f)) { OrderItemChips(o) }
                    Text(
                        Format.currency(o.total, symbol), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                    )
                    Row(Modifier.weight(1f).padding(start = 12.dp)) { StatusPill(o.status) }
                    Row(Modifier.weight(1.6f)) {
                        if (canChange) OrderActions(o, busy[o.id], busy.containsKey(o.id), onChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align, modifier = Modifier.weight(weight)
    )
}
