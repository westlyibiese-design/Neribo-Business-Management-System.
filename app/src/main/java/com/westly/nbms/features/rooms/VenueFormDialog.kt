package com.westly.nbms.features.rooms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.ImageFieldProvider

/** "Add Venue" / "Edit Venue". [editing] is null when adding. */
@Composable
fun VenueFormDialog(
    editing: Venue?,
    currencySymbol: String,
    saving: Boolean,
    imageProvider: ImageFieldProvider?,
    onDismiss: () -> Unit,
    onSave: (VenueFormInput) -> Unit
) {
    val key = editing?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(editing?.name ?: "") }
    var description by rememberSaveable(key) { mutableStateOf(editing?.description ?: "") }
    var size by rememberSaveable(key) { mutableStateOf(editing?.size ?: "") }
    var capacityText by rememberSaveable(key) { mutableStateOf(editing?.capacity?.toString() ?: "") }
    var priceText by rememberSaveable(key) { mutableStateOf(editing?.price?.let { priceInputText(it) } ?: "") }
    var amenitiesText by rememberSaveable(key) { mutableStateOf(editing?.amenities?.joinToString(", ") ?: "") }
    var available by rememberSaveable(key) { mutableStateOf(editing?.available ?: true) }
    // One URL per line, kept as text so the form survives a screen rotation.
    var imagesText by rememberSaveable(key) { mutableStateOf(editing?.images?.joinToString("\n") ?: "") }
    var errors by remember { mutableStateOf(VenueFormErrors()) }

    val images = parseImageUrls(imagesText)

    FormSheet(
        title = if (editing == null) "Add Venue" else "Edit Venue",
        confirmText = if (editing == null) "Add Venue" else "Save Changes",
        saving = saving,
        onDismiss = onDismiss,
        onConfirm = {
            val input = VenueFormInput(
                name = name,
                description = description,
                size = size,
                capacityText = capacityText,
                priceText = priceText,
                amenitiesText = amenitiesText,
                images = images,
                available = available
            )
            val found = validateVenueForm(input)
            errors = found
            // A missing name is reported by the view model with the "Name required" toast.
            if (!found.hasErrors) onSave(input)
        }
    ) {
        NbmsTextField(
            value = name,
            onValueChange = { name = it },
            label = "Venue Name *",
            placeholder = "e.g. Grand Ballroom",
            enabled = !saving
        )
        NbmsTextField(
            value = description,
            onValueChange = { description = it },
            label = "Description",
            placeholder = "Describe the venue — layout, ambience, ideal use cases…",
            singleLine = false,
            enabled = !saving
        )
        NbmsTextField(
            value = size,
            onValueChange = { size = it },
            label = "Size",
            placeholder = "e.g. 450 sqm",
            enabled = !saving
        )
        NbmsTextField(
            value = capacityText,
            onValueChange = { capacityText = cleanNumberInput(it, allowDot = false); errors = errors.copy(capacity = null) },
            label = "Guest Capacity",
            placeholder = "200",
            keyboardType = KeyboardType.Number,
            error = errors.capacity,
            enabled = !saving
        )
        NbmsTextField(
            value = priceText,
            onValueChange = { priceText = cleanNumberInput(it, allowDot = true); errors = errors.copy(price = null) },
            label = "Price ($currencySymbol, optional)",
            placeholder = "Leave blank for 'Contact for pricing'",
            keyboardType = KeyboardType.Decimal,
            error = errors.price,
            enabled = !saving
        )
        if (imageProvider != null) {
            imageProvider.MultiImageField(
                label = "venue images",
                folder = "venues",
                urls = images,
                onChange = { imagesText = it.joinToString("\n") }
            )
        } else {
            ImageUrlListField(
                label = "Venue Images (image URLs, one per line)",
                urls = images,
                onChange = { imagesText = it.joinToString("\n") }
            )
        }
        NbmsTextField(
            value = amenitiesText,
            onValueChange = { amenitiesText = it },
            label = "Amenities (comma-separated)",
            placeholder = "Stage, AV Equipment, Catering, Parking",
            enabled = !saving
        )
        NbmsSwitch(
            checked = available,
            onCheckedChange = { available = it },
            label = "Available for booking",
            enabled = !saving
        )
    }
}
