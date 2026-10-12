package com.westly.nbms.features.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.dashboard.data.DashExpense
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.finance.RevenueMath
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.charts.BarGroup
import com.westly.nbms.features.finance.charts.BarSeries
import com.westly.nbms.features.finance.charts.GroupedBarChart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal const val ROUTE_APPROVALS = "approvals"
internal const val ROUTE_EXPENSES = "expenses"
internal const val ROUTE_REVENUE = "revenue"
internal const val ROUTE_FINANCIAL_REPORTS = "financial-reports"

private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** "Welcome, Ada · October 2026" in the business zone. */
internal fun accountantSubtitle(name: String, now: Instant, zone: ZoneId): String =
    "Welcome, $name · ${MONTH_YEAR.format(now.atZone(zone))}"

/** Trailing amber button: "1 payment awaiting your approval" / "3 payments awaiting your approval". */
internal fun approvalsAwaitingText(count: Int): String =
    "$count ${if (count == 1) "payment" else "payments"} awaiting your approval"

/** Button of the action row: "Approvals (3)", or just "Approvals" when nothing is pending. */
internal fun approvalsButtonText(count: Int): String = if (count > 0) "Approvals ($count)" else "Approvals"

/** The live lists the Accountant dashboard reads (one cold flow each, so Retry can collect them again). */
internal class AccountantFlows(
    val expenses: Flow<Resource<List<DashExpense>>>,
    val approvedRevenue: Flow<Resource<List<RevenueTransaction>>>,
    val pendingRevenue: Flow<Resource<List<RevenueTransaction>>>
)

/** Everything the Accountant screen shows. */
internal data class AccountantState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val monthRevenue: Double = 0.0,
    val monthExpenses: Double = 0.0,
    val net: Double = 0.0,
    /** True when the month made a loss: the Net Profit card turns red instead of blue. */
    val netNegative: Boolean = false,
    val margin: Int = 0,
    val pendingCount: Int = 0,
    val months: List<MonthPair> = emptyList()
)

/** Works out the whole Accountant dashboard from the live lists. Pure: revenue is the approved-only ledger, all numbers come from [DashboardMath]. */
internal fun accountantStateOf(
    expenses: Resource<List<DashExpense>>,
    approvedRevenue: Resource<List<RevenueTransaction>>,
    pendingRevenue: Resource<List<RevenueTransaction>>,
    now: Instant,
    zone: ZoneId
): AccountantState {
    val failed = anyFailed(expenses, approvedRevenue, pendingRevenue)
    val loading = !failed && anyLoading(expenses, approvedRevenue, pendingRevenue)
    val expenseRows = expenses.rowsOrEmpty()
    val approved = approvedRevenue.rowsOrEmpty()
    val revenue = DashboardMath.monthRevenue(approved, now, zone)
    val spent = DashboardMath.monthExpenses(expenseRows, now, zone)
    val net = revenue - spent
    return AccountantState(
        loading = loading,
        failed = failed,
        now = now,
        monthRevenue = revenue,
        monthExpenses = spent,
        net = net,
        netNegative = net < 0,
        margin = DashboardMath.margin(revenue, net),
        pendingCount = RevenueMath.pendingOnly(pendingRevenue.rowsOrEmpty()).size,
        months = DashboardMath.last6Months(approved, expenseRows, now, zone)
    )
}

private class AccountantInputs(
    val expenses: Resource<List<DashExpense>>,
    val approved: Resource<List<RevenueTransaction>>,
    val pending: Resource<List<RevenueTransaction>>
)

/** Accountant dashboard data. All numbers are live and computed in the business time zone. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AccountantDashboardViewModel internal constructor(
    private val flows: () -> AccountantFlows,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>
) : ViewModel() {

    @Inject
    constructor(repository: DashboardRepository, session: SessionManager, navigator: ShellNavigator) : this(
        flows = {
            AccountantFlows(
                expenses = repository.expenses(),
                approvedRevenue = repository.approvedRevenue(),
                pendingRevenue = repository.pendingRevenue()
            )
        },
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock()
    )

    private val attempt = MutableStateFlow(0)

    internal val state: StateFlow<AccountantState> = attempt
        .flatMapLatest {
            val f = flows()
            combine(f.expenses, f.approvedRevenue, f.pendingRevenue) { e, a, p -> AccountantInputs(e, a, p) }
                .combine(clock) { i, now -> accountantStateOf(i.expenses, i.approved, i.pending, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountantState())

    /** Retry after an error: collects every list again. */
    fun retry() { attempt.value += 1 }

    fun openApprovals() = navigator.open(ROUTE_APPROVALS)

    fun openExpenses() = navigator.open(ROUTE_EXPENSES)

    fun openRevenue() = navigator.open(ROUTE_REVENUE)

    fun openFinancialReports() = navigator.open(ROUTE_FINANCIAL_REPORTS)
}

/** The Dashboard of the Accountant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountantDashboard(session: SessionState.SignedIn) {
    val viewModel: AccountantDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val zone = rememberBusinessZone(session)
    val symbol = session.business.currencySymbol
    val nbms = MaterialTheme.nbms
    val scheme = MaterialTheme.colorScheme
    val purple = nbms.charts[4]
    val netTint = if (state.netNegative) nbms.destructive else nbms.info

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Finance Overview",
            subtitle = accountantSubtitle(session.user.name, if (state.now == Instant.EPOCH) Instant.now() else state.now, zone),
            trailing = {
                if (state.pendingCount > 0) {
                    AwaitingApprovalButton(approvalsAwaitingText(state.pendingCount), viewModel::openApprovals)
                }
            }
        )
        DashStateHost(state.loading, state.failed, viewModel::retry) {
            WithWidth { width ->
                TileGrid(
                    if (width < 360.dp) 1 else 2,
                    listOf(
                        {
                            KpiCard(
                                "Monthly Revenue", Format.currency(state.monthRevenue, symbol), NbmsIcons.TrendingUp,
                                nbms.success, nbms.success.copy(alpha = 0.15f)
                            )
                        },
                        {
                            KpiCard(
                                "Monthly Expenses", Format.currency(state.monthExpenses, symbol), Icons.Outlined.TrendingDown,
                                nbms.destructive, nbms.destructive.copy(alpha = 0.15f)
                            )
                        },
                        {
                            KpiCard(
                                "Net Profit", Format.currency(state.net, symbol), NbmsIcons.Banknote,
                                netTint, netTint.copy(alpha = 0.15f)
                            )
                        },
                        {
                            KpiCard(
                                "Profit Margin", "${state.margin}%", NbmsIcons.BarChart,
                                purple, purple.copy(alpha = 0.15f)
                            )
                        }
                    )
                )
            }
            SectionCard(title = "Revenue vs Expenses (6 Months)", icon = NbmsIcons.BarChart) {
                NbmsButton(
                    text = "Full Report",
                    onClick = viewModel::openFinancialReports,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.align(Alignment.End)
                )
                GroupedBarChart(
                    groups = state.months.map { BarGroup(it.label, listOf(it.revenue, it.expenses)) },
                    series = listOf(BarSeries("Revenue", scheme.primary), BarSeries("Expenses", nbms.destructive)),
                    height = 220.dp,
                    valueFormatter = { Format.currency(it, symbol) }
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FinanceAction(approvalsButtonText(state.pendingCount), NbmsIcons.Clock, viewModel::openApprovals)
                FinanceAction("Record Expense", NbmsIcons.Receipt, viewModel::openExpenses)
                FinanceAction("Revenue Details", NbmsIcons.TrendingUp, viewModel::openRevenue)
                FinanceAction("Generate Report", NbmsIcons.BarChart, viewModel::openFinancialReports)
            }
        }
    }
}

@Composable
private fun FinanceAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NbmsButton(text = text, onClick = onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = icon)
}

/** The amber "payment(s) awaiting your approval" button of the header. */
@Composable
private fun AwaitingApprovalButton(text: String, onClick: () -> Unit) {
    val nbms = MaterialTheme.nbms
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(nbms.warning)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(NbmsIcons.Clock, contentDescription = null, tint = nbms.onWarning, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = nbms.onWarning)
    }
}
