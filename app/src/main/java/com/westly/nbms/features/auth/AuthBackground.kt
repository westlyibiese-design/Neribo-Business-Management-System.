package com.westly.nbms.features.auth

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsBrandTitleStyle
import com.westly.nbms.core.design.nbmsShadow

/**
 * Shared full-screen layout for the auth screens: always-dark navy background,
 * content centred, scrolls when the keyboard is open, column at most 448dp wide.
 */
@Composable
fun AuthBackground(
    modifier: Modifier = Modifier,
    @DrawableRes backgroundRes: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.nbms.drawerBackground)
            .authPhoto(backgroundRes)
            .systemBarsPadding()
            .imePadding()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .heightIn(min = minHeight - 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier
                        .widthIn(max = 448.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Draws a full-screen photo behind a screen, with a dark navy layer on top (lighter at the top,
 * darker at the bottom) so the form and white text stay readable. Does nothing when [res] is null.
 * Put it before `systemBarsPadding()` so the photo also fills the status and navigation bars.
 */
@Composable
fun Modifier.authPhoto(@DrawableRes res: Int?): Modifier {
    if (res == null) return this
    val painter = painterResource(res)
    val base = MaterialTheme.nbms.drawerBackground
    return this
        .paint(painter, contentScale = ContentScale.Crop)
        .background(
            Brush.verticalGradient(
                listOf(base.copy(alpha = 0.40f), base.copy(alpha = 0.65f), base.copy(alpha = 0.85f))
            )
        )
}

/** Logo mark (64dp navy square with a gold "N"), the "NBMS" title and the "Management Portal" subtitle. */
@Composable
fun AuthBrandHeader(subtitle: String = "Management Portal") {
    val nbms = MaterialTheme.nbms
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .nbmsShadow(8.dp, RoundedCornerShape(16.dp))
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF203A6F))
                .border(1.dp, nbms.drawerBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "N",
                color = nbms.drawerPrimary,
                style = nbmsBrandTitleStyle()
            )
        }
        Spacer(Modifier.size(16.dp))
        Text("NBMS", style = nbmsBrandTitleStyle(), color = nbms.drawerForeground)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = nbms.drawerForeground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The translucent card used on the three auth screens (12dp radius, 2xl shadow). */
@Composable
fun AuthCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .nbmsShadow(16.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .border(1.dp, MaterialTheme.nbms.drawerBorder, shape),
        content = content
    )
}

/** Card title + description, 24dp padding. */
@Composable
fun AuthCardHeader(title: String, description: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A red banner shown at the top of a card for server errors. */
@Composable
fun AuthErrorBanner(message: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.errorContainer)
            .border(1.dp, scheme.error.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** Outline button that stays readable on the dark navy background in both themes. */
@Composable
fun AuthOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val nbms = MaterialTheme.nbms
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, nbms.drawerForeground.copy(alpha = 0.25f)), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = nbms.drawerForeground,
            textAlign = TextAlign.Center
        )
    }
}

/** Gold text link for use on the dark navy background. */
@Composable
fun AuthLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.nbms.drawerPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/** A thin "or" divider for the dark background. */
@Composable
fun AuthOrDivider() {
    val nbms = MaterialTheme.nbms
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = nbms.drawerBorder)
        Text("or", style = MaterialTheme.typography.bodySmall, color = nbms.drawerForeground.copy(alpha = 0.5f))
        HorizontalDivider(Modifier.weight(1f), color = nbms.drawerBorder)
    }
}

