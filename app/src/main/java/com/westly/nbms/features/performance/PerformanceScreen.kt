package com.westly.nbms.features.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.dashboard.rememberBusinessZone
import java.time.Instant

private const val TOTAL_HIGHLIGHT_ABOVE = 10
private val TABLET_BREAKPOINT = 600.dp

/** The Staff Performance page (Super Admin and Manager): this month's activity chart and the leaderboard. */
@Composable
fun PerformanceScreen(session: SessionState.SignedIn) {
    val viewModel: PerformanceViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val shownNow = remember(state.now) { if (state.now == Instant.EPOCH) Instant.now() else state.now }
    val monthLabel = PerformanceMath.monthLabel(shownNow, zone)
    val monthName = PerformanceMath.monthName(shownNow, zone)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(title = "Staff Performance", subtitle = "$monthLabel — all roles")
        when {
            state.failed -> ErrorState(message = MSG_PERFORMANCE_LOAD_FAILED, onRetry = viewModel::retry)
            state.loading -> LoadingState()
            else -> {
                ActivityChartCard(state, monthName)
                LeaderboardCard(state.rows, symbol)
            }
        }
    }
}

@Composable
private fun ActivityChartCard(state: PerformanceState, monthName: String) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Activity by Staff — $monthName",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (state.chartIsEmpty) {
                Text(
                    "No activity recorded this month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                val bars = remember(state.top) {
                    state.top.map { p ->
                        StackedBar(
                            label = PerformanceMath.firstName(p.name),
                            values = listOf(p.checkins, p.checkouts, p.sales, p.orders)
                        )
                    }
                }
                StackedBarChart(bars = bars, series = PERFORMANCE_SERIES)
            }
        }
    }
}

@Composable
private fun LeaderboardCard(rows: List<StaffPerformance>, symbol: String) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NbmsIcons.Users, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    "Staff Activity Leaderboard",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (rows.isEmpty()) {
                Text(
                    "No active staff yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= TABLET_BREAKPOINT) LeaderboardTable(rows, symbol) else LeaderboardCards(rows, symbol)
                }
            }
        }
    }
}

/** #1 yellow, #2 grey, #3 bronze, everyone else muted. */
@Composable
private fun rankColor(rank: Int): Color {
    val dark = MaterialTheme.nbms.isDark
    return when (rank) {
        1 -> if (dark) Color(0xFFFACC15) else Color(0xFFCA8A04)
        2 -> if (dark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
        3 -> if (dark) Color(0xFFD97706) else Color(0xFFB45309)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun roleLabel(roleKey: String?): String = Role.fromKey(roleKey)?.label ?: (roleKey ?: "")

@Composable
private fun totalColor(total: Int): Color =
    if (total > TOTAL_HIGHLIGHT_ABOVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

/** Phones: each person is a two-line card, name and rank on the first line, the figures in a wrapped row below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaderboardCards(rows: List<StaffPerformance>, symbol: String) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, p ->
            val rank = index + 1
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#$rank", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = rankColor(rank))
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    NbmsBadge(roleLabel(p.roleKey), BadgeTone.Secondary)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Figure("Check-Ins", p.checkInsAndOuts.toString())
                    Figure("Sales", p.salesAndOrders.toString())
                    Figure("Revenue", Format.currency(p.revenue, symbol))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FigureLabel("Attendance")
                        NbmsBadge("${p.presentDays}d", BadgeTone.Outline)
                    }
                    Figure("Total Activities", p.totalActivities.toString(), bold = true, valueColor = totalColor(p.totalActivities))
                }
            }
            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
        }
    }
}

@Composable
private fun FigureLabel(text: String) {
    Text(text, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Figure(label: String, value: String, bold: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FigureLabel(label)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor
        )
    }
}

/** Tablets: a table with the column titles Rank, Name, Role, Check-Ins, Sales, Revenue, Attendance, Total Activities. */
@Composable
private fun LeaderboardTable(rows: List<StaffPerformance>, symbol: String) {
    Column(Modifier.fillMaxWidth()) {
        TableRow(
            rank = { HeaderCell("Rank") },
            name = { HeaderCell("Name") },
            role = { HeaderCell("Role") },
            checkIns = { HeaderCell("Check-Ins", TextAlign.End) },
            sales = { HeaderCell("Sales", TextAlign.End) },
            revenue = { HeaderCell("Revenue", TextAlign.End) },
            attendance = { HeaderCell("Attendance", TextAlign.Center) },
            total = { HeaderCell("Total Activities", TextAlign.End) }
        )
        HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
        rows.forEachIndexed { index, p ->
            val rank = index + 1
            TableRow(
                rank = { Text("#$rank", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = rankColor(rank)) },
                name = {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                role = { NbmsBadge(roleLabel(p.roleKey), BadgeTone.Secondary) },
                checkIns = { NumberCell(p.checkInsAndOuts.toString()) },
                sales = { NumberCell(p.salesAndOrders.toString()) },
                revenue = { NumberCell(Format.currency(p.revenue, symbol)) },
                attendance = { NbmsBadge("${p.presentDays}d", BadgeTone.Outline) },
                total = { NumberCell(p.totalActivities.toString(), bold = true, color = totalColor(p.totalActivities)) }
            )
            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
        }
    }
}

@Composable
private fun TableRow(
    rank: @Composable () -> Unit,
    name: @Composable () -> Unit,
    role: @Composable () -> Unit,
    checkIns: @Composable () -> Unit,
    sales: @Composable () -> Unit,
    revenue: @Composable () -> Unit,
    attendance: @Composable () -> Unit,
    total: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.width(48.dp)) { rank() }
        Row(Modifier.weight(2f)) { name() }
        Row(Modifier.weight(2f)) { role() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { checkIns() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { sales() }
        Row(Modifier.weight(1.5f), horizontalArrangement = Arrangement.End) { revenue() }
        Row(Modifier.weight(1.2f), horizontalArrangement = Arrangement.Center) { attendance() }
        Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.End) { total() }
    }
}

@Composable
private fun HeaderCell(text: String, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberCell(text: String, bold: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color)
}
