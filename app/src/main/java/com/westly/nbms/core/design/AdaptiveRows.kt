package com.westly.nbms.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The value never takes more than this share of the row before the row stacks. */
private const val MAX_VALUE_SHARE = 0.60f

/** The label needs at least this share of the row to stay side by side with the value. */
private const val MIN_LABEL_SHARE = 0.35f

/** Width at and above which [AdaptiveTwoColumn] uses two columns. */
private val TWO_COLUMN_MIN_WIDTH = 600.dp

private val ROW_GAP = 12.dp

/**
 * A "label on the left, value on the right" row that never squeezes the value into a thin column.
 *
 * - The value keeps its natural single-line width (up to about 60% of the row).
 * - The label gets what is left and may wrap to 2 lines.
 * - If the label would get under about 35% of the row, or the value cannot fit on one line,
 *   the row stacks: label on top, value underneath at full width.
 * - Money (anything that does not start with a letter or digit, such as a currency symbol) and short
 *   numbers are never broken: one line, ellipsis only as a last resort. Dates and times wrap only at spaces.
 * - [note] sits on its own line under the value, small, and wraps at spaces.
 */
@Composable
fun LabelValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    valueWeight: FontWeight? = null,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    labelColor: Color = Color.Unspecified
) {
    val keepOnOneLine = isUnbreakableValue(value)
    val noteTint = if (noteColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else noteColor
    val noteContent: (@Composable () -> Unit)? = if (note != null) {
        { Text(note, style = MaterialTheme.typography.bodySmall, color = noteTint, textAlign = TextAlign.End) }
    } else null
    LabelValueRowSlot(
        label = label,
        modifier = modifier,
        labelStyle = labelStyle,
        labelColor = if (labelColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else labelColor,
        note = noteContent
    ) {
        Text(
            value,
            style = if (valueWeight != null) valueStyle.copy(fontWeight = valueWeight) else valueStyle,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            textAlign = TextAlign.End,
            softWrap = !keepOnOneLine,
            maxLines = if (keepOnOneLine) 1 else Int.MAX_VALUE,
            overflow = if (keepOnOneLine) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    }
}

/**
 * Same layout as [LabelValueRow], for values that are not one plain string (coloured pieces, badges).
 * Whatever [value] draws is measured at its natural width. Keep [value] to one line of content;
 * put long secondary text in [note].
 */
@Composable
fun LabelValueRowSlot(
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    note: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Text(label, style = labelStyle, color = labelColor)
            value()
            if (note != null) note()
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = ROW_GAP.roundToPx()
        val labelM = measurables[0]
        val valueM = measurables[1]
        val noteM = measurables.getOrNull(2)

        val maxValueW = (width * MAX_VALUE_SHARE).roundToInt()
        val valueNatural = valueM.maxIntrinsicWidth(Constraints.Infinity)
        val noteNatural = noteM?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val blockNatural = max(valueNatural, min(noteNatural, maxValueW))
        val labelRoom = width - blockNatural - gap

        val sideBySide = valueNatural <= maxValueW && labelRoom >= (width * MIN_LABEL_SHARE).roundToInt()

        val labelP: Placeable
        val valueP: Placeable
        val noteP: Placeable?
        val height: Int
        if (sideBySide) {
            labelP = labelM.measure(Constraints(maxWidth = labelRoom.coerceAtLeast(0)))
            valueP = valueM.measure(Constraints(maxWidth = blockNatural))
            noteP = noteM?.measure(Constraints(maxWidth = blockNatural))
            val valueBlockH = valueP.height + (noteP?.height ?: 0)
            height = max(labelP.height, valueBlockH)
            layout(width, height) {
                labelP.placeRelative(0, Alignment.Top.align(labelP.height, height))
                val top = if (noteP == null) Alignment.CenterVertically.align(valueBlockH, height) else 0
                valueP.placeRelative(width - valueP.width, top)
                noteP?.placeRelative(width - noteP.width, top + valueP.height)
            }
        } else {
            labelP = labelM.measure(Constraints(maxWidth = width))
            valueP = valueM.measure(Constraints(maxWidth = width))
            noteP = noteM?.measure(Constraints(maxWidth = width))
            height = labelP.height + valueP.height + (noteP?.height ?: 0)
            layout(width, height) {
                labelP.placeRelative(0, 0)
                valueP.placeRelative(width - valueP.width, labelP.height)
                noteP?.placeRelative(width - noteP.width, labelP.height + valueP.height)
            }
        }
    }
}

/**
 * Lays out its direct children as a 2-column grid when the width is 600dp or more, and as one stacked
 * column on phones. Each top-level child is one cell. Use it for 2-column form fields and detail grids.
 */
@Composable
fun AdaptiveTwoColumn(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val columns = if (width >= TWO_COLUMN_MIN_WIDTH.roundToPx()) 2 else 1
        val cellW = (width - gap * (columns - 1)) / columns
        val placeables = measurables.map { it.measure(Constraints(minWidth = cellW, maxWidth = cellW)) }
        val rows = placeables.chunked(columns)
        val rowHeights = rows.map { r -> r.maxOf { it.height } }
        val height = rowHeights.sum() + gap * max(rows.size - 1, 0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { i, r ->
                r.forEachIndexed { c, p -> p.placeRelative(c * (cellW + gap), y) }
                y += rowHeights[i] + gap
            }
        }
    }
}

/**
 * Puts two or three items side by side, spread to both edges, but drops the one that does not fit onto the next
 * line instead of squeezing it. Use it for card rows such as "amount ... payment method".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveSideBySide(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}

/** Chips (or any small items) that wrap onto the next line instead of running off the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(
    modifier: Modifier = Modifier,
    horizontalSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    verticalSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        content()
    }
}

/**
 * True for money (starts with a currency symbol or sign), and for short numbers/codes with no space.
 * Those must never be split inside the word or number.
 */
internal fun isUnbreakableValue(value: String): Boolean {
    val t = value.trim()
    if (t.isEmpty()) return false
    val first = t.first()
    if (!first.isLetterOrDigit()) return true
    return !t.contains(' ') && t.length <= 14
}
