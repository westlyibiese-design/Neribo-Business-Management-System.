package com.westly.nbms.features.performance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One vertical bar: [label] under it and one whole-number value per series, bottom to top. */
internal data class StackedBar(val label: String, val values: List<Int>)

/** One segment colour and its legend name. */
internal data class StackedSeries(val name: String, val color: Color)

/** `hsl(h, s%, l%)` as a Compose colour (h in degrees, s and l from 0 to 1). */
internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/** The four segment colours of the Staff Performance chart: Check-Ins navy, Check-Outs gold, Sales green, Orders purple. */
internal val PERFORMANCE_SERIES: List<StackedSeries> = listOf(
    StackedSeries("Check-Ins", hslColor(220f, 0.55f, 0.28f)),
    StackedSeries("Check-Outs", hslColor(38f, 0.55f, 0.45f)),
    StackedSeries("Sales", hslColor(158f, 0.64f, 0.40f)),
    StackedSeries("Orders", hslColor(262f, 0.52f, 0.47f))
)

private const val AXIS_LEFT_DP = 36

/**
 * Stacked vertical bars drawn with Compose Canvas only: a faint dashed grid with whole-number labels, one bar per person
 * (segments stacked in [series] order, 3dp rounded top), the label under each bar, a legend beneath, and a bubble with the
 * bar's numbers when it is tapped (tap again to close it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StackedBarChart(
    bars: List<StackedBar>,
    series: List<StackedSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp
) {
    val scheme = MaterialTheme.colorScheme
    val gridColor = scheme.outline.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant)
    var selected by remember(bars) { mutableStateOf<Int?>(null) }

    val scale = PerformanceMath.axisScale(bars.maxOfOrNull { bar -> bar.values.sum() } ?: 0)
    val ticks = scale.ticks
    val top = scale.top

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Stacked bar chart of this month's activity for ${bars.size} people" }
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        val left = AXIS_LEFT_DP.dp.toPx()
                        val plotWidth = size.width - left
                        val hit = if (bars.isEmpty() || plotWidth <= 0f || offset.x < left) null
                        else ((offset.x - left) / (plotWidth / bars.size)).toInt().takeIf { it in bars.indices }
                        selected = if (hit == selected) null else hit
                    }
                }
        ) {
            val left = AXIS_LEFT_DP.dp.toPx()
            val plotWidth = size.width - left
            val plotTop = 12.dp.toPx()
            val plotBottom = size.height - 24.dp.toPx()
            val plotHeight = max(1f, plotBottom - plotTop)

            fun yOf(v: Int): Float = plotBottom - (v.toFloat() / top).coerceIn(0f, 1f) * plotHeight

            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            ticks.forEach { tick ->
                val y = yOf(tick)
                drawLine(gridColor, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                val layout = measurer.measure(tick.toString(), axisStyle, maxLines = 1)
                drawText(layout, topLeft = Offset(left - 8.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
            }

            if (bars.isEmpty() || series.isEmpty() || plotWidth <= 0f) return@Canvas

            val slot = plotWidth / bars.size
            val barWidth = min(slot * 0.6f, 40.dp.toPx())
            val radius = 3.dp.toPx()

            bars.forEachIndexed { index, bar ->
                val barLeft = left + slot * index + (slot - barWidth) / 2f
                if (selected == index) {
                    drawRoundRect(
                        scheme.onSurface.copy(alpha = 0.06f),
                        Offset(left + slot * index, plotTop),
                        Size(slot, plotHeight),
                        CornerRadius(radius)
                    )
                }
                val lastFilled = series.indices.lastOrNull { s -> bar.values.getOrElse(s) { 0 } > 0 }
                var base = plotBottom
                series.forEachIndexed { s, serie ->
                    val value = bar.values.getOrElse(s) { 0 }
                    if (value <= 0) return@forEachIndexed
                    val segmentTop = base - (value.toFloat() / top).coerceIn(0f, 1f) * plotHeight
                    val segmentHeight = base - segmentTop
                    if (segmentHeight > 0f) {
                        if (s == lastFilled) {
                            val r = min(radius, min(barWidth / 2f, segmentHeight))
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = barLeft, top = segmentTop, right = barLeft + barWidth, bottom = base,
                                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                                        bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero
                                    )
                                )
                            }
                            drawPath(path, serie.color)
                        } else {
                            drawRect(serie.color, Offset(barLeft, segmentTop), Size(barWidth, segmentHeight))
                        }
                    }
                    base = segmentTop
                }
                val labelLayout = measurer.measure(
                    text = bar.label,
                    style = axisStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = max(1, slot.toInt()))
                )
                val cx = left + slot * index + slot / 2f
                drawText(
                    labelLayout,
                    topLeft = Offset((cx - labelLayout.size.width / 2f).coerceIn(0f, max(0f, size.width - labelLayout.size.width)), plotBottom + 6.dp.toPx())
                )
            }

            // Bubble for the tapped bar: the person, then one line per series.
            val index = selected?.takeIf { it in bars.indices } ?: return@Canvas
            val bar = bars[index]
            val bubbleBg = scheme.inverseSurface
            val bubbleFg = scheme.inverseOnSurface
            val titleLayout = measurer.measure(bar.label, TextStyle(fontSize = 11.sp, color = bubbleFg.copy(alpha = 0.8f)), maxLines = 1)
            val lines = series.mapIndexed { s, serie ->
                serie to measurer.measure(
                    "${serie.name}: ${bar.values.getOrElse(s) { 0 }}",
                    TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = bubbleFg),
                    maxLines = 1
                )
            }
            val padX = 10.dp.toPx()
            val padY = 6.dp.toPx()
            val dot = 8.dp.toPx()
            val dotGap = 6.dp.toPx()
            val bubbleW = max(titleLayout.size.width.toFloat(), lines.maxOf { it.second.size.width } + dot + dotGap) + padX * 2
            val bubbleH = titleLayout.size.height + lines.sumOf { it.second.size.height } + padY * 2
            val cx = left + slot * index + slot / 2f
            val bubbleX = (cx - bubbleW / 2f).coerceIn(0f, max(0f, size.width - bubbleW))
            drawRoundRect(bubbleBg, Offset(bubbleX, plotTop), Size(bubbleW, bubbleH), CornerRadius(6.dp.toPx()))
            var y = plotTop + padY
            drawText(titleLayout, topLeft = Offset(bubbleX + padX, y))
            y += titleLayout.size.height
            for ((serie, layout) in lines) {
                drawCircle(serie.color, radius = dot / 2f, center = Offset(bubbleX + padX + dot / 2f, y + layout.size.height / 2f))
                drawText(layout, topLeft = Offset(bubbleX + padX + dot + dotGap, y))
                y += layout.size.height
            }
        }

        // Legend row beneath the chart.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            series.forEach { serie ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(serie.color))
                    Text(serie.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
