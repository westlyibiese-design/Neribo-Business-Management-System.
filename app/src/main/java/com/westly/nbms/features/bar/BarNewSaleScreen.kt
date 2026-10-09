package com.westly.nbms.features.bar

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

private val BAR_TABLET_WIDTH = 840.dp

/** New Bar Sale: record a drink sale at the bar, a room or a table. Drinks on the left (full width on phones), the sale beside or in a bottom sheet. */
@Composable
fun BarNewSaleScreen(session: SessionState.SignedIn, vm: BarNewSaleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val menu by vm.menu.collectAsStateWithLifecycle()
    val symbol = session.business.currencySymbol

    var sheetOpen by remember { mutableStateOf(false) }
    val success = state.success
    LaunchedEffect(success != null) { if (success != null) sheetOpen = false }

    if (success != null) {
        BarSuccessPanel(success, symbol, session.user.usesPin, onNewSale = vm::newSale)
        return
    }

    val total = barCartTotal(state.cart)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BarSaleHeader()

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= BAR_TABLET_WIDTH
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(2f)) { DrinksArea(vm, state, menu, symbol, columns = 3) }
                    NbmsCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) { SalePanel(vm, state, symbol) }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    DrinksArea(vm, state, menu, symbol, columns = 2)
                    Spacer(Modifier.height(88.dp)) // room so the sticky bar never hides the last row
                }
                SaleBar(count = state.cart.size, total = total, symbol = symbol, onClick = { sheetOpen = true })
            }
        }
    }

    if (sheetOpen) {
        NbmsBottomSheet(onDismiss = { sheetOpen = false }) {
            SalePanel(vm, state, symbol)
        }
    }
}

@Composable
private fun BarSaleHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            NbmsIcons.Wine,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("New Bar Sale", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Record a drink sale at the bar, a room, or a table.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── drinks ──

@Composable
private fun DrinksArea(vm: BarNewSaleViewModel, state: BarNewSaleUiState, menu: BarMenuState, symbol: String, columns: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModePill("From Menu", NbmsIcons.Wine, state.mode == BarSaleMode.FROM_MENU) { vm.setMode(BarSaleMode.FROM_MENU) }
            ModePill("Manual Entry", NbmsIcons.Pencil, state.mode == BarSaleMode.MANUAL) { vm.setMode(BarSaleMode.MANUAL) }
        }
        if (state.mode == BarSaleMode.FROM_MENU) DrinkItems(vm, state, menu, symbol, columns) else ManualEntryCard(vm, state)
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
private fun DrinkItems(vm: BarNewSaleViewModel, state: BarNewSaleUiState, menu: BarMenuState, symbol: String, columns: Int) {
    val pills = remember { barCategoryPills() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pills.forEach { (key, label) -> CategoryPill(label, state.categoryKey == key) { vm.setCategory(key) } }
    }
    when {
        menu.loading -> CenteredNote("Loading menu…")
        menu.failed -> ErrorState(MSG_BAR_MENU_LOAD_FAILED, onRetry = vm::retryMenu)
        else -> {
            val shown = remember(menu.items, state.categoryKey) { availableDrinksFor(menu.items, state.categoryKey) }
            if (shown.isEmpty()) {
                CenteredNote("No available drinks in this category. Add drinks in Drinks Menu, or use Manual Entry.")
            } else {
                // The page already scrolls, so the grid is plain rows (a lazy grid cannot live inside a scrolling column).
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.chunked(columns).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            line.forEach { item -> DrinkCard(item, symbol, Modifier.weight(1f)) { vm.onDrinkTapped(item) } }
                            repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center
    )
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
private fun DrinkCard(item: DrinkItem, symbol: String, modifier: Modifier, onClick: () -> Unit) {
    NbmsCard(modifier, onClick = onClick) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface
            )
            Text(item.category.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                Format.currency(item.price, symbol),
                fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ManualEntryCard(vm: BarNewSaleViewModel, state: BarNewSaleUiState) {
    val errors = vm.manualErrors(state)
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(NbmsIcons.Pencil, null, Modifier.size(18.dp))
                Text("Manually Enter Purchase", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Use this for drinks that aren't on the menu. It's recorded and reported exactly like any other sale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NbmsTextField(
                state.manualName, vm::setManualName, "Drink Name / Description",
                placeholder = "e.g. Special cocktail", error = errors?.name
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
                "Add to Sale", vm::addManual, Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary, leadingIcon = NbmsIcons.Plus
            )
        }
    }
}

// ── sale panel ──

@Composable
private fun SalePanel(vm: BarNewSaleViewModel, state: BarNewSaleUiState, symbol: String) {
    val total = barCartTotal(state.cart)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(NbmsIcons.Wine, null, Modifier.size(18.dp))
            Text("Sale (${state.cart.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (state.cart.isEmpty()) {
            CenteredNote("No items added")
        } else {
            state.cart.forEach { line ->
                SaleLineRow(line, symbol, onMinus = { vm.decrement(line.id) }, onPlus = { vm.increment(line.id) })
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                Text("Total", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(Format.currency(total, symbol), fontWeight = FontWeight.Bold)
            }
        }
        NbmsTextField(state.form.roomNumber, vm::setRoomNumber, "Room Number", placeholder = "e.g. 201")
        NbmsTextField(state.form.tableNumber, vm::setTableNumber, "Table Number", placeholder = "e.g. B-03")
        NbmsTextField(state.form.guestName, vm::setGuestName, "Guest Name", placeholder = "Optional")
        NbmsTextField(state.form.notes, vm::setNotes, "Notes", placeholder = "Special requests…", singleLine = false)
        NbmsDropdown(
            label = "Payment Method", options = BarPaymentMethod.entries.toList(), selected = state.form.payment,
            onSelect = vm::setPayment, optionLabel = { it.label }
        )
        NbmsButton(
            text = if (state.busy) "Recording…" else "Record Sale · ${Format.currency(total, symbol)}",
            onClick = vm::submit, modifier = Modifier.fillMaxWidth(),
            loading = state.busy, enabled = !state.busy && state.cart.isNotEmpty()
        )
    }
}

@Composable
private fun SaleLineRow(line: BarCartLine, symbol: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                line.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            if (line.isManual) NbmsBadge("Manual", BadgeTone.Outline)
        }
        RoundStep("−", onMinus)
        Text("${line.quantity}", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
        RoundStep("+", onPlus)
        Text(
            Format.currency(barLineSubtotal(line), symbol), fontSize = 12.sp, fontWeight = FontWeight.Medium,
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
private fun SaleBar(count: Int, total: Double, symbol: String, onClick: () -> Unit) {
    Popup(popupPositionProvider = SaleBottomOfWindow, properties = PopupProperties(focusable = false)) {
        Surface(
            color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        ) {
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(NbmsIcons.Wine, null, Modifier.size(18.dp))
                Text("Sale ($count) · ${Format.currency(total, symbol)}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private object SaleBottomOfWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(0, windowSize.height - popupContentSize.height)
}

// ── success ──

@Composable
private fun BarSuccessPanel(success: BarSaleSuccess, symbol: String, usesPin: Boolean, onNewSale: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.nbms.successContainer),
            contentAlignment = Alignment.Center
        ) { Icon(NbmsIcons.CheckCircle, null, Modifier.size(32.dp), tint = MaterialTheme.nbms.success) }
        Text("Sale Recorded!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${success.itemCount} items · ${Format.currency(success.total, symbol)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
