package com.westly.nbms.features.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.inventory.InventoryItem

/** Bar Inventory (`bar-inventory`): the drinks stock of the shared inventory, with Restock and (super admin, manager) Add Item. */
@Composable
fun BarInventoryScreen(session: SessionState.SignedIn, vm: BarInventoryViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var restockId by rememberSaveable { mutableStateOf<String?>(null) }

    val ready = view as? BarStockView.Ready
    val restockItem = restockId?.let { id -> ready?.all?.firstOrNull { it.id == id } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(NbmsIcons.Wine, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            PageHeader("Bar Inventory", ready?.let { barStockSubtitle(it.all.size) }, Modifier.weight(1f)) {
                if (vm.canAdd) {
                    NbmsButton(text = "Add Item", onClick = { showAdd = true }, size = ButtonSize.Icon, leadingIcon = NbmsIcons.Plus)
                }
            }
        }

        if (ready != null && ready.lowItems.isNotEmpty()) LowStockBanner(ready.lowItems)

        SearchBar(search, vm::setSearch, "Search drinks stock…", Modifier.fillMaxWidth())

        when (val v = view) {
            is BarStockView.Loading -> LoadingState()
            is BarStockView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is BarStockView.Ready ->
                if (v.rows.isEmpty()) {
                    EmptyState(NbmsIcons.Package, "No drink stock items found", "")
                } else {
                    PagedList(v.rows, key = { it.id }) { item ->
                        BarStockCard(item, symbol, vm.canRestock, onRestock = { restockId = item.id })
                    }
                }
        }
    }

    if (showAdd) {
        AddBarStockDialog(
            saving = saving,
            onDismiss = { if (!saving) showAdd = false },
            onSubmit = { form -> vm.addItem(form) { showAdd = false } }
        )
    }

    if (restockItem != null) {
        RestockBarStockDialog(
            item = restockItem,
            saving = saving,
            onDismiss = { if (!saving) restockId = null },
            onSubmit = { amount -> vm.restock(restockItem, amount) { restockId = null } }
        )
    }
}

// ── low-stock banner (amber) ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LowStockBanner(items: List<InventoryItem>) {
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(nbms.warningContainer)
            .border(1.dp, nbms.warning, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = nbms.onWarningContainer, modifier = Modifier.size(16.dp))
            Text(
                barStockLowHeading(items.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = nbms.onWarningContainer
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Text(
                    barStockLowChip(item),
                    color = nbms.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .border(1.dp, nbms.warning, MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ── item card ──

@Composable
private fun LabeledValue(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

@Composable
private fun BarStockCard(item: InventoryItem, symbol: String, canRestock: Boolean, onRestock: () -> Unit) {
    val low = isBarStockLow(item)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.name.trim().ifEmpty { "—" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (low) NbmsBadge("Low", BadgeTone.Warning)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Quantity", Modifier.weight(1f)) {
                    Text(
                        "${item.quantity} ${item.unit}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (low) MaterialTheme.nbms.onWarningContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
                LabeledValue("Cost per unit", Modifier.weight(1f)) {
                    Text(
                        if (item.costPerUnit == 0.0) "—" else Format.currency(item.costPerUnit, symbol),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (canRestock) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { RestockButton(onRestock) }
            }
        }
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
        Text("Restock", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

// ── dialogs ──

/** "Add Bar Stock Item": Name *, Quantity, Unit (default "bottles"), Cost per unit (₦), Low-stock threshold. */
@Composable
private fun AddBarStockDialog(saving: Boolean, onDismiss: () -> Unit, onSubmit: (BarStockForm) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(BAR_STOCK_DEFAULT_UNIT) }
    var cost by rememberSaveable { mutableStateOf("") }
    var threshold by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val form = BarStockForm(name, quantity, unit, cost, threshold)
    val errors = if (submitted) validateBarStockForm(form) else BarStockErrors()

    NbmsDialog(
        title = "Add Bar Stock Item",
        onDismiss = onDismiss,
        confirmText = "Add Item",
        onConfirm = {
            if (validateBarStockForm(form).any) {
                submitted = true
            } else {
                onSubmit(form)
            }
        },
        loading = saving
    ) {
        NbmsTextField(
            value = name, onValueChange = { name = it }, label = "Name *", modifier = Modifier.fillMaxWidth(),
            error = errors.name, enabled = !saving
        )
        NbmsTextField(
            value = quantity, onValueChange = { quantity = filterBarWholeInput(it) }, label = "Quantity",
            modifier = Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, error = errors.quantity, enabled = !saving
        )
        NbmsTextField(
            value = unit, onValueChange = { unit = it }, label = "Unit", modifier = Modifier.fillMaxWidth(), enabled = !saving
        )
        NbmsTextField(
            value = cost, onValueChange = { cost = filterBarCostInput(it) }, label = "Cost per unit (₦)",
            modifier = Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal, error = errors.cost, enabled = !saving
        )
        NbmsTextField(
            value = threshold, onValueChange = { threshold = filterBarWholeInput(it) }, label = "Low-stock threshold",
            modifier = Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, error = errors.threshold, enabled = !saving
        )
    }
}

/** "Restock: {name}": one quantity box (a whole number of 1 or more). */
@Composable
private fun RestockBarStockDialog(item: InventoryItem, saving: Boolean, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val amount = parseBarRestockAmount(amountText)

    NbmsDialog(
        title = "Restock: ${item.name}",
        onDismiss = onDismiss,
        confirmText = "Restock",
        onConfirm = {
            if (amount == null) {
                submitted = true
            } else {
                onSubmit(amount)
            }
        },
        loading = saving,
        description = "Current stock: ${item.quantity} ${item.unit}"
    ) {
        NbmsTextField(
            value = amountText,
            onValueChange = { amountText = filterBarWholeInput(it) },
            label = "Add Quantity",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "How many to add?",
            keyboardType = KeyboardType.Number,
            error = if (submitted && amount == null) MSG_BAR_STOCK_RESTOCK_AMOUNT else null,
            enabled = !saving
        )
    }
}
