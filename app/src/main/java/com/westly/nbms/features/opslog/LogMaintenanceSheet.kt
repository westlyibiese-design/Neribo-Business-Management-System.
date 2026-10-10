package com.westly.nbms.features.opslog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBottomSheet
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsTextField

/** The "Log Maintenance Request" bottom sheet (scrolls). */
@Composable
fun LogMaintenanceSheet(
    state: LogMaintenanceUiState,
    rooms: List<MaintenanceRoomOption>,
    onChange: ((MaintenanceForm) -> MaintenanceForm) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.form
    val enabled = !state.saving
    NbmsBottomSheet(onDismiss = onCancel, title = "Log Maintenance Request") {
        NbmsDropdown(
            label = "Room *",
            options = rooms,
            selected = rooms.firstOrNull { it.id == form.roomId },
            onSelect = { room -> onChange { it.copy(roomId = room.id) } },
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Select room…",
            enabled = enabled
        )
        NbmsTextField(
            value = form.title,
            onValueChange = { v -> onChange { it.copy(title = v) } },
            label = "Issue Title *",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "e.g. AC not cooling, Leaking faucet",
            enabled = enabled
        )
        NbmsTextField(
            value = form.description,
            onValueChange = { v -> onChange { it.copy(description = v) } },
            label = "Description",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Optional details",
            singleLine = false,
            enabled = enabled
        )
        NbmsDropdown(
            label = "Priority",
            options = MAINTENANCE_PRIORITY_OPTIONS,
            selected = form.priority,
            onSelect = { p -> onChange { it.copy(priority = p) } },
            optionLabel = { it.label },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(text = "Log Request", onClick = onSubmit, modifier = Modifier.fillMaxWidth(), loading = state.saving)
            NbmsButton(
                text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline, enabled = enabled
            )
        }
    }
}
