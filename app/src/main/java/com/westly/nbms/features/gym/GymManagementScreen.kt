package com.westly.nbms.features.gym

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ImageFieldProvider
import com.westly.nbms.core.session.SessionState

private val MANAGEMENT_MAX_WIDTH = 896.dp

internal const val MANAGEMENT_TITLE = "Gym Management"
internal const val MANAGEMENT_SUBTITLE =
    "Manage everything shown on the business's gym page — every change here goes live immediately. " +
        "The hero banner is edited from Website CMS → Page Banners → Gym Page Hero Banner."
internal const val MANAGEMENT_LOADING = "Loading gym content…"
internal const val MANAGEMENT_LOAD_FAILED = "Gym content failed to load. Reload before adding or editing."
internal const val DELETE_MESSAGE_DEFAULT = "This removes it from the gym page immediately."

/** The Gym Management page (`gym-cms`): header, load / error states, the tab row and the six tabs. */
@Composable
fun GymManagementScreen(session: SessionState.SignedIn) {
    val vm: GymContentViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableStateOf(GymFormRules.DEFAULT_TAB.ordinal) }
    val tab = GymFormRules.TABS.getOrElse(tabIndex) { GymFormRules.DEFAULT_TAB }

    Column(
        modifier = Modifier.widthIn(max = MANAGEMENT_MAX_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        GymPageHeader(icon = NbmsIcons.Dumbbell, title = MANAGEMENT_TITLE, subtitle = MANAGEMENT_SUBTITLE)

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                Text(MANAGEMENT_LOADING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (state.loadFailed) {
                Text(MANAGEMENT_LOAD_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            GymTabRow(selected = tab, onSelect = { tabIndex = it.ordinal })
            when (tab) {
                GymTab.ABOUT -> AboutTab(vm, state)
                GymTab.EQUIPMENT -> EquipmentTab(vm, state)
                GymTab.HOURS -> HoursTab(vm, state)
                GymTab.PACKAGES -> PackagesTab(vm, state, session.business.currencySymbol)
                GymTab.PROGRAMS -> ProgramsTab(vm, state)
                GymTab.GALLERY -> GalleryTab(vm, state)
            }
        }
    }
}

private fun GymTab.icon(): ImageVector = when (this) {
    GymTab.ABOUT -> Icons.Outlined.Info
    GymTab.EQUIPMENT -> NbmsIcons.ClipboardCheck
    GymTab.HOURS -> NbmsIcons.Clock
    GymTab.PACKAGES -> NbmsIcons.Tag
    GymTab.PROGRAMS -> NbmsIcons.Users
    GymTab.GALLERY -> NbmsIcons.Images
}

/** Pill row that wraps onto more lines on a narrow screen; the selected pill is filled with the primary colour. */
@Composable
private fun GymTabRow(selected: GymTab, onSelect: (GymTab) -> Unit) {
    FlowChips(horizontalSpacing = 8.dp, verticalSpacing = 8.dp, modifier = Modifier.fillMaxWidth()) {
        GymFormRules.TABS.forEach { tab ->
            val active = tab == selected
            val scheme = MaterialTheme.colorScheme
            val contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(if (active) scheme.primary else scheme.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(tab.icon(), contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                Text(tab.label, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
            }
        }
    }
}

// ───────────────────────── shared pieces used by the tabs ─────────────────────────

/** A list heading on the left and (optionally) its Add button on the right. */
@Composable
internal fun TabHeading(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (action != null) action()
    }
}

/** The tinted card an add / edit form sits in. */
@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) {
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

/** A short muted line shown when a list is empty. */
@Composable
internal fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
    )
}

/** Small icon-only button (36dp). [label] is the accessibility text. */
@Composable
internal fun IconAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    NbmsButton(text = label, onClick = onClick, variant = ButtonVariant.Ghost, size = ButtonSize.Icon, enabled = enabled, leadingIcon = icon)
}

/** Up and down arrows that move an item one place; each is disabled at its end of the list. */
@Composable
internal fun ReorderButtons(canMoveUp: Boolean, canMoveDown: Boolean, enabled: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Column {
        IconAction(Icons.Outlined.ArrowUpward, "Move up", enabled && canMoveUp, onUp)
        IconAction(Icons.Outlined.ArrowDownward, "Move down", enabled && canMoveDown, onDown)
    }
}

/** A square picture (or an image-off icon when there is none or it cannot load). */
@Composable
internal fun Thumbnail(url: String, size: Dp, modifier: Modifier = Modifier) {
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

/** The Image field of a form: the upload field when one is installed, otherwise a plain "Image URL (optional)" text field. */
@Composable
internal fun GymImageField(providers: Set<ImageFieldProvider>, label: String, url: String, onChange: (String) -> Unit) {
    val provider = providers.firstOrNull()
    if (provider != null) {
        provider.SingleImageField(
            label = label,
            folder = "gym",
            url = url.ifBlank { null },
            onChange = { onChange(it.orEmpty()) }
        )
    } else {
        NbmsTextField(value = url, onValueChange = onChange, label = "Image URL (optional)", placeholder = "https://…")
    }
}

/** "Delete "{name}"?" confirmation; [message] is the line under the title. */
@Composable
internal fun DeleteConfirm(name: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = GymFormRules.deleteTitle(name),
        message = message,
        confirmText = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** Dashed rounded outline, used by the Gallery "add" tile. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp = 12.dp, strokeWidth: Dp = 1.5.dp): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = strokeWidth.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f))
    )
}

/** Name line (semibold, one line) over a description (muted, two lines) — the text of an equipment or program card. */
@Composable
internal fun NameAndDescription(name: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Reads the accent colour used for the "Most Popular" star. */
@Composable
internal fun popularStarColor(): Color = MaterialTheme.nbms.warning
