package com.westly.nbms.features.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PHOTOS = 12
private val FieldShape = RoundedCornerShape(8.dp)

private fun photos(count: Int): String = if (count == 1) "1 photo" else "$count photos"

/** A 1dp dashed rounded border drawn inside the box (no hover on phones, so it is always visible). */
private fun Modifier.dashedBorder(color: Color, corner: Dp = 8.dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val half = stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        )
    )
}

@Composable
private fun ImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ImageNotSupported,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .alpha(0.4f)
        )
    }
}

/** A picture cropped to fill its box; while loading, or when it cannot be loaded, a neutral placeholder is shown. */
@Composable
private fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { ImagePlaceholder() },
        error = { ImagePlaceholder() }
    )
}

/**
 * The real photo fields (Phase 32). Bound `@IntoSet` in [MediaModule], so every form that had an "Image URL"
 * fallback now shows these without any change to its own code.
 *
 * The fields never delete files from storage: an unsaved upload just stays there.
 */
@Singleton
class NbmsImageFieldProvider @Inject constructor(
    private val processor: ImageProcessor,
    private val api: MediaApi,
    private val toasts: ToastController
) : ImageFieldProvider {

    // Uploads must be able to finish even if the field leaves the screen, so they do not live in a composition scope.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Composable
    private fun rememberUploadState(folder: String): MediaUploadState<Uri> {
        val state = remember(folder) {
            MediaUploadState<Uri>(appScope) { uri -> api.upload(folder, processor.process(uri)) }
        }
        DisposableEffect(state) { onDispose { state.discard() } }
        return state
    }

    // ---------------------------------------------------------------------------------------------------------
    // One photo
    // ---------------------------------------------------------------------------------------------------------

    @Composable
    override fun SingleImageField(
        label: String,
        folder: String,
        url: String?,
        onChange: (String?) -> Unit,
        modifier: Modifier
    ) {
        val state = rememberUploadState(folder)
        val progress by state.progress.collectAsState()
        val uploading = progress.active
        val latestOnChange by rememberUpdatedState(onChange)
        val latestLabel by rememberUpdatedState(label)
        val scheme = MaterialTheme.colorScheme

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                state.start(
                    items = listOf(uri),
                    onEach = { newUrl -> latestOnChange(newUrl) },
                    onFinished = { summary ->
                        if (summary.failed > 0) {
                            toasts.show(
                                message = summary.firstError ?: MSG_MEDIA_UPLOAD_FAILED,
                                type = ToastType.Error,
                                title = "Upload failed"
                            )
                        } else {
                            toasts.show(
                                message = "$latestLabel updated successfully.",
                                type = ToastType.Success,
                                title = "Image Uploaded"
                            )
                        }
                    }
                )
            }
        }
        val pick = {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        if (url.isNullOrBlank()) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .clip(FieldShape)
                    .dashedBorder(scheme.outline)
                    .clickable(enabled = !uploading, role = Role.Button, onClickLabel = "Upload ${label.lowercase()}") { pick() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uploading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Uploading…", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.ImageNotSupported,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .alpha(0.4f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Upload ${label.lowercase()}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Outlined.FileUpload,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .border(1.dp, scheme.outline, FieldShape)
                    .padding(1.dp)
                    .clip(FieldShape)
            ) {
                RemoteImage(url = url, modifier = Modifier.fillMaxSize())
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NbmsButton(
                        text = "Replace",
                        onClick = { pick() },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Sm,
                        loading = uploading,
                        enabled = !uploading,
                        leadingIcon = Icons.Outlined.FileUpload
                    )
                    NbmsButton(
                        text = "Remove",
                        onClick = { latestOnChange(null) },
                        variant = ButtonVariant.Destructive,
                        size = ButtonSize.Sm,
                        enabled = !uploading,
                        leadingIcon = NbmsIcons.Close
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Several photos (the first one is the cover)
    // ---------------------------------------------------------------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun MultiImageField(
        label: String,
        folder: String,
        urls: List<String>,
        onChange: (List<String>) -> Unit,
        modifier: Modifier
    ) {
        val state = rememberUploadState(folder)
        val progress by state.progress.collectAsState()
        val uploading = progress.active
        val latestUrls by rememberUpdatedState(urls)
        val latestOnChange by rememberUpdatedState(onChange)
        val scheme = MaterialTheme.colorScheme
        val remaining = (MAX_PHOTOS - urls.size).coerceAtLeast(0)

        val startBatch: (List<Uri>) -> Unit = { picked ->
            val room = (MAX_PHOTOS - latestUrls.size).coerceAtLeast(0)
            val items = picked.take(room)
            if (items.isNotEmpty()) {
                // Each success is reported at once, so a half-finished batch is kept.
                val collected = latestUrls.toMutableList()
                state.start(
                    items = items,
                    onEach = { newUrl ->
                        collected.add(newUrl)
                        latestOnChange(collected.toList())
                    },
                    onFinished = { summary ->
                        if (summary.failed > 0) {
                            toasts.show(
                                message = summary.firstError ?: MSG_MEDIA_UPLOAD_FAILED,
                                type = ToastType.Error,
                                title = "${photos(summary.failed)} couldn't be uploaded."
                            )
                        } else if (summary.succeeded > 0) {
                            toasts.show(
                                message = "${photos(summary.succeeded)} added.",
                                type = ToastType.Success,
                                title = "Photos Uploaded"
                            )
                        }
                    }
                )
            }
        }

        // The multi-picker needs at least 2 slots; with exactly 1 slot left the single picker is used.
        val multiContract = remember(remaining) {
            ActivityResultContracts.PickMultipleVisualMedia(remaining.coerceIn(2, MAX_PHOTOS))
        }
        val multiLauncher = rememberLauncherForActivityResult(multiContract) { uris -> startBatch(uris) }
        val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) startBatch(listOf(uri))
        }
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        val openPicker = {
            if (remaining >= 2) multiLauncher.launch(request) else singleLauncher.launch(request)
        }

        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(" (${urls.size}/$MAX_PHOTOS)", fontSize = 14.sp, color = scheme.onSurfaceVariant)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                urls.forEachIndexed { index, photoUrl ->
                    Thumbnail(
                        url = photoUrl,
                        isCover = index == 0,
                        canMoveEarlier = index > 0,
                        enabled = !uploading,
                        onRemove = { latestOnChange(latestUrls.filterIndexed { i, _ -> i != index }) },
                        onMoveEarlier = {
                            val moved = latestUrls.toMutableList()
                            if (index in 1 until moved.size) {
                                val item = moved.removeAt(index)
                                moved.add(index - 1, item)
                                latestOnChange(moved.toList())
                            }
                        }
                    )
                }
                if (urls.size < MAX_PHOTOS) {
                    AddTile(
                        uploading = uploading,
                        index = progress.index,
                        total = progress.total,
                        onClick = { openPicker() }
                    )
                }
            }
            if (urls.size >= MAX_PHOTOS) {
                Text(
                    "You've reached the maximum of $MAX_PHOTOS photos.",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun Thumbnail(
        url: String,
        isCover: Boolean,
        canMoveEarlier: Boolean,
        enabled: Boolean,
        onRemove: () -> Unit,
        onMoveEarlier: () -> Unit
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(modifier = Modifier.size(96.dp)) {
            RemoteImage(
                url = url,
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, scheme.outline, FieldShape)
                    .padding(1.dp)
                    .clip(FieldShape)
            )
            if (isCover) {
                Text(
                    text = "Cover",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // Remove: 44dp touch target, small round button drawn in its top-right corner.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Remove photo") { onRemove() },
                contentAlignment = Alignment.TopEnd
            ) {
                RoundIconButtonFace(icon = NbmsIcons.Close, modifier = Modifier.padding(top = 4.dp, end = 4.dp), enabled = enabled)
            }
            if (canMoveEarlier) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(44.dp)
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Move photo earlier") { onMoveEarlier() },
                    contentAlignment = Alignment.BottomStart
                ) {
                    RoundIconButtonFace(icon = NbmsIcons.ArrowLeft, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp), enabled = enabled)
                }
            }
        }
    }

    @Composable
    private fun RoundIconButtonFace(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        modifier: Modifier = Modifier,
        enabled: Boolean = true
    ) {
        Box(
            modifier = modifier
                .size(24.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }

    @Composable
    private fun AddTile(uploading: Boolean, index: Int, total: Int, onClick: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(FieldShape)
                .dashedBorder(scheme.outline)
                .clickable(enabled = !uploading, role = Role.Button, onClickLabel = "Add photos") { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(4.dp)
            ) {
                if (uploading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Uploading $index of $total…",
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                } else {
                    Icon(NbmsIcons.Plus, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text("Add photos", fontSize = 11.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
