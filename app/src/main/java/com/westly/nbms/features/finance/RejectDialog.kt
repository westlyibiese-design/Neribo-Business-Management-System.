package com.westly.nbms.features.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.util.Format

/** The sentence at the top of the Reject dialog. */
internal fun rejectDialogText(txn: RevenueTransaction, symbol: String): String =
    "Rejecting ${Format.currency(txn.amount, symbol)} from ${txn.guestName}. " +
        "It will be excluded from all revenue totals but kept in the record for audit purposes."

/**
 * "Reject Payment": explains what rejecting does and asks for an optional reason.
 * [onConfirm] receives the reason as typed (the view model trims it and turns a blank into nothing).
 * While [loading] the buttons ignore taps, so the payment cannot be rejected twice.
 */
@Composable
internal fun RejectDialog(
    txn: RevenueTransaction,
    symbol: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by rememberSaveable { mutableStateOf("") }
    NbmsDialog(
        title = "Reject Payment",
        onDismiss = { if (!loading) onDismiss() },
        confirmText = "Reject Payment",
        onConfirm = { if (!loading) onConfirm(reason) },
        destructive = true,
        loading = loading
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                rejectDialogText(txn, symbol),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NbmsTextField(
                value = reason,
                onValueChange = { reason = it },
                label = "Reason (optional)",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "e.g. duplicate entry, unverified amount…",
                singleLine = false,
                enabled = !loading
            )
        }
    }
}
