package com.westly.nbms.features.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsShadow

/**
 * The frame of the two dialogs of this phase: 80% black scrim, 16dp side gutters, up to 512dp wide, up to 85% of the
 * screen tall, a title with an optional icon and a close X. While [locked] (saving) the dialog cannot be dismissed
 * at all: back, a tap outside and the X do nothing.
 */
@Composable
internal fun ActionDialog(
    title: String,
    description: String?,
    locked: Boolean,
    onDismiss: () -> Unit,
    titleIcon: ImageVector? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = { if (!locked) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !locked,
            dismissOnClickOutside = !locked,
            usePlatformDefaultWidth = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 512.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .nbmsShadow(8.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.background)
                .border(1.dp, MaterialTheme.nbms.cardBorder, MaterialTheme.shapes.medium)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (titleIcon != null) {
                            Icon(titleIcon, contentDescription = null, tint = scheme.onBackground, modifier = Modifier.size(20.dp))
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
                    }
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                content()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
            }
            Icon(
                imageVector = NbmsIcons.Close,
                contentDescription = "Close",
                tint = scheme.onBackground,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(16.dp)
                    .alpha(if (locked) 0.3f else 0.7f)
                    .clickable(enabled = !locked, role = Role.Button, onClick = onDismiss)
            )
        }
    }
}

/** A label on the left and a value on the right (summary boxes). */
@Composable
internal fun SummaryRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        value()
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
}

/**
 * A button that, while [busy], shows a spinner and [busyText] and cannot be tapped; otherwise it is an outline or
 * primary button with an optional icon.
 */
@Composable
internal fun BusyButton(
    text: String,
    busyText: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    if (busy) {
        val scheme = MaterialTheme.colorScheme
        Row(
            modifier = modifier
                .alpha(0.5f)
                .heightIn(min = 36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(scheme.primary)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = scheme.onPrimary, strokeWidth = 2.dp)
            Text(busyText, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 1)
        }
    } else {
        NbmsButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            variant = ButtonVariant.Default,
            size = ButtonSize.Default,
            enabled = enabled,
            leadingIcon = icon
        )
    }
}

/** A fully rounded pill (11sp) with an optional small icon. */
@Composable
internal fun StatusPill(text: String, colors: PillColors, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        }
        Text(
            text,
            color = colors.content,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

internal val OrangeText = Color(0xFFEA580C)
