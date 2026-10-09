package com.westly.nbms.features.finance.charts

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One point of an [AreaChart]. [label] is the x-axis text; [detail] is the title of the value bubble (defaults to [label]). */
data class ChartPoint(val label: String, val value: Double, val detail: String = label)

/** One group of bars (for example one month). [values] line up with the `series` list given to [GroupedBarChart]. */
data class BarGroup(val label: String, val values: List<Double>)

/** One coloured series of a [GroupedBarChart] (shown in the legend). */
data class BarSeries(val name: String, val color: Color)

/** One slice of a [DonutChart]. Slices whose [value] is 0 or less are not drawn. */
data class DonutSlice(val label: String, val value: Double, val color: Color)

// ---- Pure helpers used by the three charts (unit-tested in ChartModelsTest) -------------------

/**
 * Y-axis tick values from 0 up to a "nice" top that is at least [maxValue] (about four steps).
 * Zero or negative data gives 0, 250, 500, 750, 1000 so an empty chart still has a readable axis.
 */
internal fun niceTicks(maxValue: Double): List<Double> {
    val top = if (maxValue.isNaN() || maxValue <= 0.0) 1000.0 else maxValue
    val raw = top / 4.0
    val exponent = floor(log10(raw))
    val base = 10.0.pow(exponent)
    val fraction = raw / base
    val niceFraction = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    val step = niceFraction * base
    val count = ceil(top / step - 1e-9).toInt().coerceAtLeast(1)
    return (0..count).map { it * step }
}

/** Which x labels to draw so they never crowd each other: every n-th index, at most [maxLabels] of them. */
internal fun sparseLabelIndexes(count: Int, maxLabels: Int): List<Int> {
    if (count <= 0) return emptyList()
    val limit = max(1, maxLabels)
    val step = ceil(count.toDouble() / limit).toInt().coerceAtLeast(1)
    return (0 until count step step).toList()
}

/** Evenly spaced x position (in px) of point [index] of [count] inside a plot starting at [left] with [width]. */
internal fun pointX(index: Int, count: Int, left: Float, width: Float): Float =
    if (count <= 1) left + width / 2f else left + width * index / (count - 1).toFloat()

/** The point closest to the touch at [x] (px), or null when there are no points. */
internal fun nearestPointIndex(x: Float, count: Int, left: Float, width: Float): Int? {
    if (count <= 0) return null
    if (count == 1) return 0
    val ratio = ((x - left) / width).coerceIn(0f, 1f)
    return Math.round(ratio * (count - 1)).coerceIn(0, count - 1)
}

/** The bar group under the touch at [x] (px); each group owns an equal slot of the plot. */
internal fun groupIndexAt(x: Float, count: Int, left: Float, width: Float): Int? {
    if (count <= 0 || width <= 0f) return null
    if (x < left || x > left + width) return null
    return (((x - left) / width) * count).toInt().coerceIn(0, count - 1)
}

/** One arc of the donut: where it starts (degrees, 0 = 3 o'clock, clockwise) and how far it sweeps. */
internal data class DonutArc(val index: Int, val startAngle: Float, val sweepAngle: Float)

/**
 * Arcs for the slices with a value above zero, starting at 12 o'clock (-90 degrees). With more than one slice a small
 * [gapDegrees] is left between neighbours. No positive value gives no arcs.
 */
internal fun donutArcs(values: List<Double>, gapDegrees: Float = 2f): List<DonutArc> {
    val positive = values.mapIndexedNotNull { i, v -> if (v > 0.0 && v.isFinite()) i to v else null }
    val total = positive.sumOf { it.second }
    if (positive.isEmpty() || total <= 0.0) return emptyList()
    val gap = if (positive.size > 1) gapDegrees else 0f
    var cursor = -90f
    return positive.map { (index, value) ->
        val full = (value / total * 360.0).toFloat()
        val arc = DonutArc(index, cursor + gap / 2f, (full - gap).coerceAtLeast(0.5f))
        cursor += full
        arc
    }
}

/** Share of [value] among the positive values, as a whole percent (0 when there is no total). */
internal fun donutPercent(value: Double, values: List<Double>): Int {
    val total = values.filter { it > 0.0 && it.isFinite() }.sum()
    return if (total <= 0.0 || value <= 0.0) 0 else Math.round(value / total * 100.0).toInt()
}

/**
 * Which slice a touch hit. [dx]/[dy] are the touch offset from the donut centre in px; the touch must lie on the ring
 * between [innerRadius] and [outerRadius]. Returns the index into [values], or null.
 */
internal fun donutHitIndex(values: List<Double>, dx: Float, dy: Float, innerRadius: Float, outerRadius: Float): Int? {
    val distance = sqrt(dx * dx + dy * dy)
    if (distance < innerRadius || distance > outerRadius) return null
    val positive = values.mapIndexedNotNull { i, v -> if (v > 0.0 && v.isFinite()) i to v else null }
    val total = positive.sumOf { it.second }
    if (positive.isEmpty() || total <= 0.0) return null
    val angle = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0) % 360.0 + 360.0) % 360.0
    var cumulative = 0.0
    for ((index, value) in positive) {
        cumulative += value / total * 360.0
        if (angle < cumulative) return index
    }
    return positive.last().first
}

/** The tallest value across all groups (0 when empty); used to size the bar chart axis. */
internal fun maxBarValue(groups: List<BarGroup>): Double =
    groups.maxOfOrNull { g -> g.values.maxOrNull() ?: 0.0 }?.coerceAtLeast(0.0) ?: 0.0
