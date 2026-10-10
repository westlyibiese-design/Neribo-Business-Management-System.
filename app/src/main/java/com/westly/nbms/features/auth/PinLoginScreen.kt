package com.westly.nbms.features.auth

import com.westly.nbms.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsBrandSmallStyle
import com.westly.nbms.core.design.nbmsBrandTitleStyle
import com.westly.nbms.core.feature.AuthNavigator
import com.westly.nbms.core.design.NbmsLogoMark

private val DotRed = Color(0xFFF87171)

/** Shared Device Access (route `auth/pin`): business code + PIN keypad on the dark navy background. */
@Composable
fun PinLoginScreen(nav: AuthNavigator, vm: PinLoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nbms = MaterialTheme.nbms
    val submitting = state.pad.submitting

    Column(
        Modifier
            .fillMaxSize()
            .background(nbms.drawerBackground)
            .authPhoto(R.drawable.auth_bg_pin)
            .systemBarsPadding()
            .imePadding()
    ) {
        BackLink(onClick = { if (!submitting) nav.back() })

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight
            // Short screens (or the keyboard being open) get a tighter layout so the
            // whole page fits without scrolling. Scrolling stays as a safety net.
            val compact = maxHeight < 820.dp
            val pagePadding = if (compact) 12.dp else 16.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(pagePadding)
                    .heightIn(min = minHeight - pagePadding * 2),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier
                        .widthIn(max = 384.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp)
                ) {
                    Header(compact)

                    if (state.showCodeField) {
                        BusinessCodeField(
                            value = state.codeInput,
                            error = state.codeError,
                            enabled = !submitting,
                            onChange = vm::onCodeInput
                        )
                    } else {
                        BusinessPill(
                            code = state.rememberedCode.orEmpty(),
                            enabled = !submitting,
                            onChange = vm::onChangeCode
                        )
                    }

                    PinDots(filled = state.pad.pin.length, shakeCount = state.shakeCount)

                    Keypad(
                        compact = compact,
                        canSubmit = state.pad.canSubmit,
                        submitting = submitting,
                        onDigit = vm::onDigit,
                        onDelete = vm::onDelete,
                        onSubmit = vm::submit
                    )

                    Text(
                        "Managers and Super Admins must use email login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = nbms.drawerForeground.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun BackLink(onClick: () -> Unit) {
    val nbms = MaterialTheme.nbms
    Row(
        Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            NbmsIcons.ArrowLeft,
            contentDescription = null,
            tint = nbms.drawerForeground.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            "Staff Login",
            style = MaterialTheme.typography.bodyMedium,
            color = nbms.drawerForeground.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun Header(compact: Boolean) {
    val nbms = MaterialTheme.nbms
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)
    ) {
        if (!compact) {
            NbmsLogoMark(
                size = 40.dp,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.border(1.dp, nbms.drawerBorder, RoundedCornerShape(10.dp))
            )
        }
        Box(
            Modifier
                .size(if (compact) 48.dp else 64.dp)
                .clip(CircleShape)
                .background(nbms.drawerPrimary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                NbmsIcons.Key,
                contentDescription = null,
                tint = nbms.drawerPrimaryForeground,
                modifier = Modifier.size(if (compact) 24.dp else 28.dp)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Enter PIN", style = nbmsBrandTitleStyle(), color = nbms.drawerForeground)
            Text(
                "Shared Device Access",
                style = MaterialTheme.typography.bodyMedium,
                color = nbms.drawerForeground.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun BusinessPill(code: String, enabled: Boolean, onChange: () -> Unit) {
    val nbms = MaterialTheme.nbms
    Row(
        Modifier
            .clip(CircleShape)
            .background(nbms.drawerAccent)
            .border(1.dp, nbms.drawerBorder, CircleShape)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildAnnotatedString {
                append("Business: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(code) }
                append("  ·  ")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = nbms.drawerForeground
        )
        Text(
            "Change",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = nbms.drawerPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onChange)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun BusinessCodeField(value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val nbms = MaterialTheme.nbms
    val borderColor = if (error != null) DotRed else nbms.drawerBorder
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Business code",
            style = MaterialTheme.typography.labelLarge,
            color = nbms.drawerForeground.copy(alpha = 0.8f)
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = nbms.drawerForeground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp
            ),
            cursorBrush = SolidColor(nbms.drawerPrimary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(nbms.drawerAccent)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) { inner() }
            }
        )
        Text(
            error ?: "Ask your manager for this code",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) DotRed else nbms.drawerForeground.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PinDots(filled: Int, shakeCount: Int) {
    val nbms = MaterialTheme.nbms
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current.density

    LaunchedEffect(shakeCount) {
        if (shakeCount > 0) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 500
                    0f at 0
                    -12f at 60
                    12f at 130
                    -10f at 200
                    10f at 270
                    -6f at 340
                    6f at 410
                    0f at 500
                }
            )
        }
    }

    Row(
        Modifier
            .graphicsLayer { translationX = shake.value * density }
            .semantics { contentDescription = "$filled of 4 to 6 digits entered" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until PinPadState.MAX_LENGTH) {
            val isFilled = i < filled
            val scale by animateFloatAsState(if (isFilled) 1.25f else 1f, label = "dotScale")
            val color = when {
                isFilled -> nbms.drawerPrimary
                i < PinPadState.MIN_LENGTH -> nbms.drawerBorder
                else -> nbms.drawerBorder.copy(alpha = 0.3f)
            }
            Box(
                Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun Keypad(
    compact: Boolean,
    canSubmit: Boolean,
    submitting: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit
) {
    val nbms = MaterialTheme.nbms
    val digitStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium)
    val keySize = if (compact) 60.dp else 72.dp
    Column(
        Modifier.width(if (compact) 216.dp else 240.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { d ->
                    KeyCircle(size = keySize, enabled = !submitting, onClick = { onDigit(d) }) {
                        Text(d.toString(), style = digitStyle, color = nbms.drawerForeground)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            KeyCircle(size = keySize, enabled = !submitting, onClick = onDelete) {
                Icon(
                    NbmsIcons.Backspace,
                    contentDescription = "Delete",
                    tint = nbms.drawerForeground,
                    modifier = Modifier.size(24.dp)
                )
            }
            KeyCircle(size = keySize, enabled = !submitting, onClick = { onDigit('0') }) {
                Text("0", style = digitStyle, color = nbms.drawerForeground)
            }
            OkKey(size = keySize, canSubmit = canSubmit, submitting = submitting, onClick = onSubmit)
        }
    }
}

@Composable
private fun KeyCircle(size: Dp, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val nbms = MaterialTheme.nbms
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "keyScale")
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (pressed) nbms.drawerAccent else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun OkKey(size: Dp, canSubmit: Boolean, submitting: Boolean, onClick: () -> Unit) {
    val nbms = MaterialTheme.nbms
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && canSubmit) 0.95f else 1f, label = "okScale")
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (canSubmit || submitting) 1f else 0.3f)
            .clip(CircleShape)
            .background(nbms.drawerPrimary)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = canSubmit,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = nbms.drawerPrimaryForeground,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                "OK",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = nbms.drawerPrimaryForeground
            )
        }
    }
}
