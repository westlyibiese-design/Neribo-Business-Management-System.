package com.westly.nbms.features.device

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsBrandSmallStyle
import com.westly.nbms.core.design.nbmsBrandTitleStyle

/** Full-screen "Welcome back" lock. It sits on top of everything and swallows every touch. */
@Composable
fun DeviceLockScreen(
    userName: String,
    modifier: Modifier = Modifier,
    vm: DeviceLockViewModel = hiltViewModel()
) {
    val nbms = MaterialTheme.nbms
    val state by vm.state.collectAsState()
    val disabled = state.verifying || state.pausedByServer

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(nbms.drawerBackground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .systemBarsPadding()
    ) {
        val compact = maxHeight < 760.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .widthIn(max = 384.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)
            ) {
                if (!compact) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF203A6F))
                            .border(1.dp, nbms.drawerBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("N", style = nbmsBrandSmallStyle().copy(fontSize = 18.sp), color = nbms.drawerPrimary)
                    }
                }
                Box(
                    Modifier
                        .size(if (compact) 48.dp else 64.dp)
                        .clip(CircleShape)
                        .background(nbms.drawerPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        NbmsIcons.Lock,
                        contentDescription = null,
                        tint = nbms.drawerPrimaryForeground,
                        modifier = Modifier.size(if (compact) 22.dp else 28.dp)
                    )
                }
                Text(
                    "Welcome back",
                    style = nbmsBrandTitleStyle().copy(fontSize = if (compact) 24.sp else 30.sp),
                    color = nbms.drawerAccentForeground,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = buildAnnotatedString {
                        append("Enter your device PIN to continue, ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = nbms.drawerAccentForeground)) {
                            append(userName)
                        }
                        append(".")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = nbms.drawerForeground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                LockDots(filled = state.pad.pin.length, shakeCount = state.shakeCount)

                Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.verifying -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = nbms.drawerForeground.copy(alpha = 0.7f)
                            )
                            Text(
                                "Verifying…",
                                style = MaterialTheme.typography.bodySmall,
                                color = nbms.drawerForeground.copy(alpha = 0.7f)
                            )
                        }
                        state.error != null -> Text(
                            state.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = nbms.destructive,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                LockKeypad(
                    compact = compact,
                    enabled = !disabled,
                    canSubmit = state.pad.canSubmit,
                    onDigit = vm::onDigit,
                    onDelete = vm::onDelete,
                    onSubmit = vm::submit
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        NbmsIcons.ShieldCheck,
                        contentDescription = null,
                        tint = nbms.drawerForeground.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Only this phone can unlock with this PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = nbms.drawerForeground.copy(alpha = 0.4f)
                    )
                }

                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = vm::logOut)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        NbmsIcons.LogOut,
                        contentDescription = null,
                        tint = nbms.drawerForeground.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Log out",
                        style = MaterialTheme.typography.bodyMedium,
                        color = nbms.drawerForeground.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LockDots(filled: Int, shakeCount: Int) {
    val nbms = MaterialTheme.nbms
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeCount) {
        if (shakeCount > 0) {
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 400
                    (-10f) at 50
                    10f at 100
                    (-8f) at 150
                    8f at 200
                    (-4f) at 250
                    4f at 300
                    0f at 400
                }
            )
        }
    }
    // Six dots to start with; more appear as digits go past six, up to ten.
    val count = maxOf(PIN_MIN_LENGTH, filled).coerceAtMost(PIN_MAX_LENGTH)
    Row(
        modifier = Modifier
            .graphicsLayer { translationX = shake.value * density }
            .semantics { contentDescription = "$filled digits entered" },
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(count) { i ->
            val on = i < filled
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (on) nbms.drawerPrimary else Color.Transparent)
                    .border(1.5.dp, if (on) nbms.drawerPrimary else nbms.drawerForeground.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

@Composable
private fun LockKeypad(
    compact: Boolean,
    enabled: Boolean,
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit
) {
    val size = if (compact) 60.dp else 72.dp
    val gap = if (compact) 10.dp else 14.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { ch ->
                    KeyCircle(size, enabled, label = ch.toString(), description = ch.toString()) { onDigit(ch) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            KeyCircle(size, enabled && canSubmit, label = "OK", description = "Submit PIN", onClick = onSubmit)
            KeyCircle(size, enabled, label = "0", description = "0") { onDigit('0') }
            KeyCircle(size, enabled, label = "⌫", description = "Delete", onClick = onDelete)
        }
    }
}

@Composable
private fun KeyCircle(
    size: Dp,
    enabled: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    val nbms = MaterialTheme.nbms
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (pressed && enabled) nbms.drawerAccent.copy(alpha = 0.9f) else nbms.drawerAccent)
            .border(1.dp, nbms.drawerBorder, CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = nbms.drawerAccentForeground
        )
    }
}
