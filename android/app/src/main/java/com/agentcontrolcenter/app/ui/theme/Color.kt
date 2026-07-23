package com.agentcontrolcenter.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 强调色系统 ──

data class AccentPalette(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color
)

val AccentBlue = AccentPalette(
    primary = Color(0xFF185FA5),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF535F70),
    secondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFF6B5778),
    tertiaryContainer = Color(0xFFF2DAFF)
)

// ── Light / Dark 扩展色 ──

val LightBackground = Color(0xFFFDFBFF)
val LightSurface = Color(0xFFFDFBFF)
val LightSurfaceVariant = Color(0xFFF1F0F4)
val LightOnBackground = Color(0xFF1B1B1F)
val LightOutline = Color(0xFFC4C6D0)
val LightOutlineVariant = Color(0xFFE0E0E6)
val LightSurfaceTint = Color(0xFF6750A4)

val DarkBackground = Color(0xFF1B1B1F)
val DarkSurface = Color(0xFF1B1B1F)
val DarkSurfaceVariant = Color(0xFF2B2930)
val DarkOnBackground = Color(0xFFE4E1E6)
val DarkOutline = Color(0xFF8E9099)
val DarkOutlineVariant = Color(0xFF44474E)
val DarkSurfaceTint = Color(0xFFD0BCFF)

// ── v5.0: 语义色 token，替代散落各处的硬编码 Color。
// 这些颜色会随 Light/Dark 主题切换（见 Theme.kt 中的 CompositionLocal）。 ──

val SuccessLight = Color(0xFF10B981)
val SuccessDark = Color(0xFF34D399)
val WarningLight = Color(0xFFF59E0B)
val WarningDark = Color(0xFFFBBF24)
val InfoLight = Color(0xFF3B82F6)
val InfoDark = Color(0xFF60A5FA)
val DangerLight = Color(0xFFFF6B35)
val DangerDark = Color(0xFFFF8C5A)

// ── 形状令牌（Material 3 Expressive，详见 Shape.kt） ──
// 保留别名以兼容旧引用，实际定义在 Shape.kt
// 新代码请直接使用 Shape.kt 中的 token
