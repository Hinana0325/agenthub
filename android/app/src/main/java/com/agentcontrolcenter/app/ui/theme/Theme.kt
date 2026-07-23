package com.agentcontrolcenter.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Agent Control Center 主题模式：Light / Dark / System（跟随系统）。
 *
 * 基于 Android 16 Material 3 Expressive 设计语言。
 * 使用扩展的 ColorScheme（surfaceBright / surfaceDim）表达层次，
 * 实体 surface 替代透明/玻璃效果。
 */
enum class ThemeMode { Light, Dark, System }

// ── v5.0: 语义色 CompositionLocal ──
// M3 colorScheme 没有内置 success / warning / info / danger slot，
// 这里通过 CompositionLocal 在 AppTheme 中根据 isDark 提供 light/dark 变体，
// 避免在每个使用点写 if (isSystemInDarkTheme()) 分支。
val LocalSuccessColor = staticCompositionLocalOf { Color(0xFF10B981) }
val LocalWarningColor = staticCompositionLocalOf { Color(0xFFF59E0B) }
val LocalInfoColor = staticCompositionLocalOf { Color(0xFF3B82F6) }
val LocalDangerColor = staticCompositionLocalOf { Color(0xFFFF6B35) }

/**
 * 构建浅色 ColorScheme，包含 M3 Expressive 扩展 token。
 *
 * surfaceBright 用于需要"浮起"感的容器（如卡片、TopAppBar）
 * surfaceDim 用于需要"凹陷"感的区域（如背景）
 */
fun buildLightColorScheme(
    accent: AccentPalette
) = lightColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary,
    secondaryContainer = accent.secondaryContainer,
    tertiary = accent.tertiary,
    tertiaryContainer = accent.tertiaryContainer,
    // M3 Expressive 扩展：用 surfaceContainer 系列表达层次
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFD1D5DB)
)

/**
 * 构建深色 ColorScheme，包含 M3 Expressive 扩展 token。
 */
fun buildDarkColorScheme(
    accent: AccentPalette
) = darkColorScheme(
    primary = accent.primary,
    onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer,
    onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary,
    secondaryContainer = accent.secondaryContainer,
    tertiary = accent.tertiary,
    tertiaryContainer = accent.tertiaryContainer,
    // M3 Expressive 扩展：用 surfaceContainer 系列表达层次
    surfaceContainerLowest = Color(0xFF0D1117),
    surfaceContainerLow = Color(0xFF161B22),
    surfaceContainer = Color(0xFF21262D),
    surfaceContainerHigh = Color(0xFF30363D),
    surfaceContainerHighest = Color(0xFF3D444D)
)

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

/**
 * 根据强调色和主题模式构建 ColorScheme。
 *
 * 使用扩展的 surfaceContainer 系列表达层次，
 * 替代旧版透明 surface + Glass 容器方案。
 */
fun buildColorScheme(
    accent: AccentPalette,
    isDark: Boolean
) = if (isDark) {
    buildDarkColorScheme(accent)
} else {
    buildLightColorScheme(accent)
}

/**
 * Agent Control Center 主题入口。
 *
 * 基于 Android 16 Material 3 Expressive 设计语言：
 * - 扩展 surfaceContainer 系列表达层次（surfaceBright / surfaceDim 语义）
 * - Android 12+ 动态取色（Material You）
 * - 实体 surface 替代透明/玻璃效果
 * - Spring Motion 动效（通过 NavHost 转场和组件动画实现）
 *
 * @param themeMode Light / Dark / System
 * @param fontSize small / medium / large
 * @param dynamicColor Android 12+ 是否启用 Material You 动态取色
 * @param content 主题包裹的内容
 */
@Composable
fun AgentControlCenterTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontSize: String = "medium",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val accent = AccentBlue
    val context = LocalContext.current

    // Material 3 Expressive ColorScheme
    // Android 12+ 动态取色优先；否则使用应用自带 AccentBlue + 扩展 surfaceContainer
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        buildColorScheme(accent, isDark)
    }

    // Apply the persisted font-size setting by scaling the base typography.
    val typography = scaleTypography(AppTypography, fontScaleFor(fontSize))

    // Edge-to-edge: system bar appearance follows theme mode
    val activity = LocalContext.current as? Activity
    if (activity != null) {
        SideEffect {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller?.isAppearanceLightStatusBars = !isDark
            controller?.isAppearanceLightNavigationBars = !isDark
        }
    }

    // v5.0: 语义色根据 isDark 切换 light/dark 变体
    val successColor = if (isDark) SuccessDark else SuccessLight
    val warningColor = if (isDark) WarningDark else WarningLight
    val infoColor = if (isDark) InfoDark else InfoLight
    val dangerColor = if (isDark) DangerDark else DangerLight

    // Material 3 Theme with Expressive extensions
    // 使用扩展 surfaceContainer 系列替代透明 surface，
    // 形状系统通过 AppShapes 配置，Spring Motion 通过 NavHost 转场实现
    CompositionLocalProvider(
        LocalSuccessColor provides successColor,
        LocalWarningColor provides warningColor,
        LocalInfoColor provides infoColor,
        LocalDangerColor provides dangerColor
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}
