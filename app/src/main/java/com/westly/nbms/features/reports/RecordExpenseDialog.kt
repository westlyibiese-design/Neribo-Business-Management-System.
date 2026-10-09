package com.westly.nbms.features.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.session.SessionUser
import java.time.LocalDate
import java.time.ZoneId

/** One choice in a dropdown: [key] is what is stored, [label] is what the person reads. */
internal data class RecordExpenseOption(val key: String, val label: String)

internal val EXPENSE_CATEGORY_OPTIONS: List<RecordExpenseOption> =
    ExpenseCategory.entries.map { RecordExpenseOption(it.key, it.label) }

internal val EXPENSE_PAYMENT_METHOD_OPTIONS: List<RecordExpenseOption> =
    ExpensePaymentMethod.entries.map { RecordExpenseOption(it.key, it.label) }

/** What the person typed. [categoryKey] and [methodKey] hold the stored keys; [date] is the chosen calendar day. */
internal data class RecordExpenseForm(
    val title: String = "",
    val amountText: String = "",
    val date: LocalDate,
    val categoryKey: String = "other",
    val methodKey: String = "cash",
    val notes: String = ""
)

internal data class RecordExpenseErrors(val title: String? = null, val amount: String? = null) {
    val any: Boolean get() = title != null || amount != null
}

/** The typed amount as a number: digits with at most one dot, and 0 or more. Anything else is null. */
internal fun parseExpenseAmount(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return null
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps only digits and the first dot while the person types an amount. */
internal fun filterExpenseAmountInput(text: String): String {
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

internal fun validateRecordExpenseForm(form: RecordExpenseForm): RecordExpenseErrors = RecordExpenseErrors(
    title = if (form.title.isBlank()) "Title is required." else null,
    amount = if (parseExpenseAmount(form.amountText) == null) "Enter an amount of 0 or more." else null
)

/**
 * The new `expenses/{id}` document. `date` is the chosen day at 00:00 in the business time zone [zone].
 * `createdAt` is left out on purpose: [com.westly.nbms.core.data.BusinessFirestore.add] stamps a Map that has no
 * `createdAt` with the server time.
 */
internal fun buildExpensePayload(form: RecordExpenseForm, user: SessionUser, zone: ZoneId): Map<String, Any?> {
    val start = form.date.atStartOfDay(zone).toInstant()
    return mapOf(
        "title" to form.title.trim(),
        "amount" to (parseExpenseAmount(form.amountText) ?: 0.0),
        "category" to ExpenseCategory.fromKey(form.categoryKey).key,
        "date" to Timestamp(start.epochSecond, start.nano),
        "description" to form.notes.trim().ifEmpty { null },
        "paymentMethod" to ExpensePaymentMethod.fromKey(form.methodKey).key,
        "recordedBy" to user.uid,
        "recordedByName" to user.name,
        "isDeleted" to false
    )
}

/** The "Record Expense" form. [saving] shows the spinner on Save; [onSubmit] gets a form that has already passed the checks. */
@Composable
internal fun RecordExpenseDialog(
    currencySymbol: String,
    zone: ZoneId,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (RecordExpenseForm) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    var categoryKey by rememberSaveable { mutableStateOf("other") }
    var methodKey by rememberSaveable { mutableStateOf("cash") }
    var notes by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }

    val form = RecordExpenseForm(title, amountText, LocalDate.parse(dateText), categoryKey, methodKey, notes)
    val errors = if (tried) validateRecordExpenseForm(form) else RecordExpenseErrors()

    NbmsDialog(
        title = "Record Expense",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = {
            tried = true
            if (!validateRecordExpenseForm(form).any) onSubmit(form)
        },
        dismissText = "Cancel",
        loading = saving
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = title,
                onValueChange = { title = it },
                label = "Title *",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Electricity bill, Supplies…",
                error = errors.title
            )
            NbmsTextField(
                value = amountText,
                onValueChange = { amountText = filterExpenseAmountInput(it) },
                label = "Amount ($currencySymbol) *",
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Decimal,
                error = errors.amount
            )
            NbmsDatePickerField(
                label = "Date *",
                value = kotlinx.datetime.LocalDate.parse(dateText),
                onChange = { dateText = it.toString() },
                modifier = Modifier.fillMaxWidth()
            )
            NbmsDropdown(
                label = "Category",
                options = EXPENSE_CATEGORY_OPTIONS,
                selected = EXPENSE_CATEGORY_OPTIONS.firstOrNull { it.key == categoryKey },
                onSelect = { categoryKey = it.key },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
            NbmsDropdown(
                label = "Payment Method",
                options = EXPENSE_PAYMENT_METHOD_OPTIONS,
                selected = EXPENSE_PAYMENT_METHOD_OPTIONS.firstOrNull { it.key == methodKey },
                onSelect = { methodKey = it.key },
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth()
            )
            NbmsTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Notes",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Optional",
                singleLine = false
            )
        }
    }
}
