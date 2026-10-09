package com.westly.nbms.features.finance.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/**
 * Ring chart. In a 180dp box the ring runs from radius 50 to 75 (the same proportions at any [size]).
 * Tapping a slice enlarges it and shows its name and share in the middle; tapping again clears it. Pure Compose Canvas.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val values = slices.map { it.value }
    val arcs = donutArcs(values)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Donut chart with ${arcs.size} slices" }
            .pointerInput(slices) {
                detectTapGestures { offset ->
                    val box = this.size.width.toFloat()
                    val centre = box / 2f
                    val hit = donutHitIndex(
                        values,
                        offset.x - centre,
                        offset.y - centre,
                        innerRadius = box * INNER_RADIUS / BOX,
                        outerRadius = box * OUTER_RADIUS / BOX
                    )
                    selected = if (hit == selected) null else hit
                }
            }
    ) {
        val box = this.size.minDimension
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val ringWidth = box * (OUTER_RADIUS - INNER_RADIUS) / BOX
        val midRadius = box * (OUTER_RADIUS + INNER_RADIUS) / 2f / BOX

        if (arcs.isEmpty()) {
            drawCircle(scheme.surfaceVariant, radius = midRadius, center = centre, style = Stroke(width = ringWidth))
            return@Canvas
        }

        arcs.forEach { arc ->
            val isSelected = selected == arc.index
            val width = if (isSelected) ringWidth * 1.12f else ringWidth
            drawArc(
                color = slices[arc.index].color,
                startAngle = arc.startAngle,
                sweepAngle = arc.sweepAngle,
                useCenter = false,
                topLeft = Offset(centre.x - midRadius, centre.y - midRadius),
                size = Size(midRadius * 2, midRadius * 2),
                style = Stroke(width = width)
            )
        }

        val index = selected?.takeIf { it in slices.indices } ?: return@Canvas
        val percent = donutPercent(slices[index].value, values)
        val nameLayout = measurer.measure(
            slices[index].label,
            TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant),
            maxLines = 1
        )
        val percentLayout = measurer.measure(
            "$percent%",
            TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface),
            maxLines = 1
        )
        val totalHeight = nameLayout.size.height + percentLayout.size.height
        drawText(nameLayout, topLeft = Offset(centre.x - nameLayout.size.width / 2f, centre.y - totalHeight / 2f))
        drawText(percentLayout, topLeft = Offset(centre.x - percentLayout.size.width / 2f, centre.y - totalHeight / 2f + nameLayout.size.height))
    }
}

private const val BOX = 180f
private const val INNER_RADIUS = 50f
private const val OUTER_RADIUS = 75f
