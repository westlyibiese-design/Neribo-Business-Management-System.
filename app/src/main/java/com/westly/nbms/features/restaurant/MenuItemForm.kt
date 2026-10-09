package com.westly.nbms.features.restaurant

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

private val MENU_CATEGORY_OPTIONS: List<MenuCategory> = MenuCategory.entries

/**
 * The inline Add / Edit form of Menu Management.
 * [editing] = null is a new item (a primary-tinted card titled "New Menu Item", button "Save"); otherwise it replaces the
 * item's card (button "Save Changes"). Fields start at Westly's defaults: Breakfast, price 0, Available.
 * [onSave] gets a form that already passes `canSaveMenuForm`; [saving] shows the spinner and blocks a second tap.
 */
@Composable
internal fun MenuItemForm(
    editing: MenuItem?,
    saving: Boolean,
    imageProvider: ImageFieldProvider?,
    currencySymbol: String,
    onSave: (MenuForm) -> Unit,
    onCancel: () -> Unit
) {
    val key = editing?.id ?: "new"
    val start = remember(key) { editing?.let(::menuFormOf) ?: MenuForm() }
    var name by rememberSaveable(key) { mutableStateOf(start.name) }
    var categoryKey by rememberSaveable(key) { mutableStateOf(start.categoryKey) }
    var image by rememberSaveable(key) { mutableStateOf(start.image) }
    var description by rememberSaveable(key) { mutableStateOf(start.description) }
    var priceText by rememberSaveable(key) { mutableStateOf(start.priceText) }
    var available by rememberSaveable(key) { mutableStateOf(start.available) }

    val form = MenuForm(name, categoryKey, image, description, priceText, available)
    val errors = validateMenuForm(form)

    val fields: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing == null) {
                Text(
                    "New Menu Item",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            NbmsTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name *",
                placeholder = "Grilled Salmon"
            )
            NbmsDropdown(
                label = "Category *",
                options = MENU_CATEGORY_OPTIONS,
                selected = MenuCategory.fromKey(categoryKey),
                onSelect = { categoryKey = it.key },
                optionLabel = { it.label },
                placeholder = "Category"
            )
            if (imageProvider != null) {
                imageProvider.SingleImageField(
                    label = "dish photo",
                    folder = "restaurant-menu",
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
                placeholder = "Describe the dish…",
                singleLine = false
            )
            NbmsTextField(
                value = priceText,
                onValueChange = { priceText = filterMenuPriceInput(it) },
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
                    onClick = { if (canSaveMenuForm(form, saving)) onSave(form) },
                    modifier = Modifier.fillMaxWidth(),
                    loading = saving,
                    enabled = canSaveMenuForm(form, saving) || saving,
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
