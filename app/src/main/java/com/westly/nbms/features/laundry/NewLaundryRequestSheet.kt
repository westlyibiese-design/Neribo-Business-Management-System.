package com.westly.nbms.features.laundry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField

/** The "New Laundry Request" bottom sheet. */
@Composable
fun NewLaundryRequestSheet(
    state: NewLaundryUiState,
    symbol: String,
    onChange: ((LaundryRequestForm) -> LaundryRequestForm) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.form
    NbmsBottomSheet(onDismiss = onCancel, title = "New Laundry Request") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = form.guestName,
                onValueChange = { v -> onChange { it.copy(guestName = v) } },
                label = "Guest Name",
                modifier = Modifier.weight(1f),
                enabled = !state.saving
            )
            NbmsTextField(
                value = form.roomNumber,
                onValueChange = { v -> onChange { it.copy(roomNumber = v) } },
                label = "Room Number",
                modifier = Modifier.weight(1f),
                placeholder = "e.g. 201",
                enabled = !state.saving
            )
        }
        NbmsTextField(
            value = form.items,
            onValueChange = { v -> onChange { it.copy(items = v) } },
            label = "Items",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "e.g. 3 shirts, 2 trousers, 1 suit",
            singleLine = false,
            enabled = !state.saving
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = form.itemCount,
                onValueChange = { v -> onChange { it.copy(itemCount = v) } },
                label = "Item Count",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
                enabled = !state.saving
            )
            NbmsTextField(
                value = form.charge,
                onValueChange = { v -> onChange { it.copy(charge = v) } },
                label = "Service Charge ($symbol)",
                modifier = Modifier.weight(1f),
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                enabled = !state.saving
            )
        }
        NbmsDropdown(
            label = "Payment Method",
            options = LaundryPaymentMethod.entries,
            selected = form.paymentMethod,
            onSelect = { m -> onChange { it.copy(paymentMethod = m) } },
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.saving
        )
        NbmsTextField(
            value = form.notes,
            onValueChange = { v -> onChange { it.copy(notes = v) } },
            label = "Notes",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Special instructions…",
            singleLine = false,
            enabled = !state.saving
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(text = "Log Request", onClick = onSubmit, modifier = Modifier.fillMaxWidth(), loading = state.saving)
            NbmsButton(
                text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline, enabled = !state.saving
            )
        }
    }
}
