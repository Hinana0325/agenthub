package com.agenthub.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * AgentHub 主题模式：Light / Dark / LiquidGlass / System
 */
enum class ThemeMode { Light, Dark, LiquidGlass, System }

/**
 * 根据强调色和主题模式构建 Material3 ColorScheme
 */
fun buildColorScheme(
    accent: AccentPalette,
    isDark: Boolean,
    isGlass: Boolean = false
) = if (isDark) {
    darkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        secondaryContainer = accent.secondaryContainer,
        tertiary = accent.tertiary,
        tertiaryContainer = accent.tertiaryContainer,
        background = if (isGlass) Color.Transparent else DarkBackground,
        surface = if (isGlass) Color.Transparent else DarkSurface,
        surfaceVariant = if (isGlass) GlassSurfaceVariantDark else DarkSurfaceVariant,
        onBackground = DarkOnBackground,
        onSurface = DarkOnBackground,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        surfaceTint = DarkSurfaceTint
    )
} else {
    lightColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        secondaryContainer = accent.secondaryContainer,
        tertiary = accent.tertiary,
        tertiaryContainer = accent.tertiaryContainer,
        background = if (isGlass) Color.Transparent else LightBackground,
        surface = if (isGlass) Color.Transparent else LightSurface,
        surfaceVariant = if (isGlass) GlassSurfaceVariantLight else LightSurfaceVariant,
        onBackground = LightOnBackground,
        onSurface = LightOnBackground,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        surfaceTint = LightSurfaceTint
    )
}

val AccentPalettes: Map<String, AccentPalette> = mapOf(
    "blue" to AccentBlue,
    "teal" to AccentTeal,
    "purple" to AccentPurple,
    "coral" to AccentCoral,
    "amber" to AccentAmber,
    "green" to AccentGreen,
    "pink" to AccentPink,
    "gray" to AccentGray
)

/**
 * Parse a hex color string (e.g. "#185FA5") to a Compose Color.
 * Returns fallback if parsing fails.
 */
fun parseHexColor(hex: String, fallback: Color = Color.Unspecified): Color {
    return try {
        val cleaned = hex.trim().removePrefix("#")
        val argb = if (cleaned.length == 6) {
            0xFF000000L or cleaned.toLong(16)
        } else if (cleaned.length == 8) {
            cleaned.toLong(16)
        } else {
            return fallback
        }
        Color(argb.toULong())
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Build a custom ColorScheme from hex color strings.
 */
fun buildCustomColorScheme(
    primaryHex: String,
    accentHex: String,
    backgroundHex: String,
    isDark: Boolean,
    isGlass: Boolean = false
): androidx.compose.material3.ColorScheme {
    val primary = parseHexColor(primaryHex, if (isDark) DarkSurfaceTint else LightSurfaceTint)
    val accent = parseHexColor(accentHex, AccentBlue.secondary)
    val background = parseHexColor(backgroundHex, if (isDark) DarkBackground else LightBackground)

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.3f),
            onPrimaryContainer = Color.White,
            secondary = accent,
            secondaryContainer = accent.copy(alpha = 0.3f),
            tertiary = accent,
            tertiaryContainer = accent.copy(alpha = 0.3f),
            background = if (isGlass) Color.Transparent else background,
            surface = if (isGlass) Color.Transparent else DarkSurface,
            surfaceVariant = if (isGlass) background.copy(alpha = 0.35f) else DarkSurfaceVariant,
            onBackground = DarkOnBackground,
            onSurface = DarkOnBackground,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            surfaceTint = primary
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.15f),
            onPrimaryContainer = primary,
            secondary = accent,
            secondaryContainer = accent.copy(alpha = 0.15f),
            tertiary = accent,
            tertiaryContainer = accent.copy(alpha = 0.15f),
            background = if (isGlass) Color.Transparent else background,
            surface = if (isGlass) Color.Transparent else LightSurface,
            surfaceVariant = if (isGlass) background.copy(alpha = 0.35f) else LightSurfaceVariant,
            onBackground = LightOnBackground,
            onSurface = LightOnBackground,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            surfaceTint = primary
        )
    }
}

/** Map a persisted font-size setting to a multiplier applied to the base typography. */
private fun fontScaleFor(size: String): Float = when (size) {
    "small" -> 0.875f
    "large" -> 1.15f
    else -> 1.0f
}

/** Return a copy of [base] with every text style scaled by [factor]. */
private fun scaleTypography(base: Typography, factor: Float): Typography = Typography(
    displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * factor, lineHeight = base.displayLarge.lineHeight * factor),
    displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * factor, lineHeight = base.displayMedium.lineHeight * factor),
    headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * factor, lineHeight = base.headlineLarge.lineHeight * factor),
    headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * factor, lineHeight = base.headlineMedium.lineHeight * factor),
    titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * factor, lineHeight = base.titleLarge.lineHeight * factor),
    titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * factor, lineHeight = base.titleMedium.lineHeight * factor),
    titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * factor, lineHeight = base.titleSmall.lineHeight * factor),
    bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * factor, lineHeight = base.bodyLarge.lineHeight * factor),
    bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * factor, lineHeight = base.bodyMedium.lineHeight * factor),
    bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * factor, lineHeight = base.bodySmall.lineHeight * factor),
    labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * factor, lineHeight = base.labelLarge.lineHeight * factor),
    labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * factor, lineHeight = base.labelMedium.lineHeight * factor),
    labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * factor, lineHeight = base.labelSmall.lineHeight * factor)
)

@Composable
fun AgentHubTheme(
    themeMode: ThemeMode = ThemeMode.System,
    accentColor: String = "blue",
    fontSize: String = "medium",
    customThemeEnabled: Boolean = false,
    customPrimaryColorHex: String = "#185FA5",
    customAccentColorHex: String = "#535F70",
    customBackgroundColorHex: String = "#FDFBFF",
    customCornerRadius: Int = 16,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.LiquidGlass -> isSystemInDarkTheme()
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val isGlass = themeMode == ThemeMode.LiquidGlass
    val accent = AccentPalettes[accentColor] ?: AccentBlue
    val colorScheme = if (customThemeEnabled) {
        buildCustomColorScheme(
            primaryHex = customPrimaryColorHex,
            accentHex = customAccentColorHex,
            backgroundHex = customBackgroundColorHex,
            isDark = isDark,
            isGlass = isGlass
        )
    } else {
        buildColorScheme(accent, isDark, isGlass)
    }

    // Apply the persisted font-size setting by scaling the base typography.
    val typography = scaleTypography(AppTypography, fontScaleFor(fontSize))

    // Edge-to-edge: system will handle status bar/navigation bar appearance
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    if (activity != null) {
        SideEffect {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller?.isAppearanceLightStatusBars = !isDark
            controller?.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalIsGlass provides isGlass,
        LocalGlassBlurRadius provides GlassBlurMd,
        LocalGlassTintAlpha provides 0.20f,
        LocalGlassBorderAlpha provides 0.15f,
        LocalGlassShineAlpha provides if (isDark) 0.12f else 0.10f,
        LocalGlassDispersion provides if (isDark) GlassDispersionDark else GlassDispersionLight,
        LocalGlassShadowElevation provides GlassShadowMd,
        content = {
            if (isGlass) {
                Box(modifier = Modifier.fillMaxSize()) {
                    GlassBackdrop(isDark = isDark)
                    MaterialTheme(
                        colorScheme = colorScheme,
                        typography = typography,
                        content = content
                    )
                }
            } else {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    content = content
                )
            }
        }
    )
}
