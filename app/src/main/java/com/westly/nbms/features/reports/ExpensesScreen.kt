package com.westly.nbms.features.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.AdaptiveSideBySide
import com.westly.nbms.core.design.LabelValueRow
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.Pagination
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.design.ToastViewModel
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone
import java.time.Year
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val EXPENSES_TABLET_WIDTH = 600.dp
private const val EXPENSES_TABLE_PAGE_SIZE = 15

// Table column widths (Date, Title, Category, Amount, Payment, Recorded By).
private val EXPENSES_COLUMN_WEIGHTS = listOf(1.1f, 1.7f, 1.2f, 1.1f, 1.2f, 1.3f)

/** The Expenses page (`expenses`): every expense of the business, with Export and Add Expense. */
@Composable
fun ExpensesScreen(session: SessionState.SignedIn) {
    val vm: ExpensesViewModel = hiltViewModel()
    val toast = hiltViewModel<ToastViewModel>().controller
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { expensesZoneOf(session.business.timezone) }
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    var showRecord by rememberSaveable { mutableStateOf(false) }

    val ready = view as? ExpensesView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= EXPENSES_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ExpensesHeader(
                wide = wide,
                total = ready?.total,
                symbol = symbol,
                canExport = ready != null,
                onExport = {
                    val current = view as? ExpensesView.Ready ?: return@ExpensesHeader
                    val csv = buildExpensesCsv(current.rows, zone)
                    val result = ShareFiles.shareText(context, expensesCsvFileName(filters.month), csv)
                    result.exceptionOrNull()?.let { problem ->
                        toast.show(
                            message = problem.message ?: "The file could not be shared.",
                            type = ToastType.Error,
                            title = "Error"
                        )
                    }
                },
                onAdd = { showRecord = true }
            )

            if (ready != null && ready.cards.isNotEmpty()) {
                ExpenseCategoryCards(ready.cards, symbol)
            }

            ExpensesFilterBar(
                wide = wide,
                search = filters.search,
                onSearch = vm::setSearch,
                month = filters.month,
                onMonth = vm::setMonth
            )

            when (val v = view) {
                is ExpensesView.Loading -> LoadingState()
                is ExpensesView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is ExpensesView.Ready -> {
                    if (v.rows.isEmpty()) {
                        EmptyState(icon = NbmsIcons.Receipt, title = "No expenses found", message = "")
                    } else if (wide) {
                        ExpensesTable(v.rows, v.total, symbol, tz)
                    } else {
                        PagedList(items = v.rows, key = { it.id }) { expense -> ExpenseCard(expense, symbol, tz) }
                        ExpensesTotalCard(v.total, symbol)
                    }
                }
            }
        }
    }

    if (showRecord) {
        RecordExpenseDialog(
            currencySymbol = symbol,
            zone = zone,
            saving = saving,
            onDismiss = { if (!saving) showRecord = false },
            onSubmit = { form -> vm.save(form, session) { showRecord = false } }
        )
    }
}

// ── header, cards and filters ──

@Composable
private fun ExpensesHeader(
    wide: Boolean,
    total: Double?,
    symbol: String,
    canExport: Boolean,
    onExport: () -> Unit,
    onAdd: () -> Unit
) {
    val titleBlock: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text("Expenses", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            if (total != null) {
                Text(
                    "Total: ${Format.currency(total, symbol)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        NbmsButton(
            text = "Export",
            onClick = onExport,
            variant = ButtonVariant.Outline,
            enabled = canExport,
            leadingIcon = NbmsIcons.Download
        )
        NbmsButton(text = "Add Expense", onClick = onAdd, leadingIcon = NbmsIcons.Plus)
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            titleBlock(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            titleBlock(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    }
}

/** The top categories of the rows that are showing, two to a line. */
@Composable
private fun ExpenseCategoryCards(cards: List<ExpenseCategoryCard>, symbol: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.forEach { card ->
                    NbmsCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                expensesWords(card.category.key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                Format.currency(card.amount, symbol),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.nbms.destructive,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExpensesFilterBar(
    wide: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    month: String,
    onMonth: (String) -> Unit
) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search expenses…", modifier = Modifier.weight(1f))
            ExpenseMonthField(month = month, onChange = onMonth, modifier = Modifier.width(220.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search expenses…")
            ExpenseMonthField(month = month, onChange = onMonth, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A field that shows the chosen month ("October 2026") or "All months", with a clear button. Tapping it opens the month picker. */
@Composable
private fun ExpenseMonthField(month: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.nbms.inputBorder, shape)
            .clickable { open = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(NbmsIcons.Calendar, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            text = if (month.isEmpty()) "All months" else expensesMonthLabel(month),
            style = MaterialTheme.typography.bodyLarge,
            color = if (month.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (month.isNotEmpty()) {
            Icon(
                imageVector = NbmsIcons.Close,
                contentDescription = "Clear month",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onChange("") }
            )
        }
    }
    if (open) {
        ExpenseMonthPickerDialog(
            current = month,
            onPick = {
                onChange(it)
                open = false
            },
            onClear = {
                onChange("")
                open = false
            },
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun ExpenseMonthPickerDialog(current: String, onPick: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val parsed = remember(current) { runCatching { YearMonth.parse(current) }.getOrNull() }
    var year by rememberSaveable { mutableStateOf(parsed?.year ?: Year.now().value) }
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
            (1..12).chunked(3).forEach { rowMonths ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowMonths.forEach { m ->
                        val key = YearMonth.of(year, m).toString()
                        NbmsButton(
                            text = java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                            onClick = { onPick(key) },
                            modifier = Modifier.weight(1f),
                            variant = if (key == current) ButtonVariant.Default else ButtonVariant.Outline,
                            size = ButtonSize.Sm
                        )
                    }
                }
            }
            NbmsButton(
                text = "All months",
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm
            )
        }
    }
}

// ── rows ──

private fun expenseDateText(expense: Expense, tz: TimeZone): String = Format.date(expense.date.toInstant(), tz)

@Composable
private fun ExpenseAmountText(expense: Expense, symbol: String, modifier: Modifier = Modifier) {
    Text(
        text = Format.currency(expense.amount, symbol),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nbms.destructive,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
private fun ExpenseCard(expense: Expense, symbol: String, tz: TimeZone) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        expense.title.trim().ifEmpty { "—" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${expensesWords(expense.category)} · ${expenseDateText(expense, tz)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                }
                ExpenseAmountText(expense, symbol)
            }
            AdaptiveSideBySide {
                Text(expensesWords(expense.paymentMethod), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(
                    "Recorded by ${expense.recordedByName.trim().ifEmpty { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpensesTable(rows: List<Expense>, total: Double, symbol: String, tz: TimeZone) {
    var page by remember(rows) { mutableIntStateOf(1) }
    val pageCount = if (rows.isEmpty()) 1 else (rows.size + EXPENSES_TABLE_PAGE_SIZE - 1) / EXPENSES_TABLE_PAGE_SIZE
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * EXPENSES_TABLE_PAGE_SIZE
    val visible = rows.subList(start, minOf(rows.size, start + EXPENSES_TABLE_PAGE_SIZE))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                ExpensesTableRow(header = true) { index ->
                    Text(
                        listOf("Date", "Title", "Category", "Amount", "Payment", "Recorded By")[index],
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                        maxLines = 1
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                visible.forEach { expense ->
                    ExpensesTableRow(header = false) { index ->
                        val body = MaterialTheme.typography.bodyMedium
                        when (index) {
                            0 -> Text(expenseDateText(expense, tz), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            1 -> Text(
                                expense.title.trim().ifEmpty { "—" },
                                style = body.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            2 -> Text(expensesWords(expense.category), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                            3 -> ExpenseAmountText(expense, symbol)
                            4 -> Text(expensesWords(expense.paymentMethod), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                            else -> Text(
                                expense.recordedByName.trim().ifEmpty { "—" },
                                style = body,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                }
                ExpensesTotalRow(total, symbol, Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
            }
        }
        Pagination(page = safePage, pageCount = pageCount, onPage = { page = it.coerceIn(1, pageCount) })
    }
}

@Composable
private fun ExpensesTableRow(header: Boolean, cell: @Composable (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (header) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EXPENSES_COLUMN_WEIGHTS.forEachIndexed { index, weight ->
            Box(Modifier.weight(weight).padding(horizontal = 4.dp)) { cell(index) }
        }
    }
}

/** "Total" in red: the money of the rows that are showing. */
@Composable
private fun ExpensesTotalRow(total: Double, symbol: String, modifier: Modifier = Modifier) {
    val red = MaterialTheme.nbms.destructive
    LabelValueRow(
        label = "Total",
        value = Format.currency(total, symbol),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        valueColor = red,
        valueWeight = FontWeight.Bold,
        labelColor = red,
        labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
private fun ExpensesTotalCard(total: Double, symbol: String) {
    NbmsCard(Modifier.fillMaxWidth()) { ExpensesTotalRow(total, symbol) }
}
