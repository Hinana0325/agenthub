package com.agentcontrolcenter.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveDarkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Agent Control Center 主题模式：Light / Dark / System（跟随系统）。
 *
 * 基于 Android 16 Material 3 Expressive 设计语言。
 * 使用 [MaterialExpressiveTheme] + [expressiveLightColorScheme] / [expressiveDarkColorScheme]
 * 提供 surfaceBright / surfaceDim 等扩展 token，通过实体 surface 表达层次。
 */
enum class ThemeMode { Light, Dark, System }

/**
 * 根据强调色和主题模式构建 Expressive ColorScheme。
 *
 * 使用 [expressiveLightColorScheme] / [expressiveDarkColorScheme] 替代标准
 * [lightColorScheme] / [darkColorScheme]，以获得 M3 Expressive 扩展的
 * surfaceBright / surfaceDim 等 token。
 */
fun buildColorScheme(
    accent: AccentPalette,
    isDark: Boolean
) = if (isDark) {
    expressiveDarkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        secondaryContainer = accent.secondaryContainer,
        tertiary = accent.tertiary,
        tertiaryContainer = accent.tertiaryContainer
    )
} else {
    expressiveLightColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        secondaryContainer = accent.secondaryContainer,
        tertiary = accent.tertiary,
        tertiaryContainer = accent.tertiaryContainer
    )
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

/**
 * Agent Control Center 主题入口。
 *
 * 基于 Android 16 Material 3 Expressive：
 * - [MaterialExpressiveTheme] 替代标准 [MaterialTheme]
 * - [expressiveLightColorScheme] / [expressiveDarkColorScheme] 提供 surfaceBright / surfaceDim
 * - Android 12+ 动态取色（Material You）
 * - 实体 surface 表达层次，不使用透明/玻璃效果
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
    // Android 12+ 动态取色优先；否则使用应用自带 AccentBlue + Expressive 基础色板
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

    // Material 3 Expressive Theme
    // 使用 MaterialExpressiveTheme 替代 MaterialTheme，获得：
    // - 35 种形状 token
    // - Spring Motion 默认动效
    // - Expressive 组件变体
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = AppShapes,
        content = content
    )
}
