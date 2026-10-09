package com.westly.nbms.features.laundry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsTextField

/** "Update Charge": one field, Cancel / Save. Invalid or negative numbers are ignored by the view model. */
@Composable
fun ChargeDialog(request: LaundryRequest, symbol: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(request.id) { mutableStateOf(chargeFieldText(request.charge)) }
    NbmsDialog(
        title = "Update Charge",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = { onSave(text) },
        dismissText = "Cancel"
    ) {
        NbmsTextField(
            value = text,
            onValueChange = { text = it },
            label = "Service Charge ($symbol)",
            modifier = Modifier,
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal
        )
    }
}
