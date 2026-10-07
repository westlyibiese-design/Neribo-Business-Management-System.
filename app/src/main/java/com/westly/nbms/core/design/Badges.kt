package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class BadgeTone { Default, Secondary, Outline, Success, Warning, Info, Destructive, Gold }

internal class ToneColors(val container: Color, val content: Color, val border: Color)

@Composable
internal fun BadgeTone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    return when (this) {
        BadgeTone.Default -> ToneColors(scheme.primary, scheme.onPrimary, Color.Transparent)
        BadgeTone.Secondary -> ToneColors(scheme.secondary, scheme.onSecondary, Color.Transparent)
        BadgeTone.Outline -> ToneColors(Color.Transparent, scheme.onBackground, nbms.badgeOutline)
        BadgeTone.Success -> ToneColors(nbms.successContainer, nbms.onSuccessContainer, Color.Transparent)
        BadgeTone.Warning -> ToneColors(nbms.warningContainer, nbms.onWarningContainer, Color.Transparent)
        BadgeTone.Info -> ToneColors(nbms.infoContainer, nbms.onInfoContainer, Color.Transparent)
        BadgeTone.Destructive -> ToneColors(nbms.destructive, nbms.onDestructive, Color.Transparent)
        BadgeTone.Gold -> ToneColors(nbms.gold, nbms.goldForeground, Color.Transparent)
    }
}

/** Badge: 6dp radius, 12sp semibold, 10dp x 2dp padding. */
@Composable
fun NbmsBadge(
    text: String,
    tone: BadgeTone = BadgeTone.Default,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val c = tone.colors()
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .clip(shape)
            .background(c.container)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = c.content, modifier = Modifier.size(12.dp))
        }
        Text(text, color = c.content, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/**
 * Fully rounded status / role pill (11sp medium). Pass colours from
 * `MaterialTheme.nbms.rolePill("manager")` or `MaterialTheme.nbms.statusPill("checked_in")`.
 */
@Composable
fun NbmsPill(
    text: String,
    colors: PillColors,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = colors.content,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
