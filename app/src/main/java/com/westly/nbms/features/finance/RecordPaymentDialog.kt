package com.westly.nbms.features.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.session.SessionUser

/** One choice in a dropdown: [key] is what is stored, [label] is what the person reads. */
internal data class RecordPaymentOption(val key: String, val label: String)

internal val PAYMENT_TYPE_OPTIONS = listOf(
    RecordPaymentOption("room_payment", "Room Payment"),
    RecordPaymentOption("walk_in_payment", "Walk-In"),
    RecordPaymentOption("deposit", "Deposit"),
    RecordPaymentOption("refund", "Refund"),
    RecordPaymentOption("other", "Other")
)

internal val PAYMENT_METHOD_OPTIONS = listOf(
    RecordPaymentOption("cash", "Cash"),
    RecordPaymentOption("credit_card", "Credit Card"),
    RecordPaymentOption("debit_card", "Debit Card"),
    RecordPaymentOption("bank_transfer", "Bank Transfer"),
    RecordPaymentOption("mobile_payment", "Mobile Payment")
)

/** What the person typed. [type] and [method] hold the stored keys. */
internal data class RecordPaymentForm(
    val guestName: String = "",
    val amountText: String = "",
    val type: String = "room_payment",
    val method: String = "cash",
    val bookingId: String = "",
    val notes: String = ""
)

internal data class RecordPaymentErrors(val guestName: String? = null, val amount: String? = null) {
    val any: Boolean get() = guestName != null || amount != null
}

/** Which alert a new payment sends: a refund tells the finance team a refund was issued; anything else says a payment came in. */
internal enum class PaymentAlert { REFUND_ISSUED, PAYMENT_RECEIVED }

internal fun paymentAlertFor(type: String): PaymentAlert =
    if (type == "refund") PaymentAlert.REFUND_ISSUED else PaymentAlert.PAYMENT_RECEIVED

/** On a shared device (PIN login) the session ends after a payment is saved, so the next person must sign in again. */
internal fun shouldEndPinSession(user: SessionUser): Boolean = user.usesPin

/** The typed amount as a number: digits with at most one dot, and 0 or more. Anything else is null. */
internal fun parsePaymentAmount(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return null
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps only digits and the first dot while the person types an amount. */
internal fun filterPaymentAmountInput(text: String): String {
    var seenDot = false
    val out = StringBuilder()
    for (c in text) {
        if (c in '0'..'9') out.append(c)
        else if (c == '.' && !seenDot) {
            seenDot = true
            out.append(c)
        }
    }
    return out.toString()
}

internal fun validateRecordPaymentForm(form: RecordPaymentForm): RecordPaymentErrors = RecordPaymentErrors(
    guestName = if (form.guestName.isBlank()) "Guest name is required." else null,
    amount = if (parsePaymentAmount(form.amountText) == null) "Enter an amount of 0 or more." else null
)

/**
 * The new `payments/{id}` document. It is always pending, with the approval fields empty and not deleted.
 * `createdAt` is left out on purpose: [com.westly.nbms.core.data.BusinessFirestore.add] stamps a Map that has no
 * `createdAt` with the server time.
 */
internal fun buildPaymentPayload(form: RecordPaymentForm, user: SessionUser): Map<String, Any?> = mapOf(
    "guestName" to form.guestName.trim(),
    "amount" to (parsePaymentAmount(form.amountText) ?: 0.0),
    "paymentMethod" to form.method,
    "type" to form.type,
    "bookingId" to form.bookingId.trim().ifEmpty { null },
    "notes" to form.notes.trim().ifEmpty { null },
    "recordedBy" to user.uid,
    "recordedByName" to user.name,
    "approvalStatus" to "pending",
    "approvedBy" to null,
    "approvedByName" to null,
    "approvedAt" to null,
    "rejectedReason" to null,
    "isDeleted" to false
)

/** The "Record Payment" form. [saving] shows the spinner on Save; [onSubmit] gets a form that has already passed the checks. */
@Composable
internal fun RecordPaymentDialog(
    currencySymbol: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RecordPaymentForm) -> Unit
) {
    var guestName by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var typeKey by rememberSaveable { mutableStateOf("room_payment") }
    var methodKey by rememberSaveable { mutableStateOf("cash") }
    var bookingId by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }

    val form = RecordPaymentForm(guestName, amountText, typeKey, methodKey, bookingId, notes)
    val errors = if (tried) validateRecordPaymentForm(form) else RecordPaymentErrors()

    NbmsDialog(
        title = "Record Payment",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = {
            tried = true
            if (!validateRecordPaymentForm(form).any) onSubmit(form)
        },
        dismissText = "Cancel",
        loading = saving
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = guestName,
                onValueChange = { guestName = it },
                label = "Guest Name *",
                modifier = Modifier.fillMaxWidth(),
                error = errors.guestName
            )
            NbmsTextField(
                value = amountText,
                onValueChange = { amountText = filterPaymentAmountInput(it) },
                label = "Amount ($currencySymbol) *",
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Decimal,
                error = errors.amount
            )
            NbmsDropdown(
                label = "Payment Type",
                options = PAYMENT_TYPE_OPTIONS,
                selected = PAYMENT_TYPE_OPTIONS.firstOrNull { it.key == typeKey },
                onSelect = { typeKey = it.key },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
            NbmsDropdown(
                label = "Payment Method",
                options = PAYMENT_METHOD_OPTIONS,
                selected = PAYMENT_METHOD_OPTIONS.firstOrNull { it.key == methodKey },
                onSelect = { methodKey = it.key },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
            NbmsTextField(
                value = bookingId,
                onValueChange = { bookingId = it },
                label = "Booking ID (optional)",
                modifier = Modifier.fillMaxWidth()
            )
            NbmsTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Notes",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = NbmsIcons.Clock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "This payment will appear as Pending Approval until an Accountant reviews it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
