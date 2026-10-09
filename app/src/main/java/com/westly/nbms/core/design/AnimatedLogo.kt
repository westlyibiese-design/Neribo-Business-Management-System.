package com.westly.nbms.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val Navy900 = Color(0xFF040A1C)
private val Navy700 = Color(0xFF0A1A42)
private val Navy600 = Color(0xFF0C2050)
private val GoldLight = Color(0xFFFFD940)
private val Gold = Color(0xFFE8A91F)
private val GoldDark = Color(0xFFB8791A)

private val DrawEase = CubicBezierEasing(0.65f, 0f, 0.25f, 1f)
private val OrbEase = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** The logo's design space is 1254 x 1254 (the SVG viewBox). */
private const val VIEW = 1254f

private class ArchSpec(val centerX: Float, val radius: Float, val springY: Float, val bottomY: Float, val width: Float, val delay: Float)

private val Arches = listOf(
    ArchSpec(627.5f, 280.5f, 580.5f, 941f, 60f, 0.25f),
    ArchSpec(627.5f, 183.5f, 590f, 941f, 50f, 0.60f),
    ArchSpec(627.5f, 104f, 608.5f, 941f, 33f, 0.95f)
)

private fun archPath(a: ArchSpec): Path = Path().apply {
    moveTo(a.centerX - a.radius, a.bottomY)
    lineTo(a.centerX - a.radius, a.springY)
    arcTo(
        rect = Rect(a.centerX - a.radius, a.springY - a.radius, a.centerX + a.radius, a.springY + a.radius),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
    )
    lineTo(a.centerX + a.radius, a.bottomY)
}

private fun progress(t: Float, start: Float, duration: Float): Float = ((t - start) / duration).coerceIn(0f, 1f)

/** 0 -> 1 -> 0 smoothly, repeating every [period] seconds after [start]. */
private fun pulse(t: Float, start: Float, period: Float): Float {
    if (t < start) return 0f
    val u = ((t - start) / period) % 1f
    return 0.5f - 0.5f * cos(2f * PI.toFloat() * u)
}

/**
 * Full-screen animated NBMS logo: three golden arches draw themselves, a glowing sun appears, a light sweeps across.
 * It paints its own navy background, so it looks the same in light and dark mode. [footer] is for an optional
 * hint at the bottom (for example "Still connecting…").
 */
@Composable
fun AnimatedLogoScreen(
    modifier: Modifier = Modifier,
    footer: @Composable BoxScope.() -> Unit = {}
) {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            seconds = (now - start) / 1_000_000_000f
        }
    }

    val measured = remember {
        Arches.map { a -> a to archPath(a) }.map { (a, p) ->
            val m = PathMeasure().apply { setPath(p, false) }
            Triple(a, p, m)
        }
    }
    val segment = remember { Path() }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = seconds
            drawBackground()
            drawHalo(t)

            val side = min(min(size.width, size.height) * 0.78f, 560.dp.toPx())
            val floatY = -8.dp.toPx() * pulse(t, 3f, 7f)
            translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f + floatY) {
                scale(scale = side / VIEW, pivot = Offset.Zero) {
                    // Arches draw themselves, outer to inner.
                    measured.forEach { (a, path, measure) ->
                        val p = DrawEase.transform(progress(t, a.delay, 1.5f))
                        if (p > 0f) {
                            segment.rewind()
                            measure.getSegment(0f, measure.length * p, segment, true)
                            val top = a.springY - a.radius
                            val brush = Brush.verticalGradient(
                                0f to GoldLight, 0.55f to Gold, 1f to GoldDark,
                                startY = top, endY = a.bottomY
                            )
                            // A soft glow under the stroke, then the stroke itself.
                            drawPath(segment, GoldLight.copy(alpha = 0.10f), style = Stroke(a.width + 16f, cap = StrokeCap.Butt))
                            drawPath(segment, brush, style = Stroke(a.width, cap = StrokeCap.Butt))
                            // Light sweep across the finished arches.
                            val sp = progress(t, 3f, 2.2f)
                            if (sp > 0f && sp < 1f) {
                                val x = -220f + (VIEW + 440f) * FastOutSlowInEasing.transform(sp)
                                val shine = Brush.linearGradient(
                                    0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.55f), 1f to Color.Transparent,
                                    start = Offset(x, 250f), end = Offset(x + 220f, 250f + 80f)
                                )
                                drawPath(path, shine, style = Stroke(a.width, cap = StrokeCap.Butt))
                            }
                        }
                    }
                    drawOrb(t)
                }
            }
        }
        footer()
    }
}

private fun DrawScope.drawBackground() {
    val center = Offset(size.width / 2f, size.height * 0.45f)
    val radius = max(
        max(hypot(center.x, center.y), hypot(size.width - center.x, center.y)),
        max(hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y))
    )
    drawRect(
        Brush.radialGradient(
            0f to Navy600, 0.45f to Navy700, 1f to Navy900,
            center = center, radius = radius
        )
    )
}

private fun DrawScope.drawHalo(t: Float) {
    val appear = progress(t, 1.6f, 2f)
    if (appear <= 0f) return
    val p = pulse(t, 3.6f, 5f)
    val diameter = min(min(size.width, size.height) * 0.7f, 640.dp.toPx()) * (1f + 0.08f * p)
    val alpha = appear * (1f - 0.3f * p)
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(1f, 200f / 255f, 50f / 255f, 0.16f * alpha),
            0.65f to Color(1f, 200f / 255f, 50f / 255f, 0f),
            center = c, radius = diameter / 2f
        ),
        radius = diameter / 2f,
        center = c
    )
}

private fun DrawScope.drawOrb(t: Float) {
    val p = progress(t, 1.9f, 1f)
    if (p <= 0f) return
    val grow = OrbEase.transform(p)
    val center = Offset(627.5f, 756f)
    scale(scale = grow.coerceAtLeast(0f), pivot = center) {
        val breathe = pulse(t, 3f, 3.2f)
        val glowScale = 1f + 0.35f * breathe
        val glowAlpha = 0.9f + 0.1f * breathe
        scale(scale = glowScale, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to GoldLight.copy(alpha = 0.75f * glowAlpha), 1f to GoldLight.copy(alpha = 0f),
                    center = center, radius = 105f
                ),
                radius = 105f,
                center = center
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFFFFBE0), 1f to Color(0xFFFFE566),
                center = center, radius = 49f
            ),
            radius = 49f,
            center = center
        )
    }
}
