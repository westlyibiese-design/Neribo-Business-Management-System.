package com.westly.nbms.features.cms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.feature.ImageFieldProvider
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.atomic.AtomicBoolean

/** Rules shared by the CMS list pages. */
object CmsListRules {
    /** FAQ `order` is always position + 1 (1, 2, 3 …), whatever it was before. */
    fun renumberFaqs(items: List<FaqItem>): List<FaqItem> = items.mapIndexed { index, item -> item.copy(order = index + 1) }
}

/** A synchronous in-flight guard: the first [tryStart] wins, every other call is refused until [finish]. A double tap saves once. */
class CmsSaveGuard {
    private val busy = AtomicBoolean(false)

    fun tryStart(): Boolean = busy.compareAndSet(false, true)

    fun finish() {
        busy.set(false)
    }
}

/**
 * A picture field. When an upload provider is installed it is used (the upload phase binds one); until then this is a plain
 * "Image URL" text field with a preview underneath, which is hidden when the field is empty or the picture cannot be loaded.
 */
@Composable
fun CmsImageField(
    providers: Set<ImageFieldProvider>,
    label: String,
    folder: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    previewHeight: Dp = 120.dp
) {
    val provider = providers.firstOrNull()
    if (provider != null) {
        provider.SingleImageField(
            label = label,
            folder = folder,
            url = value.ifBlank { null },
            onChange = { onChange(it ?: "") },
            modifier = modifier
        )
    } else {
        UrlImageField(label = label, value = value, onChange = onChange, modifier = modifier, previewHeight = previewHeight)
    }
}

/** The fallback: an "Image URL" text field with a preview that is hidden when empty or when the picture fails to load. */
@Composable
private fun UrlImageField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, previewHeight: Dp) {
    var failedUrl by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NbmsTextField(value = value, onValueChange = onChange, label = "Image URL", placeholder = "https://…")
        val url = value.trim()
        if (url.isNotEmpty() && failedUrl != url) {
            AsyncImage(
                model = url,
                contentDescription = "Preview of $label",
                contentScale = ContentScale.Crop,
                onError = { failedUrl = url },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

/**
 * Five stars filled up to [rating]. Read-only it shows nothing at all when [rating] is null or 0; with [onSelect] it always
 * shows the five stars and each one is tappable (the stars are then the rating input).
 */
@Composable
fun CmsStars(rating: Int?, modifier: Modifier = Modifier, starSize: Dp = 14.dp, onSelect: ((Int) -> Unit)? = null) {
    val filled = (rating ?: 0).coerceIn(0, 5)
    if (filled == 0 && onSelect == null) return
    val onColor = MaterialTheme.nbms.warning
    val offColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            val on = star <= filled
            val starModifier = if (onSelect != null) {
                Modifier.size(starSize).clickable(role = Role.Button) { onSelect(star) }
            } else {
                Modifier.size(starSize)
            }
            Icon(
                imageVector = if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (onSelect != null) "Rate $star out of 5" else null,
                tint = if (on) onColor else offColor,
                modifier = starModifier
            )
        }
    }
}

/** Title, body, a red "Delete" and "Cancel". */
@Composable
fun CmsDeleteDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = title,
        message = body,
        confirmText = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

// ───────────────────────── small pieces used by the CMS pages ─────────────────────────

/** A page title row with a tinted icon tile on the left. */
@Composable
internal fun CmsPageHeader(icon: ImageVector, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The tinted card an add / edit form sits in. */
@Composable
internal fun CmsFormCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

/** Small icon-only button (36dp). [label] is the accessibility text. */
@Composable
internal fun CmsIconAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    NbmsButton(text = label, onClick = onClick, variant = ButtonVariant.Ghost, size = ButtonSize.Icon, enabled = enabled, leadingIcon = icon)
}

/** Up and down arrows that move an item one place; each is disabled at its end of the list and while [enabled] is false. */
@Composable
internal fun CmsReorderButtons(canMoveUp: Boolean, canMoveDown: Boolean, enabled: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Column {
        CmsIconAction(Icons.Outlined.ArrowUpward, "Move up", enabled && canMoveUp, onUp)
        CmsIconAction(Icons.Outlined.ArrowDownward, "Move down", enabled && canMoveDown, onDown)
    }
}

/** A square picture, or an image-off icon when there is none or it cannot load. */
@Composable
internal fun CmsThumbnail(url: String, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.ImageNotSupported,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}
