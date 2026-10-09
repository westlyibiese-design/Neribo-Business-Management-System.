package com.westly.nbms.features.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField

/** One choice in the category dropdown: [key] is what is stored, [label] is what the person reads. */
internal data class InventoryCategoryOption(val key: String, val label: String)

internal val INVENTORY_CATEGORY_OPTIONS: List<InventoryCategoryOption> =
    InventoryCategory.entries.map { InventoryCategoryOption(it.key, it.label) }

/**
 * The "Add Inventory Item" form. Fields start at Westly's defaults (category Hotel Supplies, unit pcs, the rest empty);
 * the dialog leaves the screen after a save, so the next time it opens it is back at those defaults.
 * [saving] shows the spinner on Add Item; [onSubmit] gets a form that has already passed the checks.
 */
@Composable
internal fun AddItemDialog(
    wide: Boolean,
    currencySymbol: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (AddItemForm) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var categoryKey by rememberSaveable { mutableStateOf(InventoryCategory.HOTEL_SUPPLIES.key) }
    var unit by rememberSaveable { mutableStateOf("pcs") }
    var quantityText by rememberSaveable { mutableStateOf("") }
    var minStockText by rememberSaveable { mutableStateOf("") }
    var costText by rememberSaveable { mutableStateOf("") }
    var supplier by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }

    val form = AddItemForm(name, categoryKey, unit, quantityText, minStockText, costText, supplier)
    val errors = if (tried) validateAddItemForm(form) else AddItemErrors()

    val nameField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = name,
            onValueChange = { name = it },
            label = "Item Name *",
            modifier = m,
            error = errors.name
        )
    }
    val categoryField: @Composable (Modifier) -> Unit = { m ->
        NbmsDropdown(
            label = "Category",
            options = INVENTORY_CATEGORY_OPTIONS,
            selected = INVENTORY_CATEGORY_OPTIONS.firstOrNull { it.key == categoryKey },
            onSelect = { categoryKey = it.key },
            optionLabel = { it.label },
            modifier = m,
            placeholder = "Category"
        )
    }
    val unitField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = unit,
            onValueChange = { unit = it },
            label = "Unit",
            modifier = m,
            placeholder = "pcs, bottles…"
        )
    }
    val quantityField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = quantityText,
            onValueChange = { quantityText = filterWholeNumberInput(it) },
            label = "Initial Quantity *",
            modifier = m,
            keyboardType = KeyboardType.Number,
            error = errors.quantity
        )
    }
    val minStockField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = minStockText,
            onValueChange = { minStockText = filterWholeNumberInput(it) },
            label = "Min. Stock Alert *",
            modifier = m,
            keyboardType = KeyboardType.Number,
            error = errors.minStock
        )
    }
    val costField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = costText,
            onValueChange = { costText = filterCostInput(it) },
            label = "Cost per Unit ($currencySymbol)",
            modifier = m,
            keyboardType = KeyboardType.Decimal,
            error = errors.cost
        )
    }
    val supplierField: @Composable (Modifier) -> Unit = { m ->
        NbmsTextField(
            value = supplier,
            onValueChange = { supplier = it },
            label = "Supplier",
            modifier = m
        )
    }

    NbmsDialog(
        title = "Add Inventory Item",
        onDismiss = onDismiss,
        confirmText = "Add Item",
        onConfirm = {
            tried = true
            if (!validateAddItemForm(form).any) onSubmit(form)
        },
        dismissText = "Cancel",
        loading = saving
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            nameField(Modifier.fillMaxWidth())
            if (wide) {
                TwoColumns(categoryField, unitField)
                TwoColumns(quantityField, minStockField)
                TwoColumns(costField, supplierField)
            } else {
                categoryField(Modifier.fillMaxWidth())
                unitField(Modifier.fillMaxWidth())
                quantityField(Modifier.fillMaxWidth())
                minStockField(Modifier.fillMaxWidth())
                costField(Modifier.fillMaxWidth())
                supplierField(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Two fields side by side, each taking half the width (the tablet layout). */
@Composable
private fun TwoColumns(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}
