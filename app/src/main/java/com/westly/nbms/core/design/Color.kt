package com.westly.nbms.core.design

import androidx.compose.ui.graphics.Color

/**
 * The ONLY file in the app that contains hex colour values (Westly Hotel design tokens).
 * Everything else reads colours from MaterialTheme.colorScheme or MaterialTheme.nbms.
 */
internal object LightTokens {
    val background = Color(0xFFF9F8F6)
    val foreground = Color(0xFF1B212D)
    val border = Color(0xFFDCDFE5)
    val input = Color(0xFFDCDFE5)
    val ring = Color(0xFF223E77)
    val card = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFE5E7EB)
    val popoverBorder = Color(0xFFDCDFE5)
    val primary = Color(0xFF203A6F)
    val primaryForeground = Color(0xFFFAF6F0)
    val primaryContainer = Color(0xFFE1E7F2)
    val secondary = Color(0xFFB28434)
    val secondaryForeground = Color(0xFF1B212D)
    val muted = Color(0xFFEDEFF2)
    val mutedForeground = Color(0xFF68748D)
    val accent = Color(0xFFF4EDE1)
    val accentForeground = Color(0xFF6F5220)
    val destructive = Color(0xFFDC2828)
    val destructiveForeground = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFFEE2E2)
    val onErrorContainer = Color(0xFF991B1B)
    val tertiary = Color(0xFF673AB6)
    val onTertiary = Color(0xFFFFFFFF)
    val inverseSurface = Color(0xFF141924)
    val inverseOnSurface = Color(0xFFD3D7DE)
    val inversePrimary = Color(0xFFD2962D)

    val primaryBorder = Color(0xFF172A4F)
    val secondaryBorder = Color(0xFF926C2A)
    val destructiveBorder = Color(0xFFBD1F1F)
    val buttonOutline = Color(0x1A000000)
    val badgeOutline = Color(0x0D000000)
    val elevate1 = Color(0x08000000)
    val elevate2 = Color(0x14000000)

    val gold = Color(0xFFB28434)
    val goldForeground = Color(0xFF1B212D)

    val success = Color(0xFF16A34A)
    val onSuccess = Color(0xFFFFFFFF)
    val successContainer = Color(0xFFDCFCE7)
    val onSuccessContainer = Color(0xFF166534)
    val warning = Color(0xFFFBBF24)
    val onWarning = Color(0xFF1B212D)
    val warningContainer = Color(0xFFFEF3C7)
    val onWarningContainer = Color(0xFF92400E)
    val info = Color(0xFF2563EB)
    val onInfo = Color(0xFFFFFFFF)
    val infoContainer = Color(0xFFDBEAFE)
    val onInfoContainer = Color(0xFF1E40AF)

    val drawerBackground = Color(0xFF141924)
    val drawerForeground = Color(0xFFD3D7DE)
    val drawerBorder = Color(0xFF212A3B)
    val drawerPrimary = Color(0xFFD2962D)
    val drawerPrimaryForeground = Color(0xFF0F121A)
    val drawerAccent = Color(0xFF1F2737)
    val drawerAccentForeground = Color(0xFFE8EAEE)

    val charts = listOf(
        Color(0xFF203A6F), Color(0xFFB28434), Color(0xFF25A777), Color(0xFFDC2828), Color(0xFF673AB6)
    )
}

internal object DarkTokens {
    val background = Color(0xFF11151D)
    val foreground = Color(0xFFE2E4E9)
    val border = Color(0xFF2D3443)
    val input = Color(0xFF333B4C)
    val ring = Color(0xFFD2962D)
    val card = Color(0xFF161C27)
    val cardBorder = Color(0xFF29303D)
    val popoverBorder = Color(0xFF29303D)
    val primary = Color(0xFFD49A35)
    val primaryForeground = Color(0xFF0F121A)
    val primaryContainer = Color(0xFF3A3220)
    val secondary = Color(0xFF252E41)
    val secondaryForeground = Color(0xFFE2E4E9)
    val muted = Color(0xFF222A39)
    val mutedForeground = Color(0xFF7E89A0)
    val accent = Color(0xFF212A3B)
    val accentForeground = Color(0xFFDBAB57)
    val destructive = Color(0xFFCA2B2B)
    val destructiveForeground = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFF7F1D1D)
    val onErrorContainer = Color(0xFFF87171)
    val tertiary = Color(0xFF916CD0)
    val onTertiary = Color(0xFF0F121A)
    val inverseSurface = Color(0xFFE2E4E9)
    val inverseOnSurface = Color(0xFF11151D)
    val inversePrimary = Color(0xFF203A6F)

    val primaryBorder = Color(0xFFDCAD5B)
    val secondaryBorder = Color(0xFF35435F)
    val destructiveBorder = Color(0xFFD94A4A)
    val buttonOutline = Color(0x1AFFFFFF)
    val badgeOutline = Color(0x0DFFFFFF)
    val elevate1 = Color(0x0AFFFFFF)
    val elevate2 = Color(0x17FFFFFF)

    val gold = Color(0xFFD49A35)
    val goldForeground = Color(0xFF0F121A)

    val success = Color(0xFF16A34A)
    val onSuccess = Color(0xFFFFFFFF)
    val successContainer = Color(0x4D14532D)
    val onSuccessContainer = Color(0xFF4ADE80)
    val warning = Color(0xFFFBBF24)
    val onWarning = Color(0xFF0F121A)
    val warningContainer = Color(0x4D78350F)
    val onWarningContainer = Color(0xFFFBBF24)
    val info = Color(0xFF60A5FA)
    val onInfo = Color(0xFF0F121A)
    val infoContainer = Color(0x4D1E3A8A)
    val onInfoContainer = Color(0xFF60A5FA)

    val drawerBackground = Color(0xFF0C1017)
    val drawerForeground = Color(0xFFBEC4CF)
    val drawerBorder = Color(0xFF1B212D)
    val drawerPrimary = Color(0xFFD49A35)
    val drawerPrimaryForeground = Color(0xFF0F121A)
    val drawerAccent = Color(0xFF1A202E)
    val drawerAccentForeground = Color(0xFFDCDFE5)

    val charts = listOf(
        Color(0xFFD49A35), Color(0xFF6186D1), Color(0xFF38BC8C), Color(0xFFD84646), Color(0xFF916CD0)
    )
}

/** Colours that do not change with the theme. */
internal object FixedTokens {
    val shadowTint = Color(0xFF222C50)
    val toastInfo = Color(0xFF203A6F)
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)
    val scrimDialog = Color(0xCC000000)
    val scrimDrawer = Color(0x99000000)
}

/** Container (background) and content (text) colours for a pill / badge. */
data class PillColors(val container: Color, val content: Color)

internal class PillSpec(val light: PillColors, val dark: PillColors)

private fun spec(lightBg: Long, lightFg: Long, darkBg: Long, darkFg: Long, darkBgAlpha: Float = 0.30f): PillSpec =
    PillSpec(
        light = PillColors(Color(0xFF000000 or lightBg), Color(0xFF000000 or lightFg)),
        dark = PillColors(Color(0xFF000000 or darkBg).copy(alpha = darkBgAlpha), Color(0xFF000000 or darkFg))
    )

/** ROLE_COLORS from the Westly design (Appendix B, section 9.1). Developer role does not exist in NBMS. */
internal val RolePillSpecs: Map<String, PillSpec> = mapOf(
    "super_admin" to spec(0xF3E8FF, 0x6B21A8, 0x581C87, 0xC084FC),
    "manager" to spec(0xDBEAFE, 0x1E40AF, 0x1E3A8A, 0x60A5FA),
    "receptionist" to spec(0xDCFCE7, 0x166534, 0x14532D, 0x4ADE80),
    "accountant" to spec(0xFEF9C3, 0x854D0E, 0x713F12, 0xFACC15),
    "staff" to spec(0xFFEDD5, 0x9A3412, 0x7C2D12, 0xFB923C),
    "waiter" to spec(0xFCE7F3, 0x9D174D, 0x831843, 0xF472B6),
    "housekeeping" to spec(0xCCFBF1, 0x115E59, 0x134E4A, 0x2DD4BF),
    "bar_attendant" to spec(0xFAE8FF, 0x86198F, 0x701A75, 0xE879F9),
    "laundry_valet" to spec(0xCFFAFE, 0x155E75, 0x164E63, 0x22D3EE),
    "operations_manager" to spec(0xE0E7FF, 0x3730A3, 0x312E81, 0x818CF8),
    "maintenance_technician" to spec(0xFEF3C7, 0x92400E, 0x78350F, 0xFBBF24),
    "security_guard" to spec(0xF5F5F4, 0x292524, 0x1C1917, 0xA8A29E),
    "driver" to spec(0xECFCCB, 0x3F6212, 0x365314, 0xA3E635),
    "restaurant_attendant" to spec(0xFFE4E6, 0x9F1239, 0x881337, 0xFB7185),
    "kitchen_staff" to spec(0xFEE2E2, 0x991B1B, 0x7F1D1D, 0xF87171),
    "gym_staff" to spec(0xD1FAE5, 0x065F46, 0x064E3B, 0x34D399)
)

private val GreenSpec = spec(0xDCFCE7, 0x166534, 0x14532D, 0x4ADE80)
private val RedSpec = spec(0xFEE2E2, 0x991B1B, 0x7F1D1D, 0xF87171)
private val YellowSpec = spec(0xFEF9C3, 0x854D0E, 0x713F12, 0xFACC15)
private val BlueSpec = spec(0xDBEAFE, 0x1E40AF, 0x1E3A8A, 0x60A5FA)
private val OrangeSpec = spec(0xFFEDD5, 0x9A3412, 0x7C2D12, 0xFB923C)
private val AmberSpec = spec(0xFEF3C7, 0x92400E, 0x78350F, 0xFBBF24)
private val GraySpec = spec(0xF3F4F6, 0x4B5563, 0x1F2937, 0x9CA3AF, darkBgAlpha = 1f)
private val GrayDoneSpec = spec(0xF3F4F6, 0x1F2937, 0x1F2937, 0x9CA3AF, darkBgAlpha = 1f)

/** Room status, booking status and order status pill colours (Appendix B, sections 2e, 9.2, 9.5). */
internal val StatusPillSpecs: Map<String, PillSpec> = mapOf(
    "available" to GreenSpec,
    "occupied" to RedSpec,
    "cleaning" to YellowSpec,
    "reserved" to BlueSpec,
    "maintenance" to OrangeSpec,
    "out_of_service" to GraySpec,
    "pending" to YellowSpec,
    "confirmed" to GreenSpec,
    "checked_in" to BlueSpec,
    "checked_out" to GrayDoneSpec,
    "cancelled" to RedSpec,
    "rejected" to RedSpec,
    "no_show" to OrangeSpec,
    "approved" to GreenSpec,
    "preparing" to BlueSpec,
    "served" to GreenSpec,
    "awaiting_approval" to AmberSpec
)
