package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

internal const val EQUIPMENT_TITLE = "Equipment & Services"
internal const val EQUIPMENT_EMPTY = "No equipment or services listed yet."

private data class EquipmentDraft(
    val id: String = "",
    val name: String = "",
    val icon: String = GymIcons.DEFAULT,
    val image: String = "",
    val description: String = ""
)

/** Equipment & Services tab: list with reorder, edit and delete; every change saves at once. */
@Composable
internal fun EquipmentTab(vm: GymContentViewModel, state: GymContentUiState) {
    val items = state.content.equipment
    var draft by remember { mutableStateOf<EquipmentDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<EquipmentItem?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabHeading(GymFormRules.countHeading(EQUIPMENT_TITLE, items.size, GymContentLimits.EQUIPMENT)) {
            if (draft == null) {
                NbmsButton(
                    text = "Add Item",
                    onClick = { draft = EquipmentDraft() },
                    size = ButtonSize.Sm,
                    enabled = GymContentRules.canAddEquipment(items.size) && !state.saving,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        draft?.let { current ->
            EquipmentForm(
                draft = current,
                onDraft = { draft = it },
                providers = vm.imageProviders,
                saving = state.saving,
                onSave = {
                    vm.saveEquipmentItem(
                        EquipmentItem(
                            id = current.id,
                            name = current.name,
                            image = current.image,
                            description = current.description,
                            icon = current.icon
                        )
                    ) { ok -> if (ok) draft = null }
                },
                onCancel = { draft = null }
            )
        }

        if (items.isEmpty()) {
            EmptyNote(EQUIPMENT_EMPTY)
        } else {
            items.forEachIndexed { index, item ->
                NbmsCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReorderButtons(
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            enabled = !state.saving,
                            onUp = { vm.moveEquipmentItem(item.id, -1) },
                            onDown = { vm.moveEquipmentItem(item.id, 1) }
                        )
                        Thumbnail(item.image, 64.dp)
                        NameAndDescription(item.name, item.description, Modifier.weight(1f))
                        Column {
                            IconAction(NbmsIcons.Pencil, "Edit ${item.name}", !state.saving) {
                                draft = EquipmentDraft(item.id, item.name, item.icon, item.image, item.description)
                            }
                            IconAction(NbmsIcons.Trash, "Delete ${item.name}", !state.saving) { pendingDelete = item }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteConfirm(
            name = item.name,
            message = DELETE_MESSAGE_DEFAULT,
            onConfirm = {
                pendingDelete = null
                vm.deleteEquipmentItem(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun EquipmentForm(
    draft: EquipmentDraft,
    onDraft: (EquipmentDraft) -> Unit,
    providers: Set<com.westly.nbms.core.feature.ImageFieldProvider>,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    FormCard {
        NbmsTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            label = "Name *",
            placeholder = "Cardio Zone"
        )
        NbmsDropdown(
            label = "Icon",
            options = GymIcons.ALL,
            selected = draft.icon,
            onSelect = { onDraft(draft.copy(icon = it)) },
            optionLabel = { it }
        )
        GymImageField(providers, label = "equipment image", url = draft.image, onChange = { onDraft(draft.copy(image = it)) })
        NbmsTextField(
            value = draft.description,
            onValueChange = { onDraft(draft.copy(description = it)) },
            label = "Description *",
            placeholder = "Describe this for guests…",
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Save",
                onClick = onSave,
                loading = saving,
                enabled = GymContentRules.isValidEquipment(draft.name, draft.description) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Outline)
        }
    }
}
