package com.westly.nbms.features.finance.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.util.Format
import kotlin.math.max

/**
 * Filled area chart: a 2dp line over a gradient (30% to 0% alpha), a faint dashed grid, y labels from [valueFormatter],
 * sparse x labels, and a value bubble while a finger taps or drags. Pure Compose Canvas.
 * [lineColor] left as [Color.Unspecified] uses the theme's first chart colour (navy in light, gold in dark).
 */
@Composable
fun AreaChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    lineColor: Color = Color.Unspecified,
    valueFormatter: (Double) -> String = { Format.currency(it) }
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (lineColor == Color.Unspecified) MaterialTheme.nbms.charts[0] else lineColor
    val gridColor = scheme.outline.copy(alpha = 0.6f)
    val labelColor = scheme.onSurfaceVariant
    val bubbleColor = scheme.inverseSurface
    val bubbleText = scheme.inverseOnSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val ticks = niceTicks(points.maxOfOrNull { it.value } ?: 0.0)
    val top = ticks.last()
    val tickLabels = ticks.map { valueFormatter(it) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Area chart with ${points.size} points" }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val metrics = plotMetrics(size.width.toFloat(), density, measurer, axisStyle, tickLabels)
                    selected = nearestPointIndex(offset.x, points.size, metrics.left, metrics.width)
                }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val metrics = plotMetrics(size.width.toFloat(), density, measurer, axisStyle, tickLabels)
                        selected = nearestPointIndex(offset.x, points.size, metrics.left, metrics.width)
                    },
                    onHorizontalDrag = { change, _ ->
                        val metrics = plotMetrics(size.width.toFloat(), density, measurer, axisStyle, tickLabels)
                        selected = nearestPointIndex(change.position.x, points.size, metrics.left, metrics.width)
                    }
                )
            }
    ) {
        val metrics = plotMetrics(size.width, density, measurer, axisStyle, tickLabels)
        val topPad = 12.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotHeight = max(1f, plotBottom - plotTop)

        fun yOf(v: Double): Float = plotBottom - (v / top).toFloat().coerceIn(0f, 1f) * plotHeight

        // Grid + y labels.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        ticks.forEachIndexed { i, tick ->
            val y = yOf(tick)
            drawLine(gridColor, Offset(metrics.left, y), Offset(metrics.left + metrics.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val layout = measurer.measure(tickLabels[i], axisStyle, maxLines = 1)
            drawText(layout, topLeft = Offset(metrics.left - 8.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        if (points.isEmpty()) return@Canvas

        // X labels (sparse).
        val maxLabels = max(1, (metrics.width / 44.dp.toPx()).toInt())
        for (i in sparseLabelIndexes(points.size, maxLabels)) {
            val layout = measurer.measure(points[i].label, axisStyle, maxLines = 1)
            val x = pointX(i, points.size, metrics.left, metrics.width)
            val textX = (x - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width))
            drawText(layout, topLeft = Offset(textX, plotBottom + 6.dp.toPx()))
        }

        // Line + gradient fill.
        val xs = points.indices.map { pointX(it, points.size, metrics.left, metrics.width) }
        val ys = points.map { yOf(it.value) }
        val line = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until points.size) lineTo(xs[i], ys[i])
        }
        if (points.size > 1) {
            val fill = Path().apply {
                addPath(line)
                lineTo(xs.last(), plotBottom)
                lineTo(xs.first(), plotBottom)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f)), startY = plotTop, endY = plotBottom)
            )
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(xs[0], ys[0]))
        }

        // Selected point: guide line, dot and bubble.
        val index = selected?.takeIf { it in points.indices } ?: return@Canvas
        val sx = xs[index]
        val sy = ys[index]
        drawLine(color.copy(alpha = 0.5f), Offset(sx, plotTop), Offset(sx, plotBottom), strokeWidth = 1.dp.toPx())
        drawCircle(scheme.surface, radius = 5.dp.toPx(), center = Offset(sx, sy))
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(sx, sy))

        val titleLayout = measurer.measure(points[index].detail, TextStyle(fontSize = 11.sp, color = bubbleText.copy(alpha = 0.8f)), maxLines = 1)
        val valueLayout = measurer.measure(
            valueFormatter(points[index].value),
            TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bubbleText),
            maxLines = 1
        )
        val padX = 10.dp.toPx()
        val padY = 6.dp.toPx()
        val bubbleW = max(titleLayout.size.width, valueLayout.size.width) + padX * 2
        val bubbleH = titleLayout.size.height + valueLayout.size.height + padY * 2
        val bubbleX = (sx - bubbleW / 2f).coerceIn(0f, max(0f, size.width - bubbleW))
        val bubbleY = (sy - bubbleH - 10.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(bubbleColor, Offset(bubbleX, bubbleY), Size(bubbleW, bubbleH), CornerRadius(6.dp.toPx()))
        drawText(titleLayout, topLeft = Offset(bubbleX + padX, bubbleY + padY))
        drawText(valueLayout, topLeft = Offset(bubbleX + padX, bubbleY + padY + titleLayout.size.height))
    }
}

/** Where the plot sits inside the canvas: [left] leaves room for the widest y label, [width] runs to a small right margin. */
internal class PlotMetrics(val left: Float, val width: Float)

internal fun plotMetrics(
    canvasWidth: Float,
    density: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    yLabels: List<String>
): PlotMetrics {
    val widest = yLabels.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
    val left = widest + 8f * density + 4f * density
    val right = 12f * density
    return PlotMetrics(left, max(1f, canvasWidth - left - right))
}
