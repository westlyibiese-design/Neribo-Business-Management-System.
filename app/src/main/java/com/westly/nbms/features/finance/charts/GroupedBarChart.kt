package com.westly.nbms.features.finance.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.util.Format
import kotlin.math.max

/**
 * Grouped bars: one group per [BarGroup], one bar per [BarSeries] (3dp rounded top corners), a faint dashed grid,
 * y labels from [valueFormatter], a legend row beneath, and a bubble listing the group's values when tapped.
 * Pure Compose Canvas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    series: List<BarSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    valueFormatter: (Double) -> String = { Format.currency(it) }
) {
    val scheme = MaterialTheme.colorScheme
    val gridColor = scheme.outline.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant)
    var selected by remember(groups) { mutableStateOf<Int?>(null) }

    val ticks = niceTicks(maxBarValue(groups))
    val top = ticks.last()
    val tickLabels = ticks.map { valueFormatter(it) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Bar chart with ${groups.size} groups and ${series.size} series" }
                .pointerInput(groups) {
                    detectTapGestures { offset ->
                        val metrics = plotMetrics(size.width.toFloat(), density, measurer, axisStyle, tickLabels)
                        val hit = groupIndexAt(offset.x, groups.size, metrics.left, metrics.width)
                        selected = if (hit == selected) null else hit
                    }
                }
        ) {
            val metrics = plotMetrics(size.width, density, measurer, axisStyle, tickLabels)
            val plotTop = 12.dp.toPx()
            val plotBottom = size.height - 24.dp.toPx()
            val plotHeight = max(1f, plotBottom - plotTop)

            fun yOf(v: Double): Float = plotBottom - (v / top).toFloat().coerceIn(0f, 1f) * plotHeight

            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            ticks.forEachIndexed { i, tick ->
                val y = yOf(tick)
                drawLine(gridColor, Offset(metrics.left, y), Offset(metrics.left + metrics.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                val layout = measurer.measure(tickLabels[i], axisStyle, maxLines = 1)
                drawText(layout, topLeft = Offset(metrics.left - 8.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
            }

            if (groups.isEmpty() || series.isEmpty()) return@Canvas

            val slot = metrics.width / groups.size
            val inner = slot * 0.72f
            val gap = 2.dp.toPx()
            val barWidth = max(2f, (inner - gap * (series.size - 1)) / series.size)
            val radius = 3.dp.toPx()

            groups.forEachIndexed { g, group ->
                val groupLeft = metrics.left + slot * g + (slot - inner) / 2f
                if (selected == g) {
                    drawRoundRect(
                        scheme.onSurface.copy(alpha = 0.06f),
                        Offset(metrics.left + slot * g, plotTop),
                        Size(slot, plotHeight),
                        CornerRadius(radius)
                    )
                }
                series.forEachIndexed { s, serie ->
                    val value = group.values.getOrElse(s) { 0.0 }
                    if (value <= 0.0) return@forEachIndexed
                    val barTop = yOf(value)
                    val barHeight = plotBottom - barTop
                    if (barHeight <= 0f) return@forEachIndexed
                    val x = groupLeft + s * (barWidth + gap)
                    val r = minOf(radius, barWidth / 2f, barHeight)
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = x, top = barTop, right = x + barWidth, bottom = plotBottom,
                                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero
                            )
                        )
                    }
                    drawPath(path, serie.color)
                }
                val layout = measurer.measure(group.label, axisStyle, maxLines = 1)
                val cx = metrics.left + slot * g + slot / 2f
                drawText(layout, topLeft = Offset((cx - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width)), plotBottom + 6.dp.toPx()))
            }

            // Bubble for the tapped group: one line per series.
            val index = selected?.takeIf { it in groups.indices } ?: return@Canvas
            val group = groups[index]
            val bubbleBg = scheme.inverseSurface
            val bubbleFg = scheme.inverseOnSurface
            val titleLayout = measurer.measure(group.label, TextStyle(fontSize = 11.sp, color = bubbleFg.copy(alpha = 0.8f)), maxLines = 1)
            val lines = series.mapIndexed { s, serie ->
                serie to measurer.measure(
                    "${serie.name}: ${valueFormatter(group.values.getOrElse(s) { 0.0 })}",
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
            val cx = metrics.left + slot * index + slot / 2f
            val bubbleX = (cx - bubbleW / 2f).coerceIn(0f, max(0f, size.width - bubbleW))
            val bubbleY = plotTop
            drawRoundRect(bubbleBg, Offset(bubbleX, bubbleY), Size(bubbleW, bubbleH), CornerRadius(6.dp.toPx()))
            var y = bubbleY + padY
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
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(10.dp).clip(CircleShape).background(serie.color)
                    )
                    Text(serie.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
