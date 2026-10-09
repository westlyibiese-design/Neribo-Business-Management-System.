package com.westly.nbms.features.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.KingBed
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role as StaffRole
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.finance.charts.AreaChart
import com.westly.nbms.features.finance.charts.BarGroup
import com.westly.nbms.features.finance.charts.BarSeries
import com.westly.nbms.features.finance.charts.ChartPoint
import com.westly.nbms.features.finance.charts.DonutChart
import com.westly.nbms.features.finance.charts.DonutSlice
import com.westly.nbms.features.finance.charts.GroupedBarChart
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Roles that may open the Approvals page (Appendix A.9). Everyone else sees the pending count as a plain chip. */
private val APPROVAL_ROLES = setOf(StaffRole.SUPER_ADMIN, StaffRole.ACCOUNTANT, StaffRole.MANAGER)

/** hsl(292, 55%, 50%) for Bar and hsl(190, 65%, 45%) for Laundry, written as hex. */
private val FUCHSIA = Color(0xFFB339C6)
private val CYAN = Color(0xFF28A5BD)

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Text for the pending button: "1 pending approval", "3 pending approvals". */
internal fun pendingLabel(count: Int): String = if (count == 1) "1 pending approval" else "$count pending approvals"

/** Chart colours for the five sources: Rooms navy, Sales gold, Restaurant green (theme chart tokens), Bar fuchsia, Laundry cyan. */
@Composable
private fun sourceColors(): Map<RevenueCategory, Color> {
    val charts = MaterialTheme.nbms.charts
    return mapOf(
        RevenueCategory.ROOM to charts[0],
        RevenueCategory.SALES to charts[1],
        RevenueCategory.RESTAURANT to charts[2],
        RevenueCategory.BAR to FUCHSIA,
        RevenueCategory.LAUNDRY to CYAN
    )
}

private fun sourceName(category: RevenueCategory): String = when (category) {
    RevenueCategory.ROOM -> "Room"
    RevenueCategory.SALES -> "Sales"
    RevenueCategory.RESTAURANT -> "Restaurant"
    RevenueCategory.BAR -> "Bar"
    RevenueCategory.LAUNDRY -> "Laundry"
    RevenueCategory.OTHER -> "Other"
}

/** The Revenue page (route `revenue`): approved revenue only, for the current month. */
@Composable
internal fun RevenueScreen(session: SessionState.SignedIn, vm: RevenueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol
    val canOpenApprovals = session.user.role in APPROVAL_ROLES

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        when (val current = state) {
            RevenueUiState.Loading -> {
                PageHeader(title = "Revenue")
                LoadingState()
            }
            is RevenueUiState.Error -> {
                PageHeader(title = "Revenue")
                ErrorState(current.message, onRetry = vm::retry)
            }
            is RevenueUiState.Ready -> {
                val summary = current.summary
                PageHeader(
                    title = "Revenue",
                    subtitle = "${summary.today.format(MONTH_YEAR)} · Approved revenue only"
                ) {
                    if (summary.pendingCount > 0) {
                        PendingChip(
                            text = pendingLabel(summary.pendingCount),
                            onClick = if (canOpenApprovals) vm::openApprovals else null
                        )
                    }
                }
                RevenueContent(summary, symbol)
            }
        }
    }
}

@Composable
private fun RevenueContent(summary: RevenueSummary, symbol: String) {
    val colors = sourceColors()
    val nbms = MaterialTheme.nbms
    val kpis = summary.kpis
    val orange = if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    val blue = if (nbms.isDark) Color(0xFF60A5FA) else nbms.info
    val green = if (nbms.isDark) Color(0xFF4ADE80) else nbms.success

    val cards = listOf(
        KpiSpec("Total This Month", kpis.total, Icons.Outlined.TrendingUp, MaterialTheme.colorScheme.primary),
        KpiSpec("Room Revenue", kpis.room, Icons.Outlined.KingBed, blue),
        KpiSpec("Sales Revenue", kpis.sales, Icons.Outlined.ShoppingCart, green),
        KpiSpec("Restaurant Revenue", kpis.restaurant, Icons.Outlined.Coffee, orange),
        KpiSpec("Bar Revenue", kpis.bar, Icons.Outlined.WineBar, FUCHSIA),
        KpiSpec("Laundry Revenue", kpis.laundry, Icons.Outlined.LocalLaundryService, CYAN)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { spec ->
                    KpiCard(spec, symbol, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    SectionCard("Daily Revenue — ${summary.today.format(MONTH_TITLE)}") {
        AreaChart(
            points = summary.dailyPoints.map {
                ChartPoint(label = it.date.dayOfMonth.toString(), value = it.total, detail = it.date.format(DAY_MONTH))
            },
            valueFormatter = { Format.currency(it, symbol) }
        )
    }

    if (summary.mixSlices.isNotEmpty()) {
        SectionCard("Revenue Mix") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(
                    slices = summary.mixSlices.map {
                        DonutSlice(sourceName(it.category), it.amount, colors.getValue(it.category))
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            summary.mixSlices.forEach { slice ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors.getValue(slice.category)))
                    Text(
                        sourceName(slice.category),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Format.currency(slice.amount, symbol),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    SectionCard("Revenue by Source — Last 6 Months") {
        GroupedBarChart(
            groups = summary.sixMonths.map { BarGroup(it.label, it.values) },
            series = REVENUE_PAGE_CATEGORIES.map { category ->
                BarSeries(if (category == RevenueCategory.ROOM) "Rooms" else sourceName(category), colors.getValue(category))
            },
            valueFormatter = { Format.currency(it, symbol) }
        )
    }
}

private class KpiSpec(val label: String, val value: Double, val icon: ImageVector, val accent: Color)

@Composable
private fun KpiCard(spec: KpiSpec, symbol: String, modifier: Modifier = Modifier) {
    NbmsCard(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(spec.label, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Format.currency(spec.value, symbol),
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = spec.accent,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(spec.icon, contentDescription = null, tint = spec.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

/** Outline amber pill with a clock: tappable when [onClick] is given, a plain chip otherwise. */
@Composable
private fun PendingChip(text: String, onClick: (() -> Unit)?) {
    val nbms = MaterialTheme.nbms
    val amber = if (nbms.isDark) nbms.warning else nbms.onWarningContainer
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .clip(shape)
            .border(BorderStroke(1.dp, amber.copy(alpha = 0.6f)), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = amber, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = amber)
    }
}
