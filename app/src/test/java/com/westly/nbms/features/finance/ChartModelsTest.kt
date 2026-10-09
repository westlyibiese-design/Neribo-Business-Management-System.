package com.westly.nbms.features.finance

import androidx.compose.ui.graphics.Color
import com.westly.nbms.features.finance.charts.BarGroup
import com.westly.nbms.features.finance.charts.ChartPoint
import com.westly.nbms.features.finance.charts.DonutSlice
import com.westly.nbms.features.finance.charts.donutArcs
import com.westly.nbms.features.finance.charts.donutHitIndex
import com.westly.nbms.features.finance.charts.donutPercent
import com.westly.nbms.features.finance.charts.groupIndexAt
import com.westly.nbms.features.finance.charts.maxBarValue
import com.westly.nbms.features.finance.charts.nearestPointIndex
import com.westly.nbms.features.finance.charts.niceTicks
import com.westly.nbms.features.finance.charts.pointX
import com.westly.nbms.features.finance.charts.sparseLabelIndexes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartModelsTest {

    // ── models ──

    @Test fun chartPointDetailDefaultsToLabel() {
        assertEquals("12", ChartPoint("12", 5.0).detail)
        assertEquals("12 Oct", ChartPoint("12", 5.0, "12 Oct").detail)
    }

    @Test fun modelsKeepWhatTheyAreGiven() {
        val group = BarGroup("Oct", listOf(1.0, 2.0))
        val slice = DonutSlice("Room", 3.0, Color.Red)
        assertEquals(listOf(1.0, 2.0), group.values)
        assertEquals(3.0, slice.value, 0.0)
    }

    // ── y axis ──

    @Test fun ticksForRoundMaximum() {
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0), niceTicks(1000.0))
    }

    @Test fun ticksRoundTheTopUp() {
        assertEquals(listOf(0.0, 5000.0, 10000.0, 15000.0), niceTicks(12345.0))
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), niceTicks(7.0))
    }

    @Test fun emptyOrNegativeDataStillGivesAnAxis() {
        val expected = listOf(0.0, 250.0, 500.0, 750.0, 1000.0)
        assertEquals(expected, niceTicks(0.0))
        assertEquals(expected, niceTicks(-50.0))
        assertEquals(expected, niceTicks(Double.NaN))
    }

    @Test fun ticksAlwaysCoverTheMaximum() {
        listOf(0.5, 1.0, 99.0, 1234.5, 98765.0, 4_500_000.0).forEach { max ->
            val ticks = niceTicks(max)
            assertTrue("top ${ticks.last()} covers $max", ticks.last() >= max)
            assertEquals(0.0, ticks.first(), 0.0)
            assertTrue(ticks.size in 2..6)
        }
    }

    // ── x labels and touch ──

    @Test fun sparseLabelsAreEvenlySpaced() {
        assertEquals(listOf(0, 6, 12, 18, 24, 30), sparseLabelIndexes(31, 6))
        assertEquals(listOf(0, 1, 2, 3, 4), sparseLabelIndexes(5, 10))
        assertEquals(listOf(0), sparseLabelIndexes(1, 3))
        assertTrue(sparseLabelIndexes(0, 3).isEmpty())
        assertEquals(listOf(0), sparseLabelIndexes(3, 0))
    }

    @Test fun pointsAreEvenlySpacedAndASinglePointIsCentred() {
        assertEquals(50f, pointX(0, 5, 50f, 400f), 0.001f)
        assertEquals(250f, pointX(2, 5, 50f, 400f), 0.001f)
        assertEquals(450f, pointX(4, 5, 50f, 400f), 0.001f)
        assertEquals(250f, pointX(0, 1, 50f, 400f), 0.001f)
    }

    @Test fun touchFindsTheNearestPoint() {
        assertEquals(2, nearestPointIndex(250f, 5, 50f, 400f))
        assertEquals(0, nearestPointIndex(-100f, 5, 50f, 400f))
        assertEquals(4, nearestPointIndex(9999f, 5, 50f, 400f))
        assertEquals(1, nearestPointIndex(149f, 5, 50f, 400f))
        assertEquals(0, nearestPointIndex(10f, 1, 50f, 400f))
        assertNull(nearestPointIndex(10f, 0, 50f, 400f))
    }

    @Test fun touchFindsTheBarGroup() {
        assertEquals(0, groupIndexAt(50f, 6, 50f, 300f))
        assertEquals(5, groupIndexAt(349f, 6, 50f, 300f))
        assertEquals(5, groupIndexAt(350f, 6, 50f, 300f))
        assertEquals(2, groupIndexAt(160f, 6, 50f, 300f))
        assertNull(groupIndexAt(40f, 6, 50f, 300f))
        assertNull(groupIndexAt(351f, 6, 50f, 300f))
        assertNull(groupIndexAt(100f, 0, 50f, 300f))
    }

    @Test fun tallestBarIgnoresNegativesAndEmptyData() {
        assertEquals(0.0, maxBarValue(emptyList()), 0.0)
        assertEquals(0.0, maxBarValue(listOf(BarGroup("a", emptyList()))), 0.0)
        assertEquals(9.0, maxBarValue(listOf(BarGroup("a", listOf(1.0, 9.0)), BarGroup("b", listOf(4.0)))), 0.0)
        assertEquals(0.0, maxBarValue(listOf(BarGroup("a", listOf(-5.0)))), 0.0)
    }

    // ── donut ──

    @Test fun donutDropsZeroSlicesAndStartsAtTwelveOClock() {
        val arcs = donutArcs(listOf(50.0, 0.0, 50.0))
        assertEquals(listOf(0, 2), arcs.map { it.index })
        assertEquals(-89f, arcs[0].startAngle, 0.001f)
        assertEquals(178f, arcs[0].sweepAngle, 0.001f)
        assertEquals(91f, arcs[1].startAngle, 0.001f)
        assertEquals(178f, arcs[1].sweepAngle, 0.001f)
    }

    @Test fun aSingleSliceIsAFullRingWithNoGap() {
        val arcs = donutArcs(listOf(10.0))
        assertEquals(1, arcs.size)
        assertEquals(-90f, arcs[0].startAngle, 0.001f)
        assertEquals(360f, arcs[0].sweepAngle, 0.001f)
    }

    @Test fun noPositiveValueMeansNoArcs() {
        assertTrue(donutArcs(emptyList()).isEmpty())
        assertTrue(donutArcs(listOf(0.0, -3.0, Double.NaN)).isEmpty())
    }

    @Test fun arcsAndGapsFillTheWholeCircle() {
        val arcs = donutArcs(listOf(10.0, 20.0, 30.0, 40.0), gapDegrees = 2f)
        assertEquals(360f, arcs.sumOf { it.sweepAngle.toDouble() }.toFloat() + 2f * arcs.size, 0.01f)
    }

    @Test fun percentIsOfThePositiveTotal() {
        assertEquals(25, donutPercent(25.0, listOf(25.0, 75.0, 0.0)))
        assertEquals(100, donutPercent(5.0, listOf(5.0)))
        assertEquals(0, donutPercent(0.0, listOf(5.0)))
        assertEquals(0, donutPercent(5.0, emptyList()))
    }

    @Test fun donutTouchHitsTheRingOnly() {
        val values = listOf(50.0, 50.0)
        // Just right of 12 o'clock = first half; just left = second half.
        assertEquals(0, donutHitIndex(values, 10f, -60f, 50f, 75f))
        assertEquals(1, donutHitIndex(values, -10f, 60f, 50f, 75f))
        assertEquals(1, donutHitIndex(values, -10f, -60f, 50f, 75f))
        assertNull(donutHitIndex(values, 0f, 0f, 50f, 75f))
        assertNull(donutHitIndex(values, 0f, -90f, 50f, 75f))
        assertNull(donutHitIndex(emptyList(), 10f, -60f, 50f, 75f))
    }

    @Test fun donutTouchSkipsZeroSlices() {
        assertEquals(2, donutHitIndex(listOf(0.0, 0.0, 10.0), 0f, -60f, 50f, 75f))
    }
}
