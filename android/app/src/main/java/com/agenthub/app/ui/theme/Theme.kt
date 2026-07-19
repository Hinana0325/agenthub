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
 * AgentHub 主题模式：Light / Dark / System（跟随系统）。
 * 视觉风格始终为液态玻璃（liquid glass 常驻），模式只决定深色/浅色基底。
 */
enum class ThemeMode { Light, Dark, System }

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
        surfaceTint = DarkSurfaceTint,
        // Glass 模式下 surfaceContainer* 必须同步设为半透明玻璃色，否则 AlertDialog /
        // ModalBottomSheet 等使用 surfaceContainerHigh 的组件会显示为深色实色背景（弹窗黑框）。
        surfaceContainerLowest = if (isGlass) GlassSurfaceDark.copy(alpha = 0.90f) else Color(0xFF0C0B0F),
        surfaceContainerLow = if (isGlass) GlassSurfaceDark.copy(alpha = 0.85f) else Color(0xFF1D1B20),
        surfaceContainer = if (isGlass) GlassSurfaceDark.copy(alpha = 0.80f) else Color(0xFF211F26),
        surfaceContainerHigh = if (isGlass) GlassSurfaceDark.copy(alpha = 0.75f) else Color(0xFF2B2930),
        surfaceContainerHighest = if (isGlass) GlassSurfaceDark.copy(alpha = 0.70f) else Color(0xFF36343B)
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
        surfaceTint = LightSurfaceTint,
        // Glass 模式下 surfaceContainer* 同步设为半透明玻璃色，保持与 surface 一致的透明语义。
        surfaceContainerLowest = if (isGlass) GlassSurfaceLight.copy(alpha = 0.95f) else Color(0xFFFFFFFF),
        surfaceContainerLow = if (isGlass) GlassSurfaceLight.copy(alpha = 0.90f) else Color(0xFFF7F2FA),
        surfaceContainer = if (isGlass) GlassSurfaceLight.copy(alpha = 0.85f) else Color(0xFFF3EDF7),
        surfaceContainerHigh = if (isGlass) GlassSurfaceLight.copy(alpha = 0.80f) else Color(0xFFECE6F0),
        surfaceContainerHighest = if (isGlass) GlassSurfaceLight.copy(alpha = 0.75f) else Color(0xFFE6E1E9)
    )
}

// NOTE: The previous multi-accent picker (AccentPalettes) and the custom-theme hex
// color builder (buildCustomColorScheme / parseHexColor) were removed. The app now
// uses a single default accent (AccentBlue) and the liquid-glass style is always on,
// so per-user color customization is no longer needed.


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
    fontSize: String = "medium",
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    // Liquid glass is always on; the theme mode only selects the dark/light base.
    val isGlass = true
    val accent = AccentBlue
    val colorScheme = buildColorScheme(accent, isDark, isGlass)

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
            Box(modifier = Modifier.fillMaxSize()) {
                GlassBackdrop(isDark = isDark)
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    content = content
                )
            }
        }
    )
}
