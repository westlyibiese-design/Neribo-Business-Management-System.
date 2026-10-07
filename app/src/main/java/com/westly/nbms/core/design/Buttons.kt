package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonVariant { Default, Gold, Outline, Secondary, Ghost, Destructive, Link }

enum class ButtonSize { Default, Sm, Lg, Icon }

/**
 * Draws the Westly "hover" and "pressed" overlays (elevate-1 / elevate-2) on top of a component.
 * Shared by buttons, cards, tabs and other tappable surfaces.
 */
@Composable
internal fun Modifier.stateOverlay(source: InteractionSource, shape: Shape): Modifier {
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val nbms = MaterialTheme.nbms
    val overlay = when {
        pressed -> nbms.elevate2
        hovered -> nbms.elevate1
        else -> Color.Transparent
    }
    return this.drawWithContent {
        drawContent()
        if (overlay != Color.Transparent) {
            drawOutline(shape.createOutline(size, layoutDirection, this), overlay)
        }
    }
}

private class ButtonLook(
    val container: Color,
    val content: Color,
    val border: Color,
    val elevation: Dp
)

/**
 * NBMS button. Heights: Default 36dp, Sm 32dp, Lg 40dp, Icon 36x36dp.
 * With [loading] = true the label is replaced by a 16dp spinner, the width stays the same and taps are ignored.
 * For [ButtonSize.Icon] only [leadingIcon] is drawn; [text] is used as the accessibility label.
 */
@Composable
fun NbmsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    loading: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.small
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()

    val look = when (variant) {
        ButtonVariant.Default -> ButtonLook(scheme.primary, scheme.onPrimary, nbms.primaryBorder, 0.dp)
        ButtonVariant.Gold -> ButtonLook(nbms.gold, nbms.goldForeground, nbms.gold, 0.dp)
        ButtonVariant.Outline -> ButtonLook(Color.Transparent, scheme.onBackground, nbms.buttonOutline, 0.dp)
        ButtonVariant.Secondary -> ButtonLook(scheme.secondary, scheme.onSecondary, nbms.secondaryBorder, 0.dp)
        ButtonVariant.Ghost -> ButtonLook(Color.Transparent, scheme.onBackground, Color.Transparent, 0.dp)
        ButtonVariant.Destructive -> ButtonLook(nbms.destructive, nbms.onDestructive, nbms.destructiveBorder, 2.dp)
        ButtonVariant.Link -> ButtonLook(Color.Transparent, scheme.primary, Color.Transparent, 0.dp)
    }

    val isIcon = size == ButtonSize.Icon
    val minHeight = when (size) {
        ButtonSize.Default -> 36.dp
        ButtonSize.Sm -> 32.dp
        ButtonSize.Lg -> 40.dp
        ButtonSize.Icon -> 36.dp
    }
    val horizontalPadding = when (size) {
        ButtonSize.Default -> 16.dp
        ButtonSize.Sm -> 12.dp
        ButtonSize.Lg -> 32.dp
        ButtonSize.Icon -> 0.dp
    }
    val verticalPadding = if (isIcon) 0.dp else if (size == ButtonSize.Sm) 4.dp else 8.dp
    val textStyle = if (size == ButtonSize.Sm) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    val sizeModifier = if (isIcon) Modifier.size(36.dp) else Modifier.heightIn(min = minHeight)

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .then(sizeModifier)
            .then(if (look.elevation > 0.dp && !pressed) Modifier.nbmsShadow(look.elevation, shape) else Modifier)
            .clip(shape)
            .background(look.container)
            .border(1.dp, if (focused) nbms.ring else look.border, shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick
            )
            .stateOverlay(source, shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.alpha(if (loading) 0f else 1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = if (isIcon) text else null,
                    tint = look.content,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (!isIcon) {
                Text(
                    text = text,
                    color = look.content,
                    style = textStyle,
                    maxLines = 1,
                    textDecoration = if (variant == ButtonVariant.Link && pressed) TextDecoration.Underline else null
                )
            }
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = look.content,
                strokeWidth = 2.dp
            )
        }
    }
}
