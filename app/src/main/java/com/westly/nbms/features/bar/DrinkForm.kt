package com.westly.nbms.features.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.ImageFieldProvider

private val DRINK_CATEGORY_OPTIONS: List<DrinkCategory> = DrinkCategory.entries

/**
 * The inline Add / Edit form of the Drinks Menu.
 * [editing] = null is a new item (a primary-tinted card titled "New Drink", button "Save"); otherwise it replaces the
 * item's card (button "Save Changes"). Fields start at the defaults: Beer, price 0, Available.
 * [onSave] gets a form that already passes `canSaveDrinkForm`; [saving] shows the spinner and blocks a second tap.
 */
@Composable
internal fun DrinkForm(
    editing: DrinkItem?,
    saving: Boolean,
    imageProvider: ImageFieldProvider?,
    currencySymbol: String,
    onSave: (DrinkFormState) -> Unit,
    onCancel: () -> Unit
) {
    val key = editing?.id ?: "new"
    val start = remember(key) { editing?.let(::drinkFormOf) ?: DrinkFormState() }
    var name by rememberSaveable(key) { mutableStateOf(start.name) }
    var categoryKey by rememberSaveable(key) { mutableStateOf(start.categoryKey) }
    var image by rememberSaveable(key) { mutableStateOf(start.image) }
    var description by rememberSaveable(key) { mutableStateOf(start.description) }
    var priceText by rememberSaveable(key) { mutableStateOf(start.priceText) }
    var available by rememberSaveable(key) { mutableStateOf(start.available) }

    val form = DrinkFormState(name, categoryKey, image, description, priceText, available)
    val errors = validateDrinkForm(form)

    val fields: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing == null) {
                Text(
                    "New Drink",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            NbmsTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name *",
                placeholder = "Heineken 60cl"
            )
            NbmsDropdown(
                label = "Category *",
                options = DRINK_CATEGORY_OPTIONS,
                selected = drinkCategoryFromKey(categoryKey),
                onSelect = { categoryKey = it.key },
                optionLabel = { it.label },
                placeholder = "Category"
            )
            if (imageProvider != null) {
                imageProvider.SingleImageField(
                    label = "drink photo",
                    folder = "bar-menu",
                    url = image.ifBlank { null },
                    onChange = { image = it.orEmpty() }
                )
            } else {
                NbmsTextField(
                    value = image,
                    onValueChange = { image = it },
                    label = "Image URL (optional)",
                    placeholder = "https://…",
                    keyboardType = KeyboardType.Uri
                )
            }
            NbmsTextField(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "Describe the drink…",
                singleLine = false
            )
            NbmsTextField(
                value = priceText,
                onValueChange = { priceText = filterDrinkPriceInput(it) },
                label = "Price ($currencySymbol) *",
                keyboardType = KeyboardType.Decimal,
                error = errors.price
            )
            NbmsSwitch(
                checked = available,
                onCheckedChange = { available = it },
                label = if (available) "Available" else "Unavailable"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NbmsButton(
                    text = if (editing == null) "Save" else "Save Changes",
                    onClick = { if (canSaveDrinkForm(form, saving)) onSave(form) },
                    modifier = Modifier.fillMaxWidth(),
                    loading = saving,
                    enabled = canSaveDrinkForm(form, saving) || saving,
                    leadingIcon = NbmsIcons.CheckCircle
                )
                NbmsButton(
                    text = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline,
                    enabled = !saving
                )
            }
        }
    }

    if (editing == null) {
        val shape = MaterialTheme.shapes.large
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), shape)
        ) { fields() }
    } else {
        NbmsCard(Modifier.fillMaxWidth()) { fields() }
    }
}
