package com.westly.nbms.core.design

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The two font families used by NBMS. Sans = Inter, serif = Playfair Display. */
@Immutable
class NbmsFonts(val sans: FontFamily, val serif: FontFamily)

val LocalNbmsFonts = staticCompositionLocalOf { NbmsFonts(FontFamily.SansSerif, FontFamily.Serif) }

/**
 * Loads the downloaded variable fonts if they exist (res/font/inter_variable.ttf and
 * res/font/playfair_display_variable.ttf, fetched by the GitHub Actions build). If a file is
 * missing the app quietly falls back to the system sans / serif fonts, so it never crashes.
 */
@OptIn(ExperimentalTextApi::class)
internal fun loadNbmsFonts(context: Context): NbmsFonts {
    fun family(resName: String, weights: List<Int>, fallback: FontFamily): FontFamily {
        val id = context.resources.getIdentifier(resName, "font", context.packageName)
        if (id == 0) return fallback
        return FontFamily(
            weights.map { w ->
                Font(
                    resId = id,
                    weight = FontWeight(w),
                    style = FontStyle.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(w))
                )
            }
        )
    }
    return NbmsFonts(
        sans = family("inter_variable", listOf(400, 500, 600, 700), FontFamily.SansSerif),
        serif = family("playfair_display_variable", listOf(600, 700), FontFamily.Serif)
    )
}

private fun style(
    family: FontFamily,
    size: Int,
    line: Int,
    weight: Int,
    tracking: TextUnit = 0.sp
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking
)

/** Material 3 typography built from the Westly sizes (Appendix B section 3). */
fun nbmsTypography(sans: FontFamily): Typography {
    val d = Typography()
    val tight = (-0.025).em
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = sans),
        displayMedium = d.displayMedium.copy(fontFamily = sans),
        displaySmall = style(sans, 36, 40, 700),
        headlineLarge = style(sans, 30, 36, 700, tight),
        headlineMedium = style(sans, 24, 32, 600, tight),
        headlineSmall = style(sans, 20, 28, 600),
        titleLarge = style(sans, 18, 28, 600),
        titleMedium = style(sans, 16, 24, 500),
        titleSmall = style(sans, 14, 20, 500),
        bodyLarge = style(sans, 16, 24, 400),
        bodyMedium = style(sans, 14, 20, 400),
        bodySmall = style(sans, 12, 16, 400),
        labelLarge = style(sans, 14, 20, 500),
        labelMedium = style(sans, 12, 16, 500),
        labelSmall = style(sans, 11, 16, 500)
    )
}

/** Page title (H1): Playfair Display 24/32 bold. */
@Composable
fun nbmsPageTitleStyle(): TextStyle =
    style(LocalNbmsFonts.current.serif, 24, 32, 700, (-0.025).em)

/** Large brand / auth title: Playfair Display 30/36 bold. */
@Composable
fun nbmsBrandTitleStyle(): TextStyle =
    style(LocalNbmsFonts.current.serif, 30, 36, 700, (-0.025).em)

/** Small brand text (drawer header): Playfair Display 14 bold. */
@Composable
fun nbmsBrandSmallStyle(): TextStyle =
    style(LocalNbmsFonts.current.serif, 14, 20, 700)
