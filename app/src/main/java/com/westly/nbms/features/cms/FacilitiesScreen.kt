package com.westly.nbms.features.cms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.session.SessionState

private val FACILITIES_MAX_WIDTH = 768.dp

internal const val FACILITIES_TITLE = "Facilities Management"
internal const val FACILITIES_SUBTITLE =
    "Manage the amenities shown on the public Facilities page — every change here goes live on the website immediately."
internal const val FACILITIES_LOADING = "Loading facilities…"
internal const val FACILITIES_LOAD_FAILED = "Facilities failed to load. Reload before adding or editing."
internal const val FACILITIES_EMPTY = "No facilities yet. Add your first one above."

/** What the open form holds. [id] is null for a new facility. */
private data class FacilityDraft(
    val id: String? = null,
    val name: String = "",
    val image: String = "",
    val description: String = ""
)

/** The Facilities page (`facilities`, Super Admin and Manager). */
@Composable
fun FacilitiesScreen(session: SessionState.SignedIn) {
    val vm: FacilitiesViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<FacilityDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<FacilityItem?>(null) }

    val items = state.facilities
    val canAdd = FacilitiesRules.canAdd(items.size)

    Column(
        modifier = Modifier.widthIn(max = FACILITIES_MAX_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CmsPageHeader(icon = NbmsIcons.Building, title = FACILITIES_TITLE, subtitle = FACILITIES_SUBTITLE)

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Text(FACILITIES_LOADING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (state.loadFailed) {
                Text(FACILITIES_LOAD_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    FacilitiesRules.heading(items.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (draft == null) {
                    NbmsButton(
                        text = "Add Facility",
                        onClick = { draft = FacilityDraft() },
                        size = ButtonSize.Sm,
                        enabled = canAdd && !state.saving,
                        leadingIcon = NbmsIcons.Plus
                    )
                }
            }
            if (!canAdd) {
                Text(MSG_FACILITIES_LIMIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            draft?.takeIf { it.id == null }?.let { current ->
                FacilityForm(
                    title = "New Facility",
                    saveText = "Save",
                    draft = current,
                    onDraft = { draft = it },
                    vm = vm,
                    saving = state.saving,
                    onSave = {
                        vm.saveFacility(null, current.name, current.image, current.description) { ok -> if (ok) draft = null }
                    },
                    onCancel = { draft = null }
                )
            }

            if (items.isEmpty()) {
                Text(
                    FACILITIES_EMPTY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                )
            } else {
                items.forEachIndexed { index, item ->
                    val editing = draft?.takeIf { it.id == item.id }
                    if (editing != null) {
                        FacilityForm(
                            title = "Edit Facility",
                            saveText = "Save Changes",
                            draft = editing,
                            onDraft = { draft = it },
                            vm = vm,
                            saving = state.saving,
                            onSave = {
                                vm.saveFacility(item.id, editing.name, editing.image, editing.description) { ok -> if (ok) draft = null }
                            },
                            onCancel = { draft = null }
                        )
                    } else {
                        FacilityCard(
                            item = item,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            busy = state.saving,
                            onUp = { vm.moveFacility(item.id, -1) },
                            onDown = { vm.moveFacility(item.id, 1) },
                            onEdit = { draft = FacilityDraft(item.id, item.name, item.image, item.description) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        CmsDeleteDialog(
            title = "Delete Facility?",
            body = FacilitiesRules.deleteBody(item.name),
            onConfirm = {
                pendingDelete = null
                vm.deleteFacility(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun FacilityCard(
    item: FacilityItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CmsReorderButtons(canMoveUp = canMoveUp, canMoveDown = canMoveDown, enabled = !busy, onUp = onUp, onDown = onDown)
            CmsThumbnail(item.image, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column {
                CmsIconAction(NbmsIcons.Pencil, "Edit", !busy, onEdit)
                CmsIconAction(NbmsIcons.Trash, "Delete", !busy, onDelete)
            }
        }
    }
}

@Composable
private fun FacilityForm(
    title: String,
    saveText: String,
    draft: FacilityDraft,
    onDraft: (FacilityDraft) -> Unit,
    vm: FacilitiesViewModel,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    CmsFormCard {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        NbmsTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            label = "Name *",
            placeholder = "Infinity Pool"
        )
        CmsImageField(
            providers = vm.imageProviders,
            label = "facility image",
            folder = FACILITIES_IMAGE_FOLDER,
            value = draft.image,
            onChange = { onDraft(draft.copy(image = it)) }
        )
        NbmsTextField(
            value = draft.description,
            onValueChange = { onDraft(draft.copy(description = it)) },
            label = "Description *",
            placeholder = "Describe this facility for guests…",
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = saveText,
                onClick = onSave,
                loading = saving,
                enabled = FacilitiesRules.isValid(draft.name, draft.description) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Outline)
        }
    }
}
