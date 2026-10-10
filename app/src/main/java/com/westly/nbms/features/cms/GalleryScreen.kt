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

private val GALLERY_MAX_WIDTH = 768.dp

internal const val GALLERY_TITLE = "Gallery Management"
internal const val GALLERY_SUBTITLE =
    "Manage the photos shown on the public Gallery page — every change here goes live on the website immediately."
internal const val GALLERY_LOADING = "Loading gallery…"
internal const val GALLERY_LOAD_FAILED = "Gallery failed to load. Reload before adding or editing."
internal const val GALLERY_EMPTY = "No gallery images yet. Add your first one above."

/** What the open form holds. [id] is null for a new image. */
private data class GalleryDraft(
    val id: String? = null,
    val title: String = "",
    val imageUrl: String = "",
    val caption: String = ""
)

/** The Gallery page (`gallery`, Super Admin and Manager). */
@Composable
fun GalleryScreen(session: SessionState.SignedIn) {
    val vm: GalleryViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<GalleryDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<GalleryItem?>(null) }

    val items = state.images
    val canAdd = GalleryRules.canAdd(items.size)

    Column(
        modifier = Modifier.widthIn(max = GALLERY_MAX_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CmsPageHeader(icon = NbmsIcons.Images, title = GALLERY_TITLE, subtitle = GALLERY_SUBTITLE)

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Text(GALLERY_LOADING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (state.loadFailed) {
                Text(GALLERY_LOAD_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    GalleryRules.heading(items.size),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (draft == null) {
                    NbmsButton(
                        text = "Add Image",
                        onClick = { draft = GalleryDraft() },
                        size = ButtonSize.Sm,
                        enabled = canAdd && !state.saving,
                        leadingIcon = NbmsIcons.Plus
                    )
                }
            }
            if (!canAdd) {
                Text(MSG_GALLERY_LIMIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            draft?.takeIf { it.id == null }?.let { current ->
                GalleryForm(
                    title = "New Image",
                    saveText = "Save",
                    draft = current,
                    onDraft = { draft = it },
                    vm = vm,
                    saving = state.saving,
                    onSave = {
                        vm.saveImage(null, current.title, current.imageUrl, current.caption) { ok -> if (ok) draft = null }
                    },
                    onCancel = { draft = null }
                )
            }

            if (items.isEmpty()) {
                Text(
                    GALLERY_EMPTY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                )
            } else {
                items.forEach { item ->
                    val editing = draft?.takeIf { it.id == item.id }
                    if (editing != null) {
                        GalleryForm(
                            title = "Edit Image",
                            saveText = "Save Changes",
                            draft = editing,
                            onDraft = { draft = it },
                            vm = vm,
                            saving = state.saving,
                            onSave = {
                                vm.saveImage(item.id, editing.title, editing.imageUrl, editing.caption) { ok -> if (ok) draft = null }
                            },
                            onCancel = { draft = null }
                        )
                    } else {
                        GalleryCard(
                            item = item,
                            busy = state.saving,
                            onEdit = { draft = GalleryDraft(item.id, item.title, item.imageUrl, item.caption) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        CmsDeleteDialog(
            title = "Delete Image?",
            body = GalleryRules.deleteBody(item.title),
            onConfirm = {
                pendingDelete = null
                vm.deleteImage(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun GalleryCard(item: GalleryItem, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CmsThumbnail(item.imageUrl, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.caption.isNotBlank()) {
                    Text(
                        item.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column {
                CmsIconAction(NbmsIcons.Pencil, "Edit", !busy, onEdit)
                CmsIconAction(NbmsIcons.Trash, "Delete", !busy, onDelete)
            }
        }
    }
}

@Composable
private fun GalleryForm(
    title: String,
    saveText: String,
    draft: GalleryDraft,
    onDraft: (GalleryDraft) -> Unit,
    vm: GalleryViewModel,
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
            value = draft.title,
            onValueChange = { onDraft(draft.copy(title = it)) },
            label = "Title *",
            placeholder = "Poolside at sunset"
        )
        CmsImageField(
            providers = vm.imageProviders,
            label = "gallery image",
            folder = GALLERY_IMAGE_FOLDER,
            value = draft.imageUrl,
            onChange = { onDraft(draft.copy(imageUrl = it)) }
        )
        NbmsTextField(
            value = draft.caption,
            onValueChange = { onDraft(draft.copy(caption = it)) },
            label = "Description / Caption",
            placeholder = "Optional caption shown on the public gallery…",
            singleLine = false
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = saveText,
                onClick = onSave,
                loading = saving,
                enabled = GalleryRules.isValid(draft.title, draft.imageUrl) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = onCancel, variant = ButtonVariant.Outline)
        }
    }
}
