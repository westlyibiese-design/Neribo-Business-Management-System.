package com.westly.nbms.features.sales

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.LabelValueRow
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.inventory.InventoryItem

/** New Sale: the point-of-sale cart. Items on the left (or full width on phones), the cart beside or in a bottom sheet. */
@Composable
fun NewSaleScreen(session: SessionState.SignedIn, vm: NewSaleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol

    val success = state.success
    if (success != null) {
        SaleSuccessPanel(success, symbol, session.user.usesPin, onNewSale = vm::newSale)
        return
    }

    var sheetOpen by remember { mutableStateOf(false) }
    val total = cartTotal(state.cart)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("New Sale", "Point of Sale — select items and complete transaction")

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(2f)) { ItemsArea(vm, state, catalog, symbol, columns = 3) }
                    NbmsCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) { CartPanel(vm, state, symbol) }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    ItemsArea(vm, state, catalog, symbol, columns = 2)
                    Spacer(Modifier.height(88.dp)) // room so the sticky bar never hides the last row
                }
                CartBar(count = state.cart.size, total = total, symbol = symbol, onClick = { sheetOpen = true })
            }
        }
    }

    if (sheetOpen) {
        NbmsBottomSheet(onDismiss = { sheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                CartPanel(vm, state, symbol)
            }
        }
    }
}

// ── items ──

@Composable
private fun ItemsArea(vm: NewSaleViewModel, state: NewSaleUiState, catalog: CatalogState, symbol: String, columns: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModePill("From Inventory", NbmsIcons.Package, state.mode == ItemMode.INVENTORY) { vm.setMode(ItemMode.INVENTORY) }
            ModePill("Manual Entry", NbmsIcons.Pencil, state.mode == ItemMode.MANUAL) { vm.setMode(ItemMode.MANUAL) }
        }
        if (state.mode == ItemMode.INVENTORY) InventoryItems(vm, state, catalog, symbol, columns) else ManualEntryCard(vm, state)
    }
}

@Composable
private fun ModePill(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryItems(vm: NewSaleViewModel, state: NewSaleUiState, catalog: CatalogState, symbol: String, columns: Int) {
    when {
        catalog.loading -> LoadingState()
        catalog.failed -> ErrorState("We couldn't load the inventory.")
        else -> {
            val categories = remember(catalog.items) { categoriesOf(catalog.items) }
            val shown = remember(catalog.items, state.category) { catalogFor(catalog.items, state.category) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    CategoryPill(if (c == ALL_CATEGORIES) "All" else salesWords(c), state.category == c) { vm.setCategory(c) }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    "No items in stock.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center
                )
            } else {
                // The page already scrolls, so the grid is plain rows (a lazy grid cannot live inside a scrolling column).
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.chunked(columns).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            line.forEach { item -> ItemCard(item, symbol, Modifier.weight(1f)) { vm.onItemTapped(item) } }
                            repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun ItemCard(item: InventoryItem, symbol: String, modifier: Modifier, onClick: () -> Unit) {
    NbmsCard(modifier, onClick = onClick) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface
            )
            Text(salesWords(item.category), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Format.currency(sellingPrice(item.costPerUnit), symbol),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
                NbmsBadge("${item.quantity} left", BadgeTone.Outline)
            }
        }
    }
}

@Composable
private fun ManualEntryCard(vm: NewSaleViewModel, state: NewSaleUiState) {
    val errors = vm.manualErrors(state)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NbmsIcons.Pencil, null, Modifier.size(18.dp))
                Text("Manually Enter Purchase", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Use this for items that aren't in the inventory catalog. It's recorded and reported exactly like any other sale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NbmsTextField(
                state.manualName, vm::setManualName, "Item Name / Description",
                placeholder = "e.g. Custom gift basket", error = errors?.name
            )
            NbmsTextField(
                state.manualPrice, vm::setManualPrice, "Price (each)",
                placeholder = "0.00", keyboardType = KeyboardType.Decimal, error = errors?.price
            )
            NbmsTextField(
                state.manualQuantity, vm::setManualQuantity, "Quantity",
                keyboardType = KeyboardType.Number, error = errors?.quantity
            )
            NbmsButton(
                "Add to Cart", vm::addManual, Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary, leadingIcon = NbmsIcons.Plus
            )
        }
    }
}

// ── cart ──

@Composable
private fun CartPanel(vm: NewSaleViewModel, state: NewSaleUiState, symbol: String) {
    val total = cartTotal(state.cart)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NbmsIcons.ShoppingCart, null, Modifier.size(18.dp))
            Text("Cart (${state.cart.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (state.cart.isEmpty()) {
            Text(
                "Cart is empty", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center
            )
        } else {
            state.cart.forEach { line ->
                CartLine(line, symbol, onMinus = { vm.decrement(line.id) }, onPlus = { vm.increment(line.id) })
            }
            HorizontalDivider()
            LabelValueRow(
                label = "Total",
                value = Format.currency(total, symbol),
                modifier = Modifier.fillMaxWidth(),
                valueWeight = FontWeight.Bold,
                labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                labelColor = MaterialTheme.colorScheme.onSurface
            )
        }
        NbmsTextField(
            state.customerName, vm::setCustomerName, "Customer Name (optional)", placeholder = "Guest name"
        )
        NbmsDropdown(
            label = "Payment", options = PaymentMethod.entries.toList(), selected = state.paymentMethod,
            onSelect = vm::setPaymentMethod, optionLabel = { it.label }
        )
        NbmsButton(
            text = if (state.busy) "Processing…" else "Complete Sale · ${Format.currency(total, symbol)}",
            onClick = vm::submit, modifier = Modifier.fillMaxWidth(),
            loading = state.busy, enabled = !state.busy && state.cart.isNotEmpty()
        )
    }
}

@Composable
private fun CartLine(line: CartItem, symbol: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(line.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (line.isManual) NbmsBadge("Manual", BadgeTone.Outline)
            }
            Text("${Format.currency(line.price, symbol)} each", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RoundStep("−", onMinus)
        Text("${line.quantity}", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
        RoundStep("+", onPlus)
        Text(
            Format.currency(lineTotal(line), symbol), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End, modifier = Modifier.width(72.dp), maxLines = 1
        )
    }
}

@Composable
private fun RoundStep(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(symbol, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}

/** The phone bar pinned to the bottom of the window (the page itself scrolls, so a popup does the pinning). */
@Composable
private fun CartBar(count: Int, total: Double, symbol: String, onClick: () -> Unit) {
    Popup(popupPositionProvider = BottomOfWindow, properties = PopupProperties(focusable = false)) {
        Surface(
            color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        ) {
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(NbmsIcons.ShoppingCart, null, Modifier.size(18.dp))
                Text("Cart ($count) · ${Format.currency(total, symbol)}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private object BottomOfWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(0, windowSize.height - popupContentSize.height)
}

// ── success ──

@Composable
private fun SaleSuccessPanel(success: SaleSuccess, symbol: String, usesPin: Boolean, onNewSale: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.nbms.successContainer),
            contentAlignment = Alignment.Center
        ) { Icon(NbmsIcons.CheckCircle, null, Modifier.size(32.dp), tint = MaterialTheme.nbms.success) }
        Text("Sale Complete!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${success.itemCount} item(s) sold · ${success.timeText}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            success.lines.forEach { l ->
                LabelValueRow(
                    label = "${l.name} ×${l.quantity}",
                    value = Format.currency(lineTotal(l), symbol),
                    labelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    valueStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider()
            LabelValueRow(
                label = "Total",
                value = Format.currency(success.total, symbol),
                modifier = Modifier.fillMaxWidth(),
                valueWeight = FontWeight.Bold,
                labelStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                labelColor = MaterialTheme.colorScheme.onSurface
            )
        }
        if (usesPin) {
            Text(
                "Ending session for security — enter your PIN again for another sale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            NbmsButton("New Sale", onNewSale, Modifier.fillMaxWidth())
        }
    }
}
