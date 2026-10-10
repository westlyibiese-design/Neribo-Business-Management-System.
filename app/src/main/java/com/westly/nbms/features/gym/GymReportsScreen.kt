package com.westly.nbms.features.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.StatCard
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

private val REPORTS_WIDE_WIDTH = 840.dp
private val REPORTS_SIDE_BY_SIDE_WIDTH = 600.dp
private val BAR_AREA_HEIGHT = 120.dp
private val MIN_BAR_HEIGHT = 4.dp

internal const val REPORTS_SUBTITLE = "Membership, attendance, and revenue analytics for the gym."
internal const val REPORTS_PACKAGES_EMPTY = "No membership data yet."
internal const val REPORTS_TOP_EMPTY = "No visits recorded yet."
internal const val REPORTS_FOOTNOTE =
    "Revenue shown here reflects gym membership registrations and renewals recorded this month, and is not yet part of the unified Revenue Dashboard / Accountant approval ledger."

/** Height of one bar: proportional to the tallest of the seven days; a day with visits is never shorter than [MIN_BAR_HEIGHT]. */
internal fun barHeightOf(count: Int, scaleMax: Int, areaHeight: Dp): Dp {
    if (count <= 0) return 0.dp
    val proportional = areaHeight * (count.toFloat() / scaleMax.coerceAtLeast(1).toFloat())
    return if (proportional < MIN_BAR_HEIGHT) MIN_BAR_HEIGHT else proportional
}

/** The Gym Reports page (`gym/reports`). */
@Composable
fun GymReportsScreen(session: SessionState.SignedIn) {
    val vm: GymReportsViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GymPageHeader(icon = NbmsIcons.BarChart, title = "Gym Reports", subtitle = REPORTS_SUBTITLE)
        when (val v = view) {
            is ReportsView.Loading -> LoadingState()
            is ReportsView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is ReportsView.Ready -> ReportsBody(v.data, symbol)
        }
    }
}

@Composable
private fun ReportsBody(data: GymReportData, symbol: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= REPORTS_WIDE_WIDTH
        val sideBySide = maxWidth >= REPORTS_SIDE_BY_SIDE_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCards(data.stats, symbol, columns = if (wide) 5 else 2)
            VisitsCard(data.last7Days)
            if (sideBySide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PackagesCard(data.packages, Modifier.weight(1f))
                    TopMembersCard(data.topMembers, Modifier.weight(1f))
                }
            } else {
                PackagesCard(data.packages, Modifier.fillMaxWidth())
                TopMembersCard(data.topMembers, Modifier.fillMaxWidth())
            }
            Text(REPORTS_FOOTNOTE, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class StatItem(val label: String, val value: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tone: BadgeTone)

@Composable
private fun StatCards(stats: GymReportStats, symbol: String, columns: Int) {
    val items = listOf(
        StatItem("Total Members", stats.total.toString(), NbmsIcons.Users, BadgeTone.Default),
        StatItem("Active", stats.active.toString(), NbmsIcons.CheckCircle, BadgeTone.Success),
        StatItem("Expiring ≤7d", stats.expiringSoon.toString(), NbmsIcons.Clock, BadgeTone.Warning),
        StatItem("Suspended", stats.suspended.toString(), NbmsIcons.AlertTriangle, BadgeTone.Warning),
        StatItem("Revenue (this month)", Format.currency(stats.monthRevenue, symbol), NbmsIcons.Banknote, BadgeTone.Default)
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { item ->
                    StatCard(item.label, item.value, item.icon, item.tone, modifier = Modifier.weight(1f))
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun VisitsCard(days: List<DayVisits>) {
    val scaleMax = GymReportsLogic.barScaleMax(days)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardTitle("Visits — Last 7 Days")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(BAR_AREA_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(barHeightOf(day.count, scaleMax, BAR_AREA_HEIGHT))
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text(day.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text(
                            day.count.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(right, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PackagesCard(rows: List<PackageCount>, modifier: Modifier) {
    NbmsCard(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Members by Package")
            if (rows.isEmpty()) {
                Text(REPORTS_PACKAGES_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEach { ListRow(it.name, it.count.toString()) }
            }
        }
    }
}

@Composable
private fun TopMembersCard(rows: List<TopMember>, modifier: Modifier) {
    NbmsCard(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Most Active Members")
            if (rows.isEmpty()) {
                Text(REPORTS_TOP_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEach { ListRow(it.name, "${it.visits} visits") }
            }
        }
    }
}
