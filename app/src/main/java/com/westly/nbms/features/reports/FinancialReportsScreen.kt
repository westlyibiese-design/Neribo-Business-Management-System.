package com.westly.nbms.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.LabelValueRow
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.finance.RevenueCategory
import com.westly.nbms.features.finance.charts.DonutChart
import com.westly.nbms.features.finance.charts.DonutSlice
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** hsl(200, 70%, 50%): the colour of the "Other Income" slice. */
private val OTHER_INCOME_BLUE = Color(0xFF269DD9)

/** The Financial Reports page (`financial-reports`): one month of approved revenue, expenses and profit, with a PDF. */
@Composable
fun FinancialReportsScreen(session: SessionState.SignedIn) {
    val vm: FinancialReportsViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val symbol = session.business.currencySymbol
    val ready = view as? FinancialReportsView.Ready

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("Financial Report", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = if (ready != null) financialSubtitle(month, ready.report.approvedCount) else financialMonthLabel(month),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FinancialMonthField(month = month, onChange = vm::setMonth, modifier = Modifier.weight(1f))
            NbmsButton(
                text = "Print / PDF",
                onClick = { vm.exportPdf(context, session) },
                variant = ButtonVariant.Outline,
                loading = exporting,
                enabled = ready != null && !exporting,
                leadingIcon = NbmsIcons.Download
            )
        }

        when (val v = view) {
            is FinancialReportsView.Loading -> LoadingState()
            is FinancialReportsView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is FinancialReportsView.Ready -> FinancialReportContent(v.report, symbol)
        }
    }
}

// ── month picker ──

/** A field that shows the chosen month ("October 2026"). Tapping it opens the month picker. */
@Composable
private fun FinancialMonthField(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.nbms.inputBorder, shape)
            .semantics { contentDescription = "Filter by month" }
            .clickable { open = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.Calendar, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            text = financialMonthLabel(month),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (open) {
        FinancialMonthPickerDialog(
            current = month,
            onPick = {
                onChange(it)
                open = false
            },
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun FinancialMonthPickerDialog(current: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableStateOf(current.year) }
    NbmsDialog(title = "Select month", onDismiss = onDismiss, onConfirm = null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                NbmsButton(
                    text = "Previous year",
                    onClick = { year -= 1 },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leadingIcon = NbmsIcons.ArrowLeft
                )
                Text(year.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                NbmsButton(
                    text = "Next year",
                    onClick = { year += 1 },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leadingIcon = NbmsIcons.ChevronRight
                )
            }
            (1..12).chunked(3).forEach { monthsInRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    monthsInRow.forEach { m ->
                        val value = YearMonth.of(year, m)
                        NbmsButton(
                            text = Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                            onClick = { onPick(value) },
                            modifier = Modifier.weight(1f),
                            variant = if (value == current) ButtonVariant.Default else ButtonVariant.Outline,
                            size = ButtonSize.Sm
                        )
                    }
                }
            }
        }
    }
}

// ── page content ──

@Composable
private fun FinancialReportContent(report: FinancialReport, symbol: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SummaryCards(report, symbol)
        if (report.revenueBreakdown().isNotEmpty()) RevenueBreakdownCard(report, symbol)
        if (report.expensesByCategory.isNotEmpty()) ExpenseBreakdownCard(report, symbol)
        IncomeStatementCard(report, symbol)
    }
}

@Composable
private fun SummaryCards(report: FinancialReport, symbol: String) {
    val colors = MaterialTheme.nbms
    val netColor = if (report.netProfit < 0.0) colors.destructive else MaterialTheme.colorScheme.primary
    val cards = listOf(
        Triple("Total Revenue", Format.currency(report.totalRevenue, symbol), colors.success),
        Triple("Total Expenses", Format.currency(report.totalExpenses, symbol), colors.destructive),
        Triple("Net Profit", Format.currency(report.netProfit, symbol), netColor),
        Triple("Profit Margin", "${report.margin}%", colors.charts[4])
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.forEach { (label, value, color) ->
                    NbmsCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Slice colours in the Westly order: chart-1, chart-2, chart-3, chart-5, chart-4, then hsl(200,70%,50%). */
private fun revenueSliceColor(category: RevenueCategory, charts: List<Color>): Color = when (category) {
    RevenueCategory.ROOM -> charts[0]
    RevenueCategory.SALES -> charts[1]
    RevenueCategory.RESTAURANT -> charts[2]
    RevenueCategory.BAR -> charts[4]
    RevenueCategory.LAUNDRY -> charts[3]
    RevenueCategory.OTHER -> OTHER_INCOME_BLUE
}

@Composable
private fun RevenueBreakdownCard(report: FinancialReport, symbol: String) {
    val charts = MaterialTheme.nbms.charts
    val rows = report.revenueBreakdown()
    val slices = rows.map { (category, name, amount) -> DonutSlice(name, amount, revenueSliceColor(category, charts)) }
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardTitle("Revenue Breakdown")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(slices = slices)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slices.forEach { slice ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
                        Text(
                            slice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Format.currency(slice.value, symbol),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseBreakdownCard(report: FinancialReport, symbol: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.nbms.destructive.copy(alpha = 0.7f)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle("Expense Breakdown")
            report.expensesByCategory.forEach { (category, amount) ->
                val share = if (report.totalExpenses <= 0.0) 0f else (amount / report.totalExpenses).toFloat().coerceIn(0f, 1f)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabelValueRow(
                        label = category.label,
                        value = Format.currency(amount, symbol),
                        valueWeight = FontWeight.Medium,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    )
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
                        Box(Modifier.fillMaxWidth(share).height(6.dp).clip(RoundedCornerShape(3.dp)).background(fill))
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomeStatementCard(report: FinancialReport, symbol: String) {
    val colors = MaterialTheme.nbms
    val scheme = MaterialTheme.colorScheme
    val netColor = if (report.netProfit < 0.0) colors.destructive else scheme.primary
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("Income Statement Summary")
            FINANCIAL_STATEMENT_ORDER.forEach { category ->
                val amount = report.revenueOf(category)
                if (category == RevenueCategory.OTHER && amount <= 0.0) return@forEach
                StatementRow(category.label, Format.currency(amount, symbol))
            }
            HorizontalDivider(thickness = 2.dp, color = scheme.outline)
            StatementRow("Total Revenue", Format.currency(report.totalRevenue, symbol), bold = true, color = colors.success)
            StatementRow("Total Expenses", "(${Format.currency(report.totalExpenses, symbol)})", color = colors.destructive)
            Spacer(Modifier.height(2.dp))
            StatementRow(
                label = "Net Profit",
                value = Format.currency(report.netProfit, symbol),
                bold = true,
                color = netColor,
                large = true
            )
        }
    }
}

@Composable
private fun StatementRow(label: String, value: String, bold: Boolean = false, color: Color = Color.Unspecified, large: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val base = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    LabelValueRow(
        label = label,
        value = value,
        valueColor = color,
        valueWeight = weight,
        labelColor = scheme.onSurface,
        labelStyle = base.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        valueStyle = base
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}
