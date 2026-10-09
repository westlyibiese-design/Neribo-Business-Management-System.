package com.westly.nbms.features.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsShadow

/** The amount the person typed, as a whole number of 1 or more. Blank, letters, 0 and anything else give null (the tap does nothing). */
internal fun parseRestockAmount(text: String): Int? = parseWholeNumber(text)?.takeIf { it >= 1 }

/** The Restock button works only when something is typed and nothing is saving. */
internal fun restockEnabled(text: String, saving: Boolean): Boolean = !saving && text.isNotBlank()

/**
 * "Restock: {name}" — a narrow dialog. It has its own frame (the same look as the shared dialog) because the shared one
 * cannot disable its confirm button, and this one must stay disabled while the box is empty or while saving.
 */
@Composable
internal fun RestockDialog(
    item: InventoryItem,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit
) {
    var amountText by rememberSaveable { mutableStateOf("") }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !saving,
            dismissOnClickOutside = !saving,
            usePlatformDefaultWidth = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 384.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .nbmsShadow(8.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.nbms.cardBorder, MaterialTheme.shapes.medium)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Restock: ${item.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 24.dp)
                )
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = buildAnnotatedString {
                        append("Current stock: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${item.quantity} ${item.unit}") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted
                )
                NbmsTextField(
                    value = amountText,
                    onValueChange = { amountText = filterWholeNumberInput(it) },
                    label = "Add Quantity",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "How many to add?",
                    keyboardType = KeyboardType.Number,
                    enabled = !saving
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton(
                        text = "Restock",
                        onClick = {
                            val amount = parseRestockAmount(amountText)
                            if (amount != null) onSubmit(amount)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        loading = saving,
                        enabled = restockEnabled(amountText, saving)
                    )
                    NbmsButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        variant = ButtonVariant.Outline,
                        enabled = !saving
                    )
                }
            }
            Icon(
                imageVector = NbmsIcons.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(16.dp)
                    .alpha(0.7f)
                    .clickable(enabled = !saving, role = Role.Button, onClick = onDismiss)
            )
        }
    }
}
