package com.westly.nbms.features.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.dashboard.data.MSG_DASHBOARD_LOAD_FAILED
import com.westly.nbms.features.dashboard.data.dashZoneOf
import java.time.ZoneId

// Shared look of the management dashboards (Phase 33A1). Reused by 33A2, 33B and Phase 34.
// Money is never formatted here: callers pass text made with Format.currency and the business currency symbol.

enum class AlertTone { BLUE, RED, ORANGE }

/** The business time zone (never UTC); Africa/Lagos when the stored name is missing or unknown. */
@Composable
fun rememberBusinessZone(session: SessionState.SignedIn): ZoneId {
    val timezone = session.business.timezone
    return remember(timezone) { dashZoneOf(timezone) }
}

/** Serif 24sp bold title, 14sp muted subtitle and an optional row of actions on the right. */
@Composable
fun DashGreeting(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
        }
    }
}

/**
 * Label (12sp, upper-case, muted), value (22sp bold, wraps onto a second line instead of overflowing), optional sub line
 * and a 36dp rounded icon tile on the right. The grid that decides one or two columns (< 360dp = one) belongs to the dashboard.
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    modifier: Modifier = Modifier,
    sub: String? = null
) {
    val scheme = MaterialTheme.colorScheme
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
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Compact centred card: icon 16dp, value 20sp bold, label 10sp muted. */
@Composable
fun MiniStat(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    NbmsCard(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Card with a 14sp semibold heading row and, when [linkText] is given, a small primary-coloured link with an arrow on the right. */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    linkText: String? = null,
    onLink: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (linkText != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .then(if (onLink != null) Modifier.clickable(onClick = onLink) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(linkText, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(12.dp))
                    }
                }
            }
            content()
        }
    }
}

/** Tall rounded tile (at least 96dp high) with an icon and a label; [primary] fills it with the primary colour. */
@Composable
fun QuickActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (primary) scheme.primary else scheme.surface
    val content = if (primary) scheme.onPrimary else scheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.nbms.cardBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (primary) scheme.onPrimary else scheme.primary, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Outlined pill: blue (info), red (destructive) or orange (attention). */
@Composable
fun AlertPill(
    text: String,
    icon: ImageVector,
    tone: AlertTone,
    modifier: Modifier = Modifier
) {
    val nbms = MaterialTheme.nbms
    val colour = when (tone) {
        AlertTone.BLUE -> nbms.info
        AlertTone.RED -> nbms.destructive
        AlertTone.ORANGE -> if (nbms.isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, colour.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/** A list row (primary 14sp, secondary 12sp muted, optional trailing content) with a hairline divider underneath. */
@Composable
fun RowItem(
    primary: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    primary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (secondary != null) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) trailing()
        }
        HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
    }
}

/**
 * The loading / error wrapper of every dashboard: a centred 160dp spinner while loading, the standard error block with
 * Retry on failure, otherwise [content]. A failure wins over loading (one list failing must not hide behind another's spinner).
 */
@Composable
fun DashStateHost(
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    content: @Composable () -> Unit
) {
    when {
        failed -> ErrorState(message = MSG_DASHBOARD_LOAD_FAILED, onRetry = onRetry)
        loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
        else -> content()
    }
}
