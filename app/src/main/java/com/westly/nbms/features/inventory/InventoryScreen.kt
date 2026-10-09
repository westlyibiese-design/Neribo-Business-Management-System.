package com.westly.nbms.features.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.Pagination
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.colors
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

private val INVENTORY_TABLET_WIDTH = 600.dp
private const val INVENTORY_TABLE_PAGE_SIZE = 15

// Table columns: Item, Category, Stock, Min. Stock, Unit Cost, Supplier, action.
private val INVENTORY_COLUMN_WEIGHTS = listOf(2.0f, 1.4f, 1.2f, 1.2f, 1.2f, 1.4f, 1.3f)
private val INVENTORY_COLUMN_TITLES = listOf("Item", "Category", "Stock", "Min. Stock", "Unit Cost", "Supplier", "")

// Tailwind orange palette (Appendix B §7.2 low-stock card).
private val Orange50 = Color(0xFFFFF7ED)
private val Orange200 = Color(0xFFFED7AA)
private val Orange400 = Color(0xFFFB923C)
private val Orange500 = Color(0xFFF97316)
private val Orange600 = Color(0xFFEA580C)
private val Orange700 = Color(0xFFC2410C)
private val Orange800 = Color(0xFF9A3412)

/** One choice in the category filter: the "All Categories" entry has an empty key. */
private val INVENTORY_FILTER_OPTIONS: List<InventoryCategoryOption> =
    listOf(InventoryCategoryOption("", "All Categories")) + INVENTORY_CATEGORY_OPTIONS

/** The Inventory page (`inventory`): stock items, the low-stock alert, Add Item and Restock. */
@Composable
fun InventoryScreen(session: SessionState.SignedIn) {
    val vm: InventoryViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    val symbol = session.business.currencySymbol
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var restockId by rememberSaveable { mutableStateOf<String?>(null) }

    val ready = view as? InventoryView.Ready
    val restockItem = restockId?.let { id -> ready?.all?.firstOrNull { it.id == id } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= INVENTORY_TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeader(
                title = "Inventory",
                subtitle = ready?.let { "${it.totalCount} items" }
            ) {
                NbmsButton(text = "Add Item", onClick = { showAdd = true }, leadingIcon = NbmsIcons.Plus)
            }

            if (ready != null && ready.lowItems.isNotEmpty()) {
                LowStockBanner(ready.lowItems)
            }

            InventoryFilterBar(
                wide = wide,
                search = filters.search,
                onSearch = vm::setSearch,
                categoryKey = filters.categoryKey,
                onCategory = vm::setCategory
            )

            when (val v = view) {
                is InventoryView.Loading -> LoadingState()
                is InventoryView.Error -> ErrorState(v.message, onRetry = vm::retry)
                is InventoryView.Ready -> {
                    if (v.rows.isEmpty()) {
                        EmptyState(icon = NbmsIcons.Package, title = "No items found", message = "")
                    } else if (wide) {
                        InventoryTable(v.rows, symbol, onRestock = { restockId = it.id })
                    } else {
                        PagedList(items = v.rows, key = { it.id }) { item ->
                            InventoryCard(item, symbol, onRestock = { restockId = item.id })
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddItemDialog(
            wide = LocalConfiguration.current.screenWidthDp.dp >= INVENTORY_TABLET_WIDTH,
            currencySymbol = symbol,
            saving = saving,
            onDismiss = { if (!saving) showAdd = false },
            onSubmit = { form -> vm.addItem(form) { showAdd = false } }
        )
    }

    if (restockItem != null) {
        RestockDialog(
            item = restockItem,
            saving = saving,
            onDismiss = { if (!saving) restockId = null },
            onSubmit = { amount -> vm.restock(restockItem, amount) { restockId = null } }
        )
    }
}

// ── colours ──

@Composable
private fun lowTextColor(): Color = if (MaterialTheme.nbms.isDark) Orange400 else Orange600

@Composable
private fun lowRowBackground(): Color = if (MaterialTheme.nbms.isDark) Orange500.copy(alpha = 0.05f) else Orange50.copy(alpha = 0.5f)

// ── banner ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LowStockBanner(items: List<InventoryItem>) {
    val dark = MaterialTheme.nbms.isDark
    val shape = MaterialTheme.shapes.large
    val container = if (dark) Orange500.copy(alpha = 0.10f) else Orange50
    val border = if (dark) Orange800 else Orange200
    val heading = if (dark) Orange400 else Orange700
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = heading, modifier = Modifier.size(16.dp))
            Text(
                lowStockHeading(items.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = heading
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> LowStockChip(lowStockChipText(item), chipColor = if (dark) Orange400 else Orange700) }
        }
    }
}

/** Outline chip: orange border and text, 12sp. */
@Composable
private fun LowStockChip(text: String, chipColor: Color) {
    val shape = MaterialTheme.shapes.small
    Text(
        text = text,
        color = chipColor,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(shape)
            .border(1.dp, Orange400, shape)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    )
}

// ── filters ──

@Composable
private fun InventoryFilterBar(
    wide: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    categoryKey: String,
    onCategory: (String) -> Unit
) {
    val dropdown: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "",
            options = INVENTORY_FILTER_OPTIONS,
            selected = INVENTORY_FILTER_OPTIONS.firstOrNull { it.key == categoryKey },
            onSelect = { onCategory(it.key) },
            optionLabel = { it.label },
            modifier = m,
            placeholder = "Category"
        )
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search inventory…", modifier = Modifier.weight(1f))
            dropdown(Modifier.width(220.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(value = search, onValueChange = onSearch, placeholder = "Search inventory…")
            dropdown(Modifier.fillMaxWidth())
        }
    }
}

// ── pieces shared by the card and the table row ──

private fun unitCostText(item: InventoryItem, symbol: String): String =
    if (item.costPerUnit == 0.0) "—" else Format.currency(item.costPerUnit, symbol)

private fun supplierText(item: InventoryItem): String = item.supplier?.trim().orEmpty().ifEmpty { "—" }

/** Name (medium) with an orange warning icon after it when the item is low. */
@Composable
private fun ItemNameWithAlert(item: InventoryItem, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            item.name.trim().ifEmpty { "—" },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (InventoryLogic.isLow(item)) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = "Low stock", tint = lowTextColor(), modifier = Modifier.size(14.dp))
        }
    }
}

/** Small secondary badge, 10sp, capitalised. */
@Composable
private fun CategoryBadge(category: String) {
    val c = BadgeTone.Secondary.colors()
    val shape = MaterialTheme.shapes.small
    Text(
        text = inventoryWords(category),
        color = c.content,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(c.container)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** The quantity in semibold (orange when low) followed by the unit in 12sp muted. */
@Composable
private fun StockText(item: InventoryItem) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.quantity.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (InventoryLogic.isLow(item)) lowTextColor() else MaterialTheme.colorScheme.onSurface
        )
        Text(item.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small outline button, 28dp high, refresh icon. */
@Composable
private fun RestockButton(onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.nbms.buttonOutline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(NbmsIcons.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(14.dp))
        Text(
            "Restock",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

// ── phone card ──

@Composable
private fun LabeledValue(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

@Composable
private fun InventoryCard(item: InventoryItem, symbol: String, onRestock: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.typography.bodyMedium
    val low = InventoryLogic.isLow(item)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (low) lowRowBackground() else Color.Transparent)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                ItemNameWithAlert(item, Modifier.weight(1f))
                CategoryBadge(item.category)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Stock", Modifier.weight(1f)) { StockText(item) }
                LabeledValue("Min. Stock", Modifier.weight(1f)) { Text("${item.minStock} ${item.unit}", style = body, color = muted) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Unit Cost", Modifier.weight(1f)) {
                    Text(unitCostText(item, symbol), style = body, color = MaterialTheme.colorScheme.onSurface)
                }
                LabeledValue("Supplier", Modifier.weight(1f)) {
                    Text(supplierText(item), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RestockButton(onRestock)
            }
        }
    }
}

// ── tablet table ──

@Composable
private fun InventoryTable(rows: List<InventoryItem>, symbol: String, onRestock: (InventoryItem) -> Unit) {
    var page by remember(rows) { mutableIntStateOf(1) }
    val pageCount = if (rows.isEmpty()) 1 else (rows.size + INVENTORY_TABLE_PAGE_SIZE - 1) / INVENTORY_TABLE_PAGE_SIZE
    val safePage = page.coerceIn(1, pageCount)
    val start = (safePage - 1) * INVENTORY_TABLE_PAGE_SIZE
    val visible = rows.subList(start, minOf(rows.size, start + INVENTORY_TABLE_PAGE_SIZE))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                InventoryTableRow(header = true, background = Color.Transparent) { index ->
                    Text(INVENTORY_COLUMN_TITLES[index], style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                visible.forEach { item ->
                    val low = InventoryLogic.isLow(item)
                    InventoryTableRow(header = false, background = if (low) lowRowBackground() else Color.Transparent) { index ->
                        val body = MaterialTheme.typography.bodyMedium
                        when (index) {
                            0 -> ItemNameWithAlert(item)
                            1 -> CategoryBadge(item.category)
                            2 -> StockText(item)
                            3 -> Text("${item.minStock} ${item.unit}", style = body, color = muted, maxLines = 1)
                            4 -> Text(unitCostText(item, symbol), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            5 -> Text(supplierText(item), style = body, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else -> RestockButton { onRestock(item) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                }
            }
        }
        Pagination(page = safePage, pageCount = pageCount, onPage = { page = it.coerceIn(1, pageCount) })
    }
}

@Composable
private fun InventoryTableRow(header: Boolean, background: Color, cell: @Composable (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = if (header) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        INVENTORY_COLUMN_WEIGHTS.forEachIndexed { index, weight ->
            Box(Modifier.weight(weight).padding(horizontal = 4.dp)) { cell(index) }
        }
    }
}
