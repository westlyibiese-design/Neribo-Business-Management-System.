package com.westly.nbms.features.finance

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.Pagination
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.StatCard
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.design.ToastViewModel
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDate
import java.io.File
import java.time.LocalDate

private val APPROVALS_TABLET_WIDTH = 600.dp
private const val APPROVALS_TABLE_PAGE_SIZE = 15
private val ApprovalsAmber = Color(0xFFD97706)
private val ApprovalsAmberBadge = Color(0xFFF59E0B)
private val ApprovalsRed = Color(0xFFEF4444)

private val APPROVALS_TAB_TITLES = listOf("Pending Approvals", "Transaction History", "Daily Records")

private val HISTORY_HEADERS = listOf("Date & Time", "Guest", "Type", "Category", "Amount", "Method", "Recorded By", "Status", "Approved By")
private val HISTORY_WEIGHTS = listOf(1.3f, 1.4f, 1.1f, 1.2f, 1.0f, 0.9f, 1.1f, 1.1f, 1.5f)

private val DAILY_HEADERS = listOf("Date", "Room", "Restaurant", "Bar", "Laundry", "Sales", "Other", "Total Revenue", "Activity")
private val DAILY_WEIGHTS = listOf(1.2f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.2f, 2.2f)

/** The Approvals page (`approvals`): review pending payments, read every transaction, and see the day-by-day record. */
@Composable
fun ApprovalsScreen(session: SessionState.SignedIn) {
    val vm: ApprovalsViewModel = hiltViewModel()
    val toast = hiltViewModel<ToastViewModel>().controller
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { approvalsZoneOf(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    val canReview = approvalsCanReview(session.user.role)

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rejectKey by rememberSaveable { mutableStateOf<String?>(null) }

    val ready = view as? ApprovalsView.Ready
    val rejectTarget = rejectKey?.let { key -> ready?.pending?.firstOrNull { approvalsTxnKey(it) == key } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= APPROVALS_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader(
                title = "Accountant · Approvals & Records",
                subtitle = "Review incoming payments and manage the hotel's financial record."
            )

            ApprovalsTabs(
                titles = APPROVALS_TAB_TITLES,
                selected = tab,
                pendingCount = ready?.pending?.size ?: 0,
                onSelect = { tab = it }
            )

            when (tab) {
                0 -> PendingTab(
                    view = view,
                    symbol = symbol,
                    tz = tz,
                    canReview = canReview,
                    busy = busy,
                    onApprove = vm::approve,
                    onReject = { rejectKey = approvalsTxnKey(it) },
                    onRetry = vm::retry
                )

                1 -> HistoryTab(
                    view = view,
                    wide = wide,
                    filters = filters,
                    symbol = symbol,
                    tz = tz,
                    vm = vm,
                    onExport = {
                        val current = view as? ApprovalsView.Ready
                        if (current != null) {
                            val csv = buildTransactionsCsv(current.history, zone)
                            val fileName = transactionsCsvFileName(current.rangeStart, current.rangeEnd)
                            scope.launch {
                                try {
                                    shareTransactionsCsv(context, csv, fileName)
                                } catch (e: Exception) {
                                    toast.show(
                                        message = e.message ?: "The file could not be shared.",
                                        type = ToastType.Error,
                                        title = "Error"
                                    )
                                }
                            }
                        }
                    },
                    onRetry = vm::retry
                )

                else -> DailyTab(
                    view = view,
                    wide = wide,
                    filters = filters,
                    symbol = symbol,
                    vm = vm,
                    onRetry = vm::retry
                )
            }
        }
    }

    if (canReview && rejectTarget != null) {
        RejectDialog(
            txn = rejectTarget,
            symbol = symbol,
            loading = approvalsTxnKey(rejectTarget) in busy,
            onDismiss = { rejectKey = null },
            onConfirm = { reason -> vm.reject(rejectTarget, reason) { rejectKey = null } }
        )
    }
}

// ── tabs ──

/** The three tabs in the segmented-pill style. They scroll sideways on a phone; Pending Approvals carries an amber count badge. */
@Composable
private fun ApprovalsTabs(titles: List<String>, selected: Int, pendingCount: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val trackShape = MaterialTheme.shapes.medium
    val triggerShape = MaterialTheme.shapes.small
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trackWidth = if (maxWidth > 540.dp) maxWidth else 540.dp
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .width(trackWidth)
                    .heightIn(min = 36.dp)
                    .clip(trackShape)
                    .background(scheme.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                titles.forEachIndexed { index, title ->
                    val active = index == selected
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(triggerShape)
                            .background(if (active) scheme.background else Color.Transparent)
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) scheme.onBackground else scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        if (index == 0 && pendingCount > 0) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 20.dp)
                                    .clip(CircleShape)
                                    .background(ApprovalsAmberBadge)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    pendingCount.toString(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Pending tab ──

@Composable
private fun PendingTab(
    view: ApprovalsView,
    symbol: String,
    tz: TimeZone,
    canReview: Boolean,
    busy: Set<String>,
    onApprove: (RevenueTransaction) -> Unit,
    onReject: (RevenueTransaction) -> Unit,
    onRetry: () -> Unit
) {
    when (view) {
        is ApprovalsView.Loading -> LoadingState()
        is ApprovalsView.Error -> ErrorState(MSG_APPROVALS_PENDING_LOAD_FAILED, onRetry = onRetry)
        is ApprovalsView.Ready -> {
            if (view.pending.isEmpty()) {
                EmptyState(
                    icon = NbmsIcons.CheckCircle,
                    title = "No payments waiting for approval. Everything's up to date.",
                    message = ""
                )
            } else {
                PagedList(items = view.pending, key = { approvalsTxnKey(it) }) { txn ->
                    PendingCard(
                        txn = txn,
                        symbol = symbol,
                        tz = tz,
                        canReview = canReview,
                        busy = approvalsTxnKey(txn) in busy,
                        onApprove = { onApprove(txn) },
                        onReject = { onReject(txn) }
                    )
                }
            }
        }
    }
}

private fun approvalsCategoryIcon(category: RevenueCategory): ImageVector = when (category) {
    RevenueCategory.ROOM -> NbmsIcons.Bed
    RevenueCategory.RESTAURANT -> NbmsIcons.Coffee
    RevenueCategory.SALES -> NbmsIcons.ShoppingCart
    RevenueCategory.BAR -> NbmsIcons.Wine
    RevenueCategory.LAUNDRY -> NbmsIcons.Laundry
    RevenueCategory.OTHER -> NbmsIcons.Banknote
}

/** "12 Mar 2025, 14:30" in the business time zone, or "—" when there is no date. [Format] works with kotlinx instants. */
private fun approvalsDateTime(date: java.time.Instant?, tz: TimeZone): String = Format.dateTime(date?.toKotlinInstant(), tz)

/** "12 Mar 2025" in the business time zone, or "—" when there is no date. */
private fun approvalsDate(date: java.time.Instant?, tz: TimeZone): String = Format.date(date?.toKotlinInstant(), tz)

/** "credit_card" -> "Credit Card". */
private fun approvalsMethodLabel(raw: String): String {
    val words = raw.trim().replace('_', ' ').split(' ').filter { it.isNotEmpty() }
    return if (words.isEmpty()) "—" else words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

@Composable
private fun PendingCard(
    txn: RevenueTransaction,
    symbol: String,
    tz: TimeZone,
    canReview: Boolean,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NbmsCard(Modifier.fillMaxWidth().border(1.dp, ApprovalsAmber.copy(alpha = 0.6f), MaterialTheme.shapes.large)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ApprovalsAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(approvalsCategoryIcon(txn.category), contentDescription = null, tint = ApprovalsAmber, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        txn.guestName.ifBlank { "—" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${txn.typeLabel} · ${txn.category.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    Format.currency(txn.amount, symbol),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Text(
                "${approvalsDateTime(txn.date, tz)} · ${approvalsMethodLabel(txn.paymentMethod)} · Recorded by ${txn.recordedByName.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            if (canReview) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApprovalsActionButton(
                        text = "Approve",
                        icon = NbmsIcons.CheckCircle,
                        filled = true,
                        color = MaterialTheme.nbms.success,
                        contentColor = MaterialTheme.nbms.onSuccess,
                        loading = busy,
                        enabled = !busy,
                        onClick = onApprove,
                        modifier = Modifier.weight(1f)
                    )
                    ApprovalsActionButton(
                        text = "Reject",
                        icon = NbmsIcons.XCircle,
                        filled = false,
                        color = ApprovalsRed,
                        contentColor = ApprovalsRed,
                        loading = false,
                        enabled = !busy,
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** A 36dp button that can be solid green (Approve) or outlined red (Reject), with a spinner while [loading]. */
@Composable
private fun ApprovalsActionButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    color: Color,
    contentColor: Color,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.small
    val look = if (filled) Modifier.background(color) else Modifier.border(1.dp, color, shape)
    Row(
        modifier = modifier
            .alpha(if (enabled || loading) 1f else 0.5f)
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(look)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = contentColor, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
    }
}

// ── filter bar (History and Daily) ──

@Composable
private fun ApprovalsFilterBar(
    wide: Boolean,
    filters: ApprovalsFilters,
    showSearch: Boolean,
    exportEnabled: Boolean?,
    rangeStart: LocalDate?,
    rangeEnd: LocalDate?,
    vm: ApprovalsViewModel,
    onExport: () -> Unit
) {
    val range: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "Range",
            options = DateRangePreset.entries.toList(),
            selected = filters.range,
            onSelect = vm::setRange,
            optionLabel = ::approvalsRangeLabel,
            modifier = m
        )
    }
    val status: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "Status",
            options = ApprovalsStatusFilter.entries.toList(),
            selected = filters.status,
            onSelect = vm::setStatus,
            optionLabel = { it.label },
            modifier = m
        )
    }
    val export: @Composable (Modifier) -> Unit = { m ->
        if (exportEnabled != null) {
            NbmsButton(
                text = "Export",
                onClick = onExport,
                modifier = m,
                variant = ButtonVariant.Outline,
                enabled = exportEnabled,
                leadingIcon = NbmsIcons.Download
            )
        }
    }
    val custom: @Composable () -> Unit = {
        if (filters.range == DateRangePreset.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsDatePickerField(
                    label = "From",
                    value = (filters.customStart ?: rangeStart)?.toKotlinLocalDate(),
                    onChange = { vm.setCustomStart(it.toJavaLocalDate()) },
                    modifier = Modifier.weight(1f)
                )
                NbmsDatePickerField(
                    label = "To",
                    value = (filters.customEnd ?: rangeEnd)?.toKotlinLocalDate(),
                    onChange = { vm.setCustomEnd(it.toJavaLocalDate()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                if (showSearch) {
                    SearchBar(
                        value = filters.search,
                        onValueChange = vm::setSearch,
                        placeholder = "Search guest, type…",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                range(Modifier.width(180.dp))
                status(Modifier.width(180.dp))
                export(Modifier)
            }
        } else {
            if (showSearch) {
                SearchBar(value = filters.search, onValueChange = vm::setSearch, placeholder = "Search guest, type…")
            }
            range(Modifier.fillMaxWidth())
            status(Modifier.fillMaxWidth())
        }
        custom()
        if (!wide) export(Modifier.fillMaxWidth())
    }
}

// ── History tab ──

@Composable
private fun HistoryTab(
    view: ApprovalsView,
    wide: Boolean,
    filters: ApprovalsFilters,
    symbol: String,
    tz: TimeZone,
    vm: ApprovalsViewModel,
    onExport: () -> Unit,
    onRetry: () -> Unit
) {
    val ready = view as? ApprovalsView.Ready
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovalsFilterBar(
            wide = wide,
            filters = filters,
            showSearch = true,
            exportEnabled = ready != null,
            rangeStart = ready?.rangeStart,
            rangeEnd = ready?.rangeEnd,
            vm = vm,
            onExport = onExport
        )
        when (view) {
            is ApprovalsView.Loading -> LoadingState()
            is ApprovalsView.Error -> ErrorState(MSG_APPROVALS_HISTORY_LOAD_FAILED, onRetry = onRetry)
            is ApprovalsView.Ready -> {
                ApprovalsStatTiles(view.stats, symbol, wide)
                if (view.history.isEmpty()) {
                    EmptyState(icon = NbmsIcons.Receipt, title = "No transactions in this range.", message = "")
                } else if (wide) {
                    ApprovalsTable(
                        headers = HISTORY_HEADERS,
                        weights = HISTORY_WEIGHTS,
                        rows = view.history,
                        rowKey = { approvalsTxnKey(it) }
                    ) { txn, index -> HistoryCell(txn, index, symbol, tz) }
                } else {
                    PagedList(items = view.history, key = { approvalsTxnKey(it) }) { txn -> HistoryCard(txn, symbol, tz) }
                }
            }
        }
    }
}

private data class ApprovalsTile(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val tone: BadgeTone,
    val subtitle: String? = null
)

@Composable
private fun ApprovalsStatTiles(stats: ApprovalsStats, symbol: String, wide: Boolean) {
    val tiles = listOf(
        ApprovalsTile("Approved Revenue", Format.currency(stats.approvedRevenue, symbol), NbmsIcons.TrendingUp, BadgeTone.Success),
        ApprovalsTile("Transactions", stats.transactions.toString(), NbmsIcons.Receipt, BadgeTone.Default),
        ApprovalsTile("Pending", stats.pendingCount.toString(), NbmsIcons.Clock, BadgeTone.Warning, Format.currency(stats.pendingAmount, symbol)),
        ApprovalsTile("Rejected", stats.rejectedCount.toString(), NbmsIcons.XCircle, BadgeTone.Destructive, Format.currency(stats.rejectedAmount, symbol))
    )
    val perRow = if (wide) 4 else 2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(perRow).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                group.forEach { t ->
                    StatCard(
                        label = t.label,
                        value = t.value,
                        icon = t.icon,
                        tone = t.tone,
                        subtitle = t.subtitle,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Approved: green bold. Rejected: red with a line through it. Pending: muted. */
@Composable
private fun ApprovalsAmountText(txn: RevenueTransaction, symbol: String, modifier: Modifier = Modifier) {
    val (color, bold, strike) = when (txn.approvalStatus) {
        ApprovalStatus.APPROVED -> Triple(MaterialTheme.nbms.success, true, false)
        ApprovalStatus.REJECTED -> Triple(ApprovalsRed, false, true)
        ApprovalStatus.PENDING -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, false, false)
    }
    Text(
        text = Format.currency(txn.amount, symbol),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
        modifier = modifier
    )
}

/** "Pending" (amber, clock), "Approved" (green, check) or "Rejected" (red, cross). */
@Composable
private fun ApprovalsStatusBadge(status: ApprovalStatus) {
    val (icon, pillKey) = when (status) {
        ApprovalStatus.PENDING -> NbmsIcons.Clock to "awaiting_approval"
        ApprovalStatus.APPROVED -> NbmsIcons.CheckCircle to "approved"
        ApprovalStatus.REJECTED -> NbmsIcons.XCircle to "rejected"
    }
    val colors = MaterialTheme.nbms.statusPill(pillKey)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        Text(transactionsStatusLabel(status), color = colors.content, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** "{name} · {date}" or "—". */
private fun approvedByText(txn: RevenueTransaction, tz: TimeZone): String {
    val name = txn.approvedByName?.trim().orEmpty()
    return if (name.isEmpty()) "—" else "$name · ${approvalsDate(txn.approvedAt, tz)}"
}

@Composable
private fun HistoryCard(txn: RevenueTransaction, symbol: String, tz: TimeZone) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        txn.guestName.ifBlank { "—" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("${txn.typeLabel} · ${txn.category.label}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                ApprovalsStatusBadge(txn.approvalStatus)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ApprovalsAmountText(txn, symbol)
                Text(approvalsMethodLabel(txn.paymentMethod), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(approvalsDateTime(txn.date, tz), style = MaterialTheme.typography.bodySmall, color = muted)
            Text("Recorded by ${txn.recordedByName.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = muted)
            Text("Approved by ${approvedByText(txn, tz)}", style = MaterialTheme.typography.bodySmall, color = muted)
            if (txn.approvalStatus == ApprovalStatus.REJECTED && !txn.rejectedReason.isNullOrBlank()) {
                Text("Reason: ${txn.rejectedReason}", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
private fun HistoryCell(txn: RevenueTransaction, index: Int, symbol: String, tz: TimeZone) {
    val body = MaterialTheme.typography.bodySmall
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (index) {
        0 -> Text(approvalsDateTime(txn.date, tz), style = body, color = onSurface, maxLines = 2)
        1 -> Text(
            txn.guestName.ifBlank { "—" },
            style = body.copy(fontWeight = FontWeight.Medium),
            color = onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        2 -> Text(txn.typeLabel, style = body, color = onSurface, maxLines = 2)
        3 -> Text(txn.category.label, style = body, color = onSurface, maxLines = 2)
        4 -> ApprovalsAmountText(txn, symbol)
        5 -> Text(approvalsMethodLabel(txn.paymentMethod), style = body, color = onSurface, maxLines = 2)
        6 -> Text(txn.recordedByName.ifBlank { "—" }, style = body, color = onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        7 -> ApprovalsStatusBadge(txn.approvalStatus)
        else -> Column {
            Text(approvedByText(txn, tz), style = body, color = onSurface, maxLines = 2)
            if (txn.approvalStatus == ApprovalStatus.REJECTED && !txn.rejectedReason.isNullOrBlank()) {
                Text(txn.rejectedReason, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Daily Records tab ──

@Composable
private fun DailyTab(
    view: ApprovalsView,
    wide: Boolean,
    filters: ApprovalsFilters,
    symbol: String,
    vm: ApprovalsViewModel,
    onRetry: () -> Unit
) {
    val ready = view as? ApprovalsView.Ready
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovalsFilterBar(
            wide = wide,
            filters = filters,
            showSearch = false,
            exportEnabled = null,
            rangeStart = ready?.rangeStart,
            rangeEnd = ready?.rangeEnd,
            vm = vm,
            onExport = {}
        )
        when (view) {
            is ApprovalsView.Loading -> LoadingState()
            is ApprovalsView.Error -> ErrorState(MSG_APPROVALS_HISTORY_LOAD_FAILED, onRetry = onRetry)
            is ApprovalsView.Ready -> {
                if (view.daily.isEmpty()) {
                    EmptyState(icon = NbmsIcons.Receipt, title = "No activity in this range.", message = "")
                } else if (wide) {
                    ApprovalsTable(
                        headers = DAILY_HEADERS,
                        weights = DAILY_WEIGHTS,
                        rows = view.daily,
                        rowKey = { it.date }
                    ) { record, index -> DailyCell(record, index, symbol) }
                } else {
                    PagedList(items = view.daily, key = { it.date }) { record -> DailyCard(record, symbol) }
                }
            }
        }
    }
}

/** "{n} txns · {p} pending · {a} approved · {r} rejected" with pending amber, approved green and rejected red. */
@Composable
private fun dailyFooterText(record: DailyRecord): AnnotatedString {
    val green = MaterialTheme.nbms.success
    return buildAnnotatedString {
    append("${record.transactionCount} txns · ")
    withStyle(SpanStyle(color = ApprovalsAmber)) { append("${record.pending} pending") }
    append(" · ")
    withStyle(SpanStyle(color = green)) { append("${record.approved} approved") }
    append(" · ")
    withStyle(SpanStyle(color = ApprovalsRed)) { append("${record.rejected} rejected") }
    }
}

@Composable
private fun DailyCard(record: DailyRecord, symbol: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rows = listOf(
        "Room" to record.room,
        "Restaurant" to record.restaurant,
        "Bar" to record.bar,
        "Laundry" to record.laundry,
        "Sales" to record.sales,
        "Other" to record.other
    )
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.label,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total Revenue", style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(
                        Format.currency(record.total, symbol),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.nbms.success
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            rows.forEach { (label, amount) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = muted)
                    Text(Format.currency(amount, symbol), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            Text(dailyFooterText(record), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun DailyCell(record: DailyRecord, index: Int, symbol: String) {
    val body = MaterialTheme.typography.bodySmall
    val onSurface = MaterialTheme.colorScheme.onSurface
    fun money(v: Double) = Format.currency(v, symbol)
    when (index) {
        0 -> Text(record.label, style = body.copy(fontWeight = FontWeight.Medium), color = onSurface, maxLines = 2)
        1 -> Text(money(record.room), style = body, color = onSurface, maxLines = 1)
        2 -> Text(money(record.restaurant), style = body, color = onSurface, maxLines = 1)
        3 -> Text(money(record.bar), style = body, color = onSurface, maxLines = 1)
        4 -> Text(money(record.laundry), style = body, color = onSurface, maxLines = 1)
        5 -> Text(money(record.sales), style = body, color = onSurface, maxLines = 1)
        6 -> Text(money(record.other), style = body, color = onSurface, maxLines = 1)
        7 -> Text(money(record.total), style = body.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.nbms.success, maxLines = 1)
        else -> Text(dailyFooterText(record), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── shared table ──

/** A card holding a header row and the rows of the current page, with a pager below. Used on tablets. */
@Composable
private fun <T> ApprovalsTable(
    headers: List<String>,
    weights: List<Float>,
    rows: List<T>,
    rowKey: (T) -> Any,
    cell: @Composable (T, Int) -> Unit
) {
    var page by remember(rows) { mutableIntStateOf(1) }
    val pageCount = if (rows.isEmpty()) 1 else (rows.size + APPROVALS_TABLE_PAGE_SIZE - 1) / APPROVALS_TABLE_PAGE_SIZE
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * APPROVALS_TABLE_PAGE_SIZE
    val visible = rows.subList(start, minOf(rows.size, start + APPROVALS_TABLE_PAGE_SIZE))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                ApprovalsTableRow(weights, vertical = 10.dp) { index ->
                    Text(headers[index], style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 2)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                visible.forEach { row ->
                    androidx.compose.runtime.key(rowKey(row)) {
                        ApprovalsTableRow(weights, vertical = 8.dp) { index -> cell(row, index) }
                        HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                    }
                }
            }
        }
        Pagination(page = safePage, pageCount = pageCount, onPage = { page = it.coerceIn(1, pageCount) })
    }
}

@Composable
private fun ApprovalsTableRow(
    weights: List<Float>,
    vertical: androidx.compose.ui.unit.Dp,
    cell: @Composable (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        weights.forEachIndexed { index, weight ->
            Box(Modifier.weight(weight).padding(horizontal = 4.dp)) { cell(index) }
        }
    }
}

// ── export ──

/**
 * Writes the CSV into the app's cache folder `exports/` (the folder the FileProvider from Phase 0 declares) and opens
 * the Android share sheet for it.
 */
private suspend fun shareTransactionsCsv(context: Context, csv: String, fileName: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(csv, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Export transactions").apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
