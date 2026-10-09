package com.westly.nbms.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingDown
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.finance.charts.BarGroup
import com.westly.nbms.features.finance.charts.BarSeries
import com.westly.nbms.features.finance.charts.ChartPoint
import com.westly.nbms.features.finance.charts.GroupedBarChart

/** hsl(0, 65%, 56%): the colour of the Expenses bars. */
private val EXPENSES_RED = Color(0xFFD84646)

/** The Annual Reports page (route `reports`): this year so far, month by month, with a CSV export. */
@Composable
fun ReportsScreen(session: SessionState.SignedIn) {
    val vm: ReportsViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val symbol = session.business.currencySymbol
    val ready = view as? AnnualReportView.Ready

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(
            title = "Annual Reports",
            subtitle = ready?.let { annualSubtitle(it.report.year) },
            actions = {
                NbmsButton(
                    text = "Export CSV",
                    onClick = { vm.exportCsv(context) },
                    variant = ButtonVariant.Outline,
                    enabled = ready != null,
                    leadingIcon = NbmsIcons.Download
                )
            }
        )

        when (val v = view) {
            is AnnualReportView.Loading -> LoadingState()
            is AnnualReportView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is AnnualReportView.Ready -> AnnualReportContent(v.report, symbol)
        }
    }
}

@Composable
private fun AnnualReportContent(report: AnnualReport, symbol: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AnnualKpiCards(report, symbol)
        RevenueVsExpensesCard(report, symbol)
        BookingVolumeCard(report)
    }
}

@Composable
private fun AnnualKpiCards(report: AnnualReport, symbol: String) {
    val colors = MaterialTheme.nbms
    val profitColor = if (report.ytdProfit < 0.0) colors.destructive else MaterialTheme.colorScheme.primary
    val cards = listOf(
        KpiCard("YTD Revenue", Format.currency(report.ytdRevenue, symbol), NbmsIcons.TrendingUp, colors.success),
        KpiCard("YTD Expenses", Format.currency(report.ytdExpenses, symbol), Icons.Outlined.TrendingDown, colors.destructive),
        KpiCard("YTD Profit", Format.currency(report.ytdProfit, symbol), NbmsIcons.TrendingUp, profitColor),
        KpiCard("Current Occupancy", "${report.occupancyPercent}%", NbmsIcons.Bed, colors.info)
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.forEach { card -> KpiTile(card, Modifier.weight(1f)) }
            }
        }
    }
}

private data class KpiCard(val label: String, val value: String, val icon: ImageVector, val color: Color)

@Composable
private fun KpiTile(card: KpiCard, modifier: Modifier = Modifier) {
    NbmsCard(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    card.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    card.value,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = card.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(card.color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(card.icon, contentDescription = null, tint = card.color, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RevenueVsExpensesCard(report: AnnualReport, symbol: String) {
    val charts = MaterialTheme.nbms.charts
    val series = listOf(
        BarSeries("Revenue", charts[0]),
        BarSeries("Expenses", EXPENSES_RED),
        BarSeries("Profit", charts[2])
    )
    val groups = report.months.map { BarGroup(it.label, listOf(it.revenue, it.expenses, it.profit)) }
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardTitle("Monthly Revenue vs Expenses")
            GroupedBarChart(
                groups = groups,
                series = series,
                height = 250.dp,
                valueFormatter = { Format.currency(it, symbol) }
            )
        }
    }
}

@Composable
private fun BookingVolumeCard(report: AnnualReport) {
    val points = report.months.map { ChartPoint(label = it.label, value = it.bookings.toDouble()) }
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardTitle("Monthly Booking Volume")
            LineChart(points = points, height = 200.dp, lineColor = MaterialTheme.nbms.charts[1])
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}
