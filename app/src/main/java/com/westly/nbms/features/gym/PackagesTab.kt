package com.westly.nbms.features.gym

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms

private val PACKAGES_TWO_COLUMN_WIDTH = 600.dp

internal const val PACKAGES_TITLE = "Membership Packages"
internal const val PACKAGES_EMPTY = "No membership packages yet. This section is hidden on the public page until you add one."
internal const val PACKAGES_DELETE_MESSAGE =
    "This removes it from the gym page immediately. Existing members already on this package keep their membership — only the public listing is removed."
internal const val PACKAGES_DURATION_HELP = "Words like Weekly, Monthly, Quarterly, Annual set the membership length."
internal const val PACKAGES_PRICE_ERROR = "Enter a number that is 0 or more."

private data class PackageDraft(
    val id: String = "",
    val name: String = "",
    val priceText: String = "",
    val duration: String = GymFormRules.DEFAULT_DURATION,
    val features: List<String> = emptyList(),
    val popular: Boolean = false
)

/** Membership Packages tab. These are the packages Phase 29 offers when a member is registered or renewed. */
@Composable
internal fun PackagesTab(vm: GymContentViewModel, state: GymContentUiState, currencySymbol: String) {
    val items = state.content.packages
    var draft by remember { mutableStateOf<PackageDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<PackageItem?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabHeading(GymFormRules.countHeading(PACKAGES_TITLE, items.size, GymContentLimits.PACKAGES)) {
            if (draft == null) {
                NbmsButton(
                    text = "Add Package",
                    onClick = { draft = PackageDraft() },
                    size = ButtonSize.Sm,
                    enabled = GymContentRules.canAddPackage(items.size) && !state.saving,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        draft?.let { current ->
            PackageForm(
                draft = current,
                onDraft = { draft = it },
                symbol = currencySymbol,
                saving = state.saving,
                onSave = {
                    val price = GymFormRules.parsePrice(current.priceText)
                    if (price != null) {
                        vm.savePackage(
                            PackageItem(
                                id = current.id,
                                name = current.name,
                                price = price,
                                duration = current.duration,
                                features = current.features,
                                popular = current.popular
                            )
                        ) { ok -> if (ok) draft = null }
                    }
                },
                onCancel = { draft = null }
            )
        }

        if (items.isEmpty()) {
            EmptyNote(PACKAGES_EMPTY)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= PACKAGES_TWO_COLUMN_WIDTH) 2 else 1
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.withIndex().toList().chunked(columns).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { (index, item) ->
                                PackageCard(
                                    item = item,
                                    symbol = currencySymbol,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < items.lastIndex,
                                    saving = state.saving,
                                    onUp = { vm.movePackage(item.id, -1) },
                                    onDown = { vm.movePackage(item.id, 1) },
                                    onEdit = {
                                        draft = PackageDraft(
                                            id = item.id,
                                            name = item.name,
                                            priceText = GymFormRules.priceToText(item.price),
                                            duration = item.duration,
                                            features = item.features,
                                            popular = item.popular
                                        )
                                    },
                                    onDelete = { pendingDelete = item },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteConfirm(
            name = item.name,
            message = PACKAGES_DELETE_MESSAGE,
            onConfirm = {
                pendingDelete = null
                vm.deletePackage(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun PackageCard(
    item: PackageItem,
    symbol: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    saving: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    NbmsCard(modifier) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.popular) {
                        Icon(Icons.Filled.Star, contentDescription = "Most popular", tint = popularStarColor(), modifier = Modifier.size(16.dp))
                    }
                }
                IconAction(Icons.Outlined.ArrowUpward, "Move up", !saving && canMoveUp, onUp)
                IconAction(Icons.Outlined.ArrowDownward, "Move down", !saving && canMoveDown, onDown)
                IconAction(NbmsIcons.Pencil, "Edit ${item.name}", !saving, onEdit)
                IconAction(NbmsIcons.Trash, "Delete ${item.name}", !saving, onDelete)
            }
            Text(
                GymFormRules.packageSubtitle(item.duration, item.price, symbol),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.features.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item.features.forEach { feature ->
                        Text("• $feature", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageForm(
    draft: PackageDraft,
    onDraft: (PackageDraft) -> Unit,
    symbol: String,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val parsedPrice = GymFormRules.parsePrice(draft.priceText)
    var featureInput by remember { mutableStateOf("") }
    val addFeature = {
        onDraft(draft.copy(features = GymFormRules.addFeature(draft.features, featureInput)))
        featureInput = ""
    }

    FormCard {
        NbmsTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            label = "Package Name *",
            placeholder = "Premium Monthly"
        )
        NbmsTextField(
            value = draft.priceText,
            onValueChange = { onDraft(draft.copy(priceText = it)) },
            label = "Price ($symbol)",
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            error = if (parsedPrice == null) PACKAGES_PRICE_ERROR else null
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NbmsTextField(
                value = draft.duration,
                onValueChange = { onDraft(draft.copy(duration = it)) },
                label = "Duration Label",
                placeholder = "Monthly / 30 days"
            )
            Text(PACKAGES_DURATION_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Features", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureEntryField(
                    value = featureInput,
                    onValueChange = { featureInput = it },
                    onSubmit = addFeature,
                    modifier = Modifier.weight(1f)
                )
                NbmsButton(
                    text = "Add",
                    onClick = addFeature,
                    variant = ButtonVariant.Outline,
                    enabled = featureInput.isNotBlank()
                )
            }
            if (draft.features.isNotEmpty()) {
                FlowChips {
                    draft.features.forEachIndexed { index, feature ->
                        FeatureChip(feature, onRemove = { onDraft(draft.copy(features = GymFormRules.removeFeature(draft.features, index))) })
                    }
                }
            }
        }
        NbmsSwitch(
            checked = draft.popular,
            onCheckedChange = { onDraft(draft.copy(popular = it)) },
            label = "Mark as \"Most Popular\""
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Save",
                onClick = onSave,
                loading = saving,
                enabled = GymContentRules.isValidPackage(draft.name) && parsedPrice != null && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Outline)
        }
    }
}

/** One feature the package lists, with an × to remove it. */
@Composable
private fun FeatureChip(text: String, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(scheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
        Icon(NbmsIcons.Close, contentDescription = "Remove $text", tint = scheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
    }
}

/** A one-line field that adds the feature when Enter / Done is pressed on the keyboard (the shared field cannot do that). */
@Composable
private fun FeatureEntryField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .border(1.dp, if (focused) MaterialTheme.nbms.ring else MaterialTheme.nbms.inputBorder, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text("Add a feature, e.g. Full equipment access", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        }
    )
}
