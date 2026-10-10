package com.westly.nbms.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Renders LabelValueRow at 320, 360 and 600dp wide, with font scale 1.0 and 2.0, and checks that a value is
 * never split inside a word or number.
 */
class LabelValueRowTest {

    @get:Rule
    val rule = createComposeRule()

    private val widths = listOf(320, 360, 600)
    private val fontScales = listOf(1.0f, 2.0f)

    @Before
    fun setUp() = startRendering()

    private var widthDp by mutableStateOf(360)
    private var fontScale by mutableStateOf(1.0f)
    private var content by mutableStateOf<@Composable () -> Unit>({})

    private fun startRendering() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(widthDp.dp)) { content() }
                }
            }
        }
    }

    /** Re-renders the same test with a new width, font scale and row. setContent may only run once per test. */
    private fun show(width: Int, scale: Float, row: @Composable () -> Unit) {
        rule.runOnIdle {
            widthDp = width
            fontScale = scale
            content = row
        }
        rule.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    /** Every line break must fall on a space, never inside a word or number. */
    private fun assertBreaksOnlyAtSpaces(text: String) {
        val layout = layoutOf(text)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val atSpace = text.substring(0, end).endsWith(" ") || text.getOrNull(end) == ' '
            assertTrue("\"$text\" was split inside a word at index $end", atSpace)
        }
    }

    @Test
    fun scheduledCheckoutDateOnlyWrapsAtSpaces() {
        val value = "12 Oct 2026, 11:00"
        for (w in widths) for (f in fontScales) {
            show(w, f) { LabelValueRow(label = "Scheduled Checkout", value = value) }
            assertBreaksOnlyAtSpaces(value)
        }
    }

    @Test
    fun roomChargesMoneyStaysOnOneLineAndNoteStaysWhole() {
        for (w in widths) for (f in fontScales) {
            show(w, f) {
                LabelValueRow(label = "Room Charges", value = "₦90,000", note = "(paid at check-in)")
            }
            assertEquals("₦90,000 at ${w}dp x$f", 1, layoutOf("₦90,000").lineCount)
            assertBreaksOnlyAtSpaces("(paid at check-in)")
        }
    }

    @Test
    fun additionalAccommodationMoneyStaysOnOneLine() {
        for (w in widths) for (f in fontScales) {
            show(w, f) { LabelValueRow(label = "Additional accommodation", value = "₦50,000") }
            assertEquals("₦50,000 at ${w}dp x$f", 1, layoutOf("₦50,000").lineCount)
        }
    }
}
