package com.agenthub.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── 8 种强调色系统 ──

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

val AccentTeal = AccentPalette(
    primary = Color(0xFF0F6E56),
    primaryContainer = Color(0xFFA7F5DB),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4B635A),
    secondaryContainer = Color(0xFFCEE9DD),
    tertiary = Color(0xFF3C6472),
    tertiaryContainer = Color(0xFFC0E8F9)
)

val AccentPurple = AccentPalette(
    primary = Color(0xFF534AB7),
    primaryContainer = Color(0xFFE0DEFF),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF12005E),
    secondary = Color(0xFF5D5A72),
    secondaryContainer = Color(0xFFE3DFF8),
    tertiary = Color(0xFF7A5267),
    tertiaryContainer = Color(0xFFFFD9E9)
)

val AccentCoral = AccentPalette(
    primary = Color(0xFF993C1D),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF360E00),
    secondary = Color(0xFF77574B),
    secondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFF616035),
    tertiaryContainer = Color(0xFFE8E5AE)
)

val AccentAmber = AccentPalette(
    primary = Color(0xFF854F0B),
    primaryContainer = Color(0xFFFFDDB5),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF2B1600),
    secondary = Color(0xFF6F5B40),
    secondaryContainer = Color(0xFFFADEBC),
    tertiary = Color(0xFF4C6447),
    tertiaryContainer = Color(0xFFCEEAC5)
)

val AccentGreen = AccentPalette(
    primary = Color(0xFF3B6D11),
    primaryContainer = Color(0xFFB8F58C),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF082100),
    secondary = Color(0xFF56624B),
    secondaryContainer = Color(0xFFD9E7CA),
    tertiary = Color(0xFF386663),
    tertiaryContainer = Color(0xFFBBF1EA)
)

val AccentPink = AccentPalette(
    primary = Color(0xFF993556),
    primaryContainer = Color(0xFFFFD9E3),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF3F0018),
    secondary = Color(0xFF73575F),
    secondaryContainer = Color(0xFFFEDAE4),
    tertiary = Color(0xFF7B5366),
    tertiaryContainer = Color(0xFFFFD9E9)
)

val AccentGray = AccentPalette(
    primary = Color(0xFF5F5E5A),
    primaryContainer = Color(0xFFE8E2D9),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF1C1B18),
    secondary = Color(0xFF5F5E5A),
    secondaryContainer = Color(0xFFE8E2D9),
    tertiary = Color(0xFF5F5E5A),
    tertiaryContainer = Color(0xFFE8E2D9)
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

// ── Glass 模式特有 ──

val GlassSurfaceLight = Color(0xE6FFFFFF)     // 90% white
val GlassSurfaceDark = Color(0xCC1C1B1F)      // 80% dark
val GlassSurfaceVariantLight = Color(0xB3F5F0F0) // 70% faint warm
val GlassSurfaceVariantDark = Color(0x99333338)  // 60% dark
val GlassBorderLight = Color(0x33FFFFFF)       // 20% white
val GlassBorderDark = Color(0x33FFFFFF)        // 20% white
val GlassHighlightLight = Color(0x4DFFFFFF)    // 30% white
val GlassHighlightDark = Color(0x1AFFFFFF)     // 10% white

// ── Window backdrop (shows through glass surfaces) ──
val GlassBackdropLight = Color(0xFFF0EDF0)     // light neutral
val GlassBackdropDark = Color(0xFF1A1A1E)      // deep dark

val GlassBackdropGradientTopLight = Color(0xFFE8E0F0)    // subtle lavender
val GlassBackdropGradientBottomLight = Color(0xFFF5F0EC) // warm cream
val GlassBackdropGradientTopDark = Color(0xFF1E1A24)     // deep purple
val GlassBackdropGradientBottomDark = Color(0xFF1C1C18)  // warm dark

// ── Android 17 玻璃模糊级别（对应系统级 5 级模糊） ──
val GlassBlurXs = 8.dp
val GlassBlurSm = 16.dp
val GlassBlurMd = 24.dp
val GlassBlurLg = 40.dp
val GlassBlurXl = 60.dp

// ── 深度阴影令牌（ambient + diffuse 多层） ──
val GlassShadowSm = 4.dp
val GlassShadowMd = 8.dp
val GlassShadowLg = 16.dp
val GlassShadowXl = 32.dp

// ── 玻璃动态光泽色（Android 17 灵魂：高光流动） ──
val GlassShineLight = Color(0x66FFFFFF)  // 40% white
val GlassShineDark = Color(0x22FFFFFF)   // 13% white

// ── 色散强度（chromatic aberration，边缘 RGB 分离） ──
val GlassDispersionLight = 1.5f
val GlassDispersionDark = 2.0f

// ── 形状令牌（Material 3 Expressive 子集） ──
val GlassShapeXs = RoundedCornerShape(8.dp)
val GlassShapeSm = RoundedCornerShape(12.dp)
val GlassShapeMd = RoundedCornerShape(16.dp)
val GlassShapeLg = RoundedCornerShape(24.dp)
val GlassShapeXl = RoundedCornerShape(32.dp)
val GlassShapePill = RoundedCornerShape(100.dp)
/** 超椭圆近似（squircle）：圆角随尺寸变化，视觉更柔和 */
val GlassShapeSquircle = RoundedCornerShape(28.dp)

// ── 强调色对应的玻璃光泽色（用于气泡/卡片 tint） ──
val GlassTintPrimaryLight = Color(0x33FFFFFF)
val GlassTintPrimaryDark = Color(0x26FFFFFF)
