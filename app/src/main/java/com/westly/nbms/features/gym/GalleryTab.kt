package com.westly.nbms.features.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

private val GALLERY_WIDE_WIDTH = 600.dp

internal const val GALLERY_TITLE = "Gym Gallery"
internal const val GALLERY_ADD_PROMPT = "Paste an image URL"

private sealed interface GalleryCell {
    data class Photo(val index: Int, val url: String) : GalleryCell
    data object Add : GalleryCell
}

/** Gallery tab: a grid of square photos (2 columns on a phone, 4 on a tablet) with × to remove and a dashed tile to add. */
@Composable
internal fun GalleryTab(vm: GymContentViewModel, state: GymContentUiState) {
    val photos = state.content.gallery
    val canAdd = GymContentRules.canAddGalleryImage(photos.size)
    var urlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabHeading(GymFormRules.countHeading(GALLERY_TITLE, photos.size, GymContentLimits.GALLERY))

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= GALLERY_WIDE_WIDTH) 4 else 2
            val cells: List<GalleryCell> =
                photos.mapIndexed { i, url -> GalleryCell.Photo(i, url) } + if (canAdd) listOf(GalleryCell.Add) else emptyList()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cells.chunked(columns).forEach { rowCells ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowCells.forEach { cell ->
                            val cellModifier = Modifier.weight(1f).aspectRatio(1f)
                            when (cell) {
                                is GalleryCell.Photo -> PhotoTile(
                                    url = cell.url,
                                    number = cell.index + 1,
                                    removeEnabled = !state.saving,
                                    onRemove = { vm.removeGalleryImage(cell.index) },
                                    modifier = cellModifier
                                )
                                GalleryCell.Add -> AddTile(
                                    vm = vm,
                                    saving = state.saving,
                                    onPasteUrl = { urlDialog = true },
                                    modifier = cellModifier
                                )
                            }
                        }
                        repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (urlDialog) {
        NbmsDialog(
            title = "Add Gallery Image",
            onDismiss = { urlDialog = false },
            confirmText = "Add",
            loading = state.saving,
            onConfirm = {
                val clean = urlText.trim()
                if (clean.isNotEmpty()) {
                    vm.addGalleryImage(clean) { ok ->
                        if (ok) {
                            urlDialog = false
                            urlText = ""
                        }
                    }
                }
            }
        ) {
            NbmsTextField(value = urlText, onValueChange = { urlText = it }, label = "Image URL", placeholder = "https://…")
        }
    }
}

@Composable
private fun PhotoTile(url: String, number: Int, removeEnabled: Boolean, onRemove: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = url,
            contentDescription = "Gallery photo $number",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(enabled = removeEnabled, role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                NbmsIcons.Close,
                contentDescription = "Remove photo $number",
                tint = Color.White.copy(alpha = if (removeEnabled) 1f else 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** The dashed "add" tile: the upload field when one is installed, otherwise a prompt to paste an image URL. */
@Composable
private fun AddTile(vm: GymContentViewModel, saving: Boolean, onPasteUrl: () -> Unit, modifier: Modifier) {
    val provider = vm.imageProviders.firstOrNull()
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.large).dashedBorder(outline),
        contentAlignment = Alignment.Center
    ) {
        if (provider != null) {
            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                provider.SingleImageField(
                    label = "gallery image",
                    folder = "gym",
                    url = null,
                    onChange = { picked -> if (!picked.isNullOrBlank()) vm.addGalleryImage(picked) }
                )
            }
            if (saving) {
                // A photo picked while a save is running would be dropped, so the tile ignores taps until the save ends.
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = !saving, role = Role.Button, onClick = onPasteUrl)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            ) {
                Icon(NbmsIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Text(
                    GALLERY_ADD_PROMPT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
