package com.westly.nbms.features.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.ImageFieldProvider

/**
 * Full-screen form sheet used by the room and venue forms: title bar with a close button,
 * a scrolling body, and Cancel / confirm buttons at the bottom.
 */
@Composable
internal fun FormSheet(
    title: String,
    confirmText: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = NbmsIcons.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(enabled = !saving, role = Role.Button, onClick = onDismiss)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NbmsButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                        enabled = !saving
                    )
                    NbmsButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        loading = saving
                    )
                }
            }
        }
    }
}

/** A price typed back into the form: 45000.0 -> "45000", 45000.5 -> "45000.5". */
internal fun priceInputText(price: Double): String =
    if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()

/** Keeps digits (and one dot when [allowDot]) for number boxes. */
internal fun cleanNumberInput(raw: String, allowDot: Boolean): String {
    val out = StringBuilder()
    var seenDot = false
    for (c in raw) {
        when {
            c.isDigit() -> out.append(c)
            allowDot && c == '.' && !seenDot -> { out.append(c); seenDot = true }
        }
    }
    return out.toString()
}

/** "Add New Room" / "Edit Room". [editing] is null when adding. */
@Composable
fun RoomFormDialog(
    editing: Room?,
    existingRooms: List<Room>,
    currencySymbol: String,
    saving: Boolean,
    imageProvider: ImageFieldProvider?,
    onDismiss: () -> Unit,
    onSave: (RoomFormInput) -> Unit
) {
    val key = editing?.id ?: "new"
    var number by rememberSaveable(key) { mutableStateOf(editing?.number ?: "") }
    var floor by rememberSaveable(key) { mutableStateOf(editing?.floor ?: "") }
    var type by rememberSaveable(key) { mutableStateOf(editing?.type ?: ROOM_TYPES.first()) }
    var name by rememberSaveable(key) { mutableStateOf(editing?.name ?: "") }
    var priceText by rememberSaveable(key) { mutableStateOf(editing?.let { priceInputText(it.price) } ?: "") }
    var capacityText by rememberSaveable(key) { mutableStateOf(editing?.capacity?.toString() ?: "2") }
    var statusKey by rememberSaveable(key) { mutableStateOf(editing?.status ?: RoomStatus.AVAILABLE.key) }
    var amenitiesText by rememberSaveable(key) { mutableStateOf(editing?.amenities?.joinToString(", ") ?: "") }
    var description by rememberSaveable(key) { mutableStateOf(editing?.description ?: "") }
    // Images are kept as one text (one URL per line) so the form survives a screen rotation.
    var imagesText by rememberSaveable(key) { mutableStateOf(editing?.images?.joinToString("\n") ?: "") }
    var errors by remember { mutableStateOf(RoomFormErrors()) }

    val images = parseImageUrls(imagesText)
    val typeOptions = if (type in ROOM_TYPES) ROOM_TYPES else ROOM_TYPES + type
    val status = RoomStatus.fromKey(statusKey) ?: RoomStatus.AVAILABLE

    FormSheet(
        title = if (editing == null) "Add New Room" else "Edit Room",
        confirmText = if (editing == null) "Add Room" else "Save Changes",
        saving = saving,
        onDismiss = onDismiss,
        onConfirm = {
            val input = RoomFormInput(
                number = number,
                floor = floor,
                type = type,
                name = name,
                priceText = priceText,
                capacityText = capacityText,
                status = status,
                amenitiesText = amenitiesText,
                description = description,
                images = images
            )
            val found = validateRoomForm(input, existingRooms, editing?.id)
            errors = found
            if (!found.hasErrors) onSave(input)
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = number,
                onValueChange = { number = it; errors = errors.copy(number = null) },
                label = "Room Number *",
                placeholder = "101",
                error = errors.number,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
            NbmsTextField(
                value = floor,
                onValueChange = { floor = it },
                label = "Floor",
                placeholder = "1",
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
        }
        NbmsDropdown(
            label = "Room Type *",
            options = typeOptions,
            selected = type,
            onSelect = { type = it },
            optionLabel = { it },
            enabled = !saving
        )
        NbmsTextField(
            value = name,
            onValueChange = { name = it },
            label = "Room Name",
            placeholder = "e.g. Ocean View Deluxe (optional — shown on the website instead of the room type)",
            enabled = !saving
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = priceText,
                onValueChange = { priceText = cleanNumberInput(it, allowDot = true); errors = errors.copy(price = null) },
                label = "Price per Night ($currencySymbol) *",
                placeholder = "45000",
                keyboardType = KeyboardType.Decimal,
                error = errors.price,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
            NbmsTextField(
                value = capacityText,
                onValueChange = { capacityText = cleanNumberInput(it, allowDot = false); errors = errors.copy(capacity = null) },
                label = "Capacity",
                placeholder = "2",
                keyboardType = KeyboardType.Number,
                error = errors.capacity,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
        }
        if (imageProvider != null) {
            imageProvider.MultiImageField(
                label = "room images",
                folder = "rooms",
                urls = images,
                onChange = { imagesText = it.joinToString("\n") }
            )
        } else {
            ImageUrlListField(
                label = "Room Images (image URLs, one per line)",
                urls = images,
                onChange = { imagesText = it.joinToString("\n") }
            )
        }
        NbmsDropdown(
            label = "Status",
            options = RoomStatus.entries.toList(),
            selected = status,
            onSelect = { statusKey = it.key },
            optionLabel = { it.label },
            enabled = !saving
        )
        NbmsTextField(
            value = amenitiesText,
            onValueChange = { amenitiesText = it },
            label = "Amenities (comma-separated)",
            placeholder = "WiFi, TV, AC, Minibar",
            enabled = !saving
        )
        NbmsTextField(
            value = description,
            onValueChange = { description = it },
            label = "Description",
            placeholder = "Brief room description",
            singleLine = false,
            enabled = !saving
        )
    }
}
