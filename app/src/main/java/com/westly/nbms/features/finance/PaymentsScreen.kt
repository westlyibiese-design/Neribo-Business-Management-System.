package com.westly.nbms.features.finance

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.io.File
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val PAYMENTS_TABLET_WIDTH = 600.dp
private const val PAYMENTS_TABLE_PAGE_SIZE = 15
private val PaymentsAmber = Color(0xFFD97706)
private val PaymentsRed = Color(0xFFEF4444)

// Table column widths (Date, Guest, Type, Amount, Method, Status, Recorded By).
private val PAYMENTS_COLUMN_WEIGHTS = listOf(1.1f, 1.5f, 1.2f, 1.1f, 1.1f, 1.6f, 1.3f)

/** The Payments page (`payments`): every payment of the business, with Export and, for some roles, Record Payment. */
@Composable
fun PaymentsScreen(session: SessionState.SignedIn) {
    val vm: PaymentsViewModel = hiltViewModel()
    val toast = hiltViewModel<ToastViewModel>().controller
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val ending by vm.endingSession.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = session.business.currencySymbol
    val zone = remember(session.business.timezone) { paymentsZoneOf(session.business.timezone) }
    val canRecord = paymentsCanRecord(session.user.role)
    var showRecord by rememberSaveable { mutableStateOf(false) }

    val ready = view as? PaymentsView.Ready

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= PAYMENTS_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PaymentsHeader(
                wide = wide,
                totals = ready?.totals,
                symbol = symbol,
                canExport = ready != null,
                canRecord = canRecord,
                onExport = {
                    val current = view as? PaymentsView.Ready ?: return@PaymentsHeader
                    val csv = buildPaymentsCsv(current.rows) { paymentsCsvDate(it, zone) }
                    val fileName = paymentsCsvFileName(filters.month)
                    scope.launch {
                        try {
                            sharePaymentsCsv(context, csv, fileName)
                        } catch (e: Exception) {
                            toast.show(
                                message = e.message ?: "The file could not be shared.",
                                type = ToastType.Error,
                                title = "Error"
                            )
                        }
                    }
                },
                onRecord = { showRecord = true }
            )

            PaymentsFilterBar(
                wide = wide,
                search = filters.search,
                onSearch = vm::setSearch,
                month = filters.month,
                onMonth = vm::setMonth
            )

            when (val v = view) {
                is PaymentsView.Loading -> LoadingState()
                is PaymentsView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is PaymentsView.Ready -> {
                    if (v.rows.isEmpty()) {
                        EmptyState(icon = NbmsIcons.Banknote, title = "No payments found", message = "")
                    } else if (wide) {
                        PaymentsTable(v.rows, v.totals.approved, symbol, zone)
                    } else {
                        PagedList(items = v.rows, key = { it.id }) { doc -> PaymentCard(doc, symbol, zone) }
                        PaymentsTotalCard(v.totals.approved, symbol)
                    }
                }
            }
        }
    }

    if (showRecord && canRecord) {
        RecordPaymentDialog(
            currencySymbol = symbol,
            saving = saving,
            onDismiss = { if (!saving) showRecord = false },
            onSubmit = { form -> vm.save(form, session) { showRecord = false } }
        )
    }

    if (ending) PinSessionEndingOverlay()
}

// ── header and filters ──

@Composable
private fun PaymentsHeader(
    wide: Boolean,
    totals: PaymentTotals?,
    symbol: String,
    canExport: Boolean,
    canRecord: Boolean,
    onExport: () -> Unit,
    onRecord: () -> Unit
) {
    val green = MaterialTheme.nbms.success
    val subtitle = totals?.let {
        buildAnnotatedString {
            withStyle(SpanStyle(color = green, fontWeight = FontWeight.SemiBold)) {
                append("Approved: ${Format.currency(it.approved, symbol)}")
            }
            if (it.pending > 0.0) {
                append(" · ")
                withStyle(SpanStyle(color = PaymentsAmber)) {
                    append("Pending: ${Format.currency(it.pending, symbol)}")
                }
            }
        }
    }
    val titleBlock: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text("Payments", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (canRecord) {
            NbmsButton(text = "Record Payment", onClick = onRecord, leadingIcon = NbmsIcons.Plus)
        }
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

@Composable
private fun PaymentsFilterBar(
    wide: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    month: String,
    onMonth: (String) -> Unit
) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search guest, type…", modifier = Modifier.weight(1f))
            MonthFilterField(month = month, onChange = onMonth, modifier = Modifier.width(220.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search guest, type…")
            MonthFilterField(month = month, onChange = onMonth, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A field that shows the chosen month ("October 2026") or "All months", with a clear button. Tapping it opens the month picker. */
@Composable
private fun MonthFilterField(month: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
            text = if (month.isEmpty()) "All months" else paymentsMonthLabel(month),
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
        MonthPickerDialog(
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
private fun MonthPickerDialog(current: String, onPick: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
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

@Composable
private fun paymentDateText(doc: PaymentDoc, zone: ZoneId): String {
    val tz = remember(zone) { runCatching { TimeZone.of(zone.id) }.getOrElse { TimeZone.currentSystemDefault() } }
    return Format.date(doc.createdAt.toInstant(), tz)
}

@Composable
private fun PaymentAmountText(doc: PaymentDoc, symbol: String, modifier: Modifier = Modifier) {
    val style = paymentAmountStyle(paymentStatusKey(doc.approvalStatus))
    val color = when (style) {
        PaymentAmountStyle.APPROVED -> MaterialTheme.nbms.success
        PaymentAmountStyle.REJECTED -> PaymentsRed
        PaymentAmountStyle.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = Format.currency(doc.amount, symbol),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (style.strikethrough) TextDecoration.LineThrough else TextDecoration.None,
        modifier = modifier
    )
}

/** "Pending Approval" (amber, clock), "Approved" (green, check) or "Rejected" (red, cross). */
@Composable
private fun PaymentStatusBadge(statusKey: String) {
    val (label, icon, pillKey) = when (statusKey) {
        "approved" -> Triple("Approved", NbmsIcons.CheckCircle, "approved")
        "rejected" -> Triple("Rejected", NbmsIcons.XCircle, "rejected")
        else -> Triple("Pending Approval", NbmsIcons.Clock, "awaiting_approval")
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
        Text(label, color = colors.content, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun PaymentCard(doc: PaymentDoc, symbol: String, zone: ZoneId) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val guest = doc.guestName?.trim().orEmpty().ifEmpty { "—" }
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        guest,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${paymentsTypeLabel(doc.type)} · ${paymentDateText(doc, zone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                }
                PaymentStatusBadge(paymentStatusKey(doc.approvalStatus))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PaymentAmountText(doc, symbol)
                Text(paymentsMethodLabel(doc.paymentMethod), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(
                "Recorded by ${doc.recordedByName?.trim().orEmpty().ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
        }
    }
}

@Composable
private fun PaymentsTable(rows: List<PaymentDoc>, approvedTotal: Double, symbol: String, zone: ZoneId) {
    var page by remember(rows) { mutableIntStateOf(1) }
    val pageCount = if (rows.isEmpty()) 1 else (rows.size + PAYMENTS_TABLE_PAGE_SIZE - 1) / PAYMENTS_TABLE_PAGE_SIZE
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * PAYMENTS_TABLE_PAGE_SIZE
    val visible = rows.subList(start, minOf(rows.size, start + PAYMENTS_TABLE_PAGE_SIZE))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                PaymentsTableRow(header = true) { index ->
                    Text(
                        listOf("Date", "Guest", "Type", "Amount", "Method", "Status", "Recorded By")[index],
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                        maxLines = 1
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                visible.forEach { doc ->
                    PaymentsTableRow(header = false) { index ->
                        val body = MaterialTheme.typography.bodyMedium
                        when (index) {
                            0 -> Text(paymentDateText(doc, zone), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            1 -> Text(
                                doc.guestName?.trim().orEmpty().ifEmpty { "—" },
                                style = body.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            2 -> Text(paymentsTypeLabel(doc.type), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                            3 -> PaymentAmountText(doc, symbol)
                            4 -> Text(paymentsMethodLabel(doc.paymentMethod), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                            5 -> PaymentStatusBadge(paymentStatusKey(doc.approvalStatus))
                            else -> Text(
                                doc.recordedByName?.trim().orEmpty().ifEmpty { "—" },
                                style = body,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                }
                PaymentsTotalRow(approvedTotal, symbol, Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
            }
        }
        Pagination(page = safePage, pageCount = pageCount, onPage = { page = it.coerceIn(1, pageCount) })
    }
}

@Composable
private fun PaymentsTableRow(header: Boolean, cell: @Composable (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (header) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PAYMENTS_COLUMN_WEIGHTS.forEachIndexed { index, weight ->
            Box(Modifier.weight(weight).padding(horizontal = 4.dp)) { cell(index) }
        }
    }
}

/** "Total (Approved)" in green: the approved money of the rows that are showing. */
@Composable
private fun PaymentsTotalRow(approvedTotal: Double, symbol: String, modifier: Modifier = Modifier) {
    val green = MaterialTheme.nbms.success
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Total (Approved)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = green)
        Text(Format.currency(approvedTotal, symbol), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = green)
    }
}

@Composable
private fun PaymentsTotalCard(approvedTotal: Double, symbol: String) {
    NbmsCard(Modifier.fillMaxWidth()) { PaymentsTotalRow(approvedTotal, symbol) }
}

// ── shared-device sign-out overlay ──

/** Full-screen dim layer shown for 2.5 seconds after a PIN user saves a payment, just before the session ends. */
@Composable
private fun PinSessionEndingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("Ending session for security…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

// ── export ──

/**
 * Writes the CSV into the app's cache folder `exports/` (the folder the FileProvider declared in Phase 0/8 already shares)
 * and opens the Android share sheet for it.
 */
private suspend fun sharePaymentsCsv(context: Context, csv: String, fileName: String) {
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
    val chooser = Intent.createChooser(send, "Export payments").apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
