package com.westly.nbms.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The three theme choices a person can make. Phase 7 stores the choice; Phase 0 only defines it. */
enum class ThemeMode { Light, Dark, System }

@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> isSystemInDarkTheme()
}

private val LightScheme = lightColorScheme(
    primary = LightTokens.primary,
    onPrimary = LightTokens.primaryForeground,
    primaryContainer = LightTokens.primaryContainer,
    onPrimaryContainer = LightTokens.primary,
    secondary = LightTokens.secondary,
    onSecondary = LightTokens.secondaryForeground,
    secondaryContainer = LightTokens.accent,
    onSecondaryContainer = LightTokens.accentForeground,
    tertiary = LightTokens.tertiary,
    onTertiary = LightTokens.onTertiary,
    background = LightTokens.background,
    onBackground = LightTokens.foreground,
    surface = LightTokens.card,
    onSurface = LightTokens.foreground,
    surfaceVariant = LightTokens.muted,
    onSurfaceVariant = LightTokens.mutedForeground,
    surfaceTint = LightTokens.primary,
    inverseSurface = LightTokens.inverseSurface,
    inverseOnSurface = LightTokens.inverseOnSurface,
    inversePrimary = LightTokens.inversePrimary,
    error = LightTokens.destructive,
    onError = LightTokens.destructiveForeground,
    errorContainer = LightTokens.errorContainer,
    onErrorContainer = LightTokens.onErrorContainer,
    outline = LightTokens.border,
    outlineVariant = LightTokens.cardBorder,
    scrim = FixedTokens.black,
    surfaceContainerLowest = LightTokens.card,
    surfaceContainerLow = LightTokens.background,
    surfaceContainer = LightTokens.card,
    surfaceContainerHigh = LightTokens.muted,
    surfaceContainerHighest = LightTokens.muted,
    surfaceBright = LightTokens.card,
    surfaceDim = LightTokens.muted
)

private val DarkScheme = darkColorScheme(
    primary = DarkTokens.primary,
    onPrimary = DarkTokens.primaryForeground,
    primaryContainer = DarkTokens.primaryContainer,
    onPrimaryContainer = DarkTokens.primary,
    secondary = DarkTokens.secondary,
    onSecondary = DarkTokens.secondaryForeground,
    secondaryContainer = DarkTokens.accent,
    onSecondaryContainer = DarkTokens.accentForeground,
    tertiary = DarkTokens.tertiary,
    onTertiary = DarkTokens.onTertiary,
    background = DarkTokens.background,
    onBackground = DarkTokens.foreground,
    surface = DarkTokens.card,
    onSurface = DarkTokens.foreground,
    surfaceVariant = DarkTokens.muted,
    onSurfaceVariant = DarkTokens.mutedForeground,
    surfaceTint = DarkTokens.primary,
    inverseSurface = DarkTokens.inverseSurface,
    inverseOnSurface = DarkTokens.inverseOnSurface,
    inversePrimary = DarkTokens.inversePrimary,
    error = DarkTokens.destructive,
    onError = DarkTokens.destructiveForeground,
    errorContainer = DarkTokens.errorContainer,
    onErrorContainer = DarkTokens.onErrorContainer,
    outline = DarkTokens.border,
    outlineVariant = DarkTokens.cardBorder,
    scrim = FixedTokens.black,
    surfaceContainerLowest = DarkTokens.background,
    surfaceContainerLow = DarkTokens.background,
    surfaceContainer = DarkTokens.card,
    surfaceContainerHigh = DarkTokens.muted,
    surfaceContainerHighest = DarkTokens.muted,
    surfaceBright = DarkTokens.muted,
    surfaceDim = DarkTokens.background
)

/** NBMS theme. Light = navy primary, dark = gold primary. Follows the system setting by default. */
@Composable
fun NbmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val fonts = remember(context) { loadNbmsFonts(context) }
    val typography = remember(fonts) { nbmsTypography(fonts.sans) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalNbmsColors provides (if (darkTheme) DarkNbmsColors else LightNbmsColors),
        LocalNbmsFonts provides fonts
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = typography,
            shapes = NbmsShapes,
            content = content
        )
    }
}
