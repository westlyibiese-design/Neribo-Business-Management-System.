package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.feature.ImageFieldProvider

internal const val PROGRAMS_TITLE = "Personal Training & Programs"
internal const val PROGRAMS_HELPER =
    "Leave this list empty if you don't offer personal training or fitness programs — the section is hidden on the public page."
internal const val PROGRAMS_EMPTY = "No programs listed yet."

private data class ProgramDraft(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val description: String = ""
)

/** Programs tab: personal training and fitness programs; every change saves at once. */
@Composable
internal fun ProgramsTab(vm: GymContentViewModel, state: GymContentUiState) {
    val items = state.content.programs
    var draft by remember { mutableStateOf<ProgramDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<ProgramItem?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabHeading(GymFormRules.countHeading(PROGRAMS_TITLE, items.size, GymContentLimits.PROGRAMS)) {
            if (draft == null) {
                NbmsButton(
                    text = "Add Program",
                    onClick = { draft = ProgramDraft() },
                    size = ButtonSize.Sm,
                    enabled = GymContentRules.canAddProgram(items.size) && !state.saving,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }
        Text(PROGRAMS_HELPER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        draft?.let { current ->
            ProgramForm(
                draft = current,
                onDraft = { draft = it },
                providers = vm.imageProviders,
                saving = state.saving,
                onSave = {
                    vm.saveProgram(
                        ProgramItem(id = current.id, name = current.name, description = current.description, image = current.image)
                    ) { ok -> if (ok) draft = null }
                },
                onCancel = { draft = null }
            )
        }

        if (items.isEmpty()) {
            EmptyNote(PROGRAMS_EMPTY)
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
                            onUp = { vm.moveProgram(item.id, -1) },
                            onDown = { vm.moveProgram(item.id, 1) }
                        )
                        Thumbnail(item.image, 64.dp)
                        NameAndDescription(item.name, item.description, Modifier.weight(1f))
                        Column {
                            IconAction(NbmsIcons.Pencil, "Edit ${item.name}", !state.saving) {
                                draft = ProgramDraft(item.id, item.name, item.image, item.description)
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
                vm.deleteProgram(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ProgramForm(
    draft: ProgramDraft,
    onDraft: (ProgramDraft) -> Unit,
    providers: Set<ImageFieldProvider>,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    FormCard {
        NbmsTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            label = "Name *",
            placeholder = "1-on-1 Personal Training"
        )
        GymImageField(providers, label = "program image", url = draft.image, onChange = { onDraft(draft.copy(image = it)) })
        NbmsTextField(
            value = draft.description,
            onValueChange = { onDraft(draft.copy(description = it)) },
            label = "Description *",
            placeholder = "Describe this program…",
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = "Save",
                onClick = onSave,
                loading = saving,
                enabled = GymContentRules.isValidProgram(draft.name, draft.description) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Outline)
        }
    }
}
