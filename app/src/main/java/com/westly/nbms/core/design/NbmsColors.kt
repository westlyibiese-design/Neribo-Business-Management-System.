package com.westly.nbms.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra colours that Material 3 has no slot for. Read them with `MaterialTheme.nbms`.
 * The drawer* colours are the always-dark navy used by the navigation drawer and sign-in screens.
 */
@Immutable
class NbmsColors(
    val isDark: Boolean,
    val gold: Color,
    val goldForeground: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val destructive: Color,
    val onDestructive: Color,
    val destructiveContainer: Color,
    val onDestructiveContainer: Color,
    val ring: Color,
    val inputBorder: Color,
    val cardBorder: Color,
    val popoverBorder: Color,
    val primaryBorder: Color,
    val secondaryBorder: Color,
    val destructiveBorder: Color,
    val buttonOutline: Color,
    val badgeOutline: Color,
    val elevate1: Color,
    val elevate2: Color,
    val toastInfo: Color,
    val drawerBackground: Color,
    val drawerForeground: Color,
    val drawerBorder: Color,
    val drawerPrimary: Color,
    val drawerPrimaryForeground: Color,
    val drawerAccent: Color,
    val drawerAccentForeground: Color,
    val charts: List<Color>
) {
    /** Pill colours for a role key such as "super_admin". Unknown keys get the muted gray pill. */
    fun rolePill(roleKey: String): PillColors = pick(RolePillSpecs[roleKey] ?: StatusPillSpecs.getValue("out_of_service"))

    /** Pill colours for room, booking, approval and order statuses such as "checked_in". */
    fun statusPill(statusKey: String): PillColors = pick(StatusPillSpecs[statusKey] ?: StatusPillSpecs.getValue("out_of_service"))

    private fun pick(spec: PillSpec): PillColors = if (isDark) spec.dark else spec.light
}

internal val LightNbmsColors = NbmsColors(
    isDark = false,
    gold = LightTokens.gold,
    goldForeground = LightTokens.goldForeground,
    success = LightTokens.success,
    onSuccess = LightTokens.onSuccess,
    successContainer = LightTokens.successContainer,
    onSuccessContainer = LightTokens.onSuccessContainer,
    warning = LightTokens.warning,
    onWarning = LightTokens.onWarning,
    warningContainer = LightTokens.warningContainer,
    onWarningContainer = LightTokens.onWarningContainer,
    info = LightTokens.info,
    onInfo = LightTokens.onInfo,
    infoContainer = LightTokens.infoContainer,
    onInfoContainer = LightTokens.onInfoContainer,
    destructive = LightTokens.destructive,
    onDestructive = LightTokens.destructiveForeground,
    destructiveContainer = LightTokens.errorContainer,
    onDestructiveContainer = LightTokens.onErrorContainer,
    ring = LightTokens.ring,
    inputBorder = LightTokens.input,
    cardBorder = LightTokens.cardBorder,
    popoverBorder = LightTokens.popoverBorder,
    primaryBorder = LightTokens.primaryBorder,
    secondaryBorder = LightTokens.secondaryBorder,
    destructiveBorder = LightTokens.destructiveBorder,
    buttonOutline = LightTokens.buttonOutline,
    badgeOutline = LightTokens.badgeOutline,
    elevate1 = LightTokens.elevate1,
    elevate2 = LightTokens.elevate2,
    toastInfo = FixedTokens.toastInfo,
    drawerBackground = LightTokens.drawerBackground,
    drawerForeground = LightTokens.drawerForeground,
    drawerBorder = LightTokens.drawerBorder,
    drawerPrimary = LightTokens.drawerPrimary,
    drawerPrimaryForeground = LightTokens.drawerPrimaryForeground,
    drawerAccent = LightTokens.drawerAccent,
    drawerAccentForeground = LightTokens.drawerAccentForeground,
    charts = LightTokens.charts
)

internal val DarkNbmsColors = NbmsColors(
    isDark = true,
    gold = DarkTokens.gold,
    goldForeground = DarkTokens.goldForeground,
    success = DarkTokens.success,
    onSuccess = DarkTokens.onSuccess,
    successContainer = DarkTokens.successContainer,
    onSuccessContainer = DarkTokens.onSuccessContainer,
    warning = DarkTokens.warning,
    onWarning = DarkTokens.onWarning,
    warningContainer = DarkTokens.warningContainer,
    onWarningContainer = DarkTokens.onWarningContainer,
    info = DarkTokens.info,
    onInfo = DarkTokens.onInfo,
    infoContainer = DarkTokens.infoContainer,
    onInfoContainer = DarkTokens.onInfoContainer,
    destructive = DarkTokens.destructive,
    onDestructive = DarkTokens.destructiveForeground,
    destructiveContainer = DarkTokens.errorContainer,
    onDestructiveContainer = DarkTokens.onErrorContainer,
    ring = DarkTokens.ring,
    inputBorder = DarkTokens.input,
    cardBorder = DarkTokens.cardBorder,
    popoverBorder = DarkTokens.popoverBorder,
    primaryBorder = DarkTokens.primaryBorder,
    secondaryBorder = DarkTokens.secondaryBorder,
    destructiveBorder = DarkTokens.destructiveBorder,
    buttonOutline = DarkTokens.buttonOutline,
    badgeOutline = DarkTokens.badgeOutline,
    elevate1 = DarkTokens.elevate1,
    elevate2 = DarkTokens.elevate2,
    toastInfo = FixedTokens.toastInfo,
    drawerBackground = DarkTokens.drawerBackground,
    drawerForeground = DarkTokens.drawerForeground,
    drawerBorder = DarkTokens.drawerBorder,
    drawerPrimary = DarkTokens.drawerPrimary,
    drawerPrimaryForeground = DarkTokens.drawerPrimaryForeground,
    drawerAccent = DarkTokens.drawerAccent,
    drawerAccentForeground = DarkTokens.drawerAccentForeground,
    charts = DarkTokens.charts
)

val LocalNbmsColors = staticCompositionLocalOf { LightNbmsColors }

/** Access the extra NBMS colours: `MaterialTheme.nbms.gold`. */
val MaterialTheme.nbms: NbmsColors
    @Composable
    @ReadOnlyComposable
    get() = LocalNbmsColors.current
