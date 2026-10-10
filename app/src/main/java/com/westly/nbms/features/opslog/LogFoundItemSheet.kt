package com.westly.nbms.features.opslog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.feature.ImageFieldProvider

/** The "Log Found Item" bottom sheet (scrolls). */
@Composable
fun LogFoundItemSheet(
    state: LogFoundUiState,
    rooms: List<LostFoundRoomOption>,
    imageProvider: ImageFieldProvider?,
    onChange: ((LogFoundForm) -> LogFoundForm) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.form
    val enabled = !state.saving
    NbmsBottomSheet(onDismiss = onCancel, title = "Log Found Item") {
        NbmsTextField(
            value = form.itemName,
            onValueChange = { v -> onChange { it.copy(itemName = v) } },
            label = "Item Name *",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "e.g. Phone charger, Wristwatch",
            enabled = enabled
        )
        NbmsTextField(
            value = form.description,
            onValueChange = { v -> onChange { it.copy(description = v) } },
            label = "Description *",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Color, brand, distinguishing details…",
            error = state.descriptionError,
            singleLine = false,
            enabled = enabled
        )

        NbmsDropdown(
            label = "Room *",
            options = rooms,
            selected = rooms.firstOrNull { it.id == form.roomId },
            onSelect = { room -> onChange { it.copy(roomId = room.id, manualRoom = "") } },
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Select room…",
            enabled = enabled
        )
        // The manual field shows only while no room is chosen.
        if (form.roomId == null) {
            NbmsTextField(
                value = form.manualRoom,
                onValueChange = { v -> onChange { it.copy(manualRoom = v) } },
                label = "Or type a room number manually",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "e.g. 201",
                enabled = enabled
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Date & Time Found *", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsDatePickerField(
                    label = "",
                    value = form.foundDate,
                    onChange = { d -> onChange { it.copy(foundDate = d) } },
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
                NbmsTimePickerField(
                    label = "",
                    value = form.foundTime,
                    onChange = { t -> onChange { it.copy(foundTime = t) } },
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
            }
        }

        NbmsTextField(
            value = form.foundBy,
            onValueChange = { v -> onChange { it.copy(foundBy = v) } },
            label = "Found By *",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Housekeeper's name",
            enabled = enabled
        )
        NbmsDropdown(
            label = "Current Status",
            options = ItemStatus.entries,
            selected = form.status,
            onSelect = { s -> onChange { it.copy(status = s) } },
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
        NbmsTextField(
            value = form.notes,
            onValueChange = { v -> onChange { it.copy(notes = v) } },
            label = "Additional Notes (optional)",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Any other details",
            singleLine = false,
            enabled = enabled
        )

        if (imageProvider != null) {
            imageProvider.SingleImageField(
                label = "item photo",
                folder = "lost-found",
                url = form.photoUrl.ifBlank { null },
                onChange = { url -> onChange { it.copy(photoUrl = url.orEmpty()) } },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            NbmsTextField(
                value = form.photoUrl,
                onValueChange = { v -> onChange { it.copy(photoUrl = v) } },
                label = "Photo URL (optional)",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "https://…",
                enabled = enabled
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(text = "Log Item", onClick = onSubmit, modifier = Modifier.fillMaxWidth(), loading = state.saving)
            NbmsButton(
                text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline, enabled = enabled
            )
        }
    }
}
