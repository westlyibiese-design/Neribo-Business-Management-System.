package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.westly.nbms.core.util.Format

/** Page title (serif, 24sp) with an optional subtitle and action buttons on the right. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Card: 12dp radius, 1dp border, 4dp shadow. Tappable when [onClick] is given. */
@Composable
fun NbmsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .nbmsShadow(4.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.nbms.cardBorder, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                        .stateOverlay(source, shape)
                } else Modifier
            ),
        content = content
    )
}

/** A titled group of form fields. */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onBackground)
        content()
    }
}

/** KPI card: small uppercase label, big value, optional subtitle and a coloured icon tile. */
@Composable
fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    tone: BadgeTone = BadgeTone.Default,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val (tileColor, iconColor) = when (tone) {
        BadgeTone.Default, BadgeTone.Outline -> scheme.primary.copy(alpha = 0.10f) to scheme.primary
        BadgeTone.Secondary, BadgeTone.Gold -> nbms.gold.copy(alpha = 0.20f) to nbms.gold
        BadgeTone.Success -> nbms.successContainer to nbms.success
        BadgeTone.Warning -> nbms.warningContainer to nbms.onWarningContainer
        BadgeTone.Info -> nbms.infoContainer to nbms.info
        BadgeTone.Destructive -> nbms.destructiveContainer to nbms.destructive
    }
    NbmsCard(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.4.sp),
                    color = scheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(tileColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Round avatar. Shows the picture when [imageUrl] loads, otherwise the person's initials. */
@Composable
fun Avatar(
    name: String,
    imageUrl: String? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            Format.initials(name),
            color = scheme.onSurfaceVariant,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        }
    }
}

/** Thin vertical gap helper used between page sections. */
@Composable
fun SectionSpacer(height: Dp = 16.dp) {
    Spacer(Modifier.height(height))
}
