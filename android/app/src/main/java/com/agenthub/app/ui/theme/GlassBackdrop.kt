package com.agenthub.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Android 17-style dynamic glass backdrop.
 *
 * Multi-layer radial gradients (ambient glows) that slowly drift, giving the
 * wallpaper-like depth that glass surfaces bleed through. Place this as the
 * root background behind all [glassBackground] surfaces.
 *
 * Pair with [AgentHubTheme] in [ThemeMode.LiquidGlass].
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier.fillMaxSize(),
    isDark: Boolean = isSystemInDarkTheme(),
) {
    val top = if (isDark) GlassBackdropGradientTopDark else GlassBackdropGradientTopLight
    val bottom = if (isDark) GlassBackdropGradientBottomDark else GlassBackdropGradientBottomLight
    val glowA = if (isDark) Color(0x4D6366F1) else Color(0x338B5CF6)   // indigo
    val glowB = if (isDark) Color(0x3322D3EE) else Color(0x2006B6D4)   // cyan
    val glowC = if (isDark) Color(0x26C084FC) else Color(0x1FEC4899)   // purple/pink

    val infinite = rememberInfiniteTransition(label = "backdrop-drift")
    val ax by infinite.animateFloat(0.15f, 0.85f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "ax")
    val ay by infinite.animateFloat(0.1f, 0.7f, infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Reverse), label = "ay")
    val bx by infinite.animateFloat(0.8f, 0.2f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), label = "bx")
    val by by infinite.animateFloat(0.85f, 0.25f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse), label = "by")
    val cx by infinite.animateFloat(0.5f, 0.6f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse), label = "cx")
    val cy by infinite.animateFloat(0.4f, 0.55f, infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Reverse), label = "cy")

    Box(
        modifier = modifier.drawBehind {
            // Base vertical gradient
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(top, bottom),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.3f, size.height)
                )
            )
            // Ambient glow A (indigo, top-left)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowA, Color.Transparent),
                    center = Offset(size.width * ax, size.height * ay),
                    radius = size.maxDimension * 0.55f
                ),
                radius = size.maxDimension * 0.55f,
                center = Offset(size.width * ax, size.height * ay)
            )
            // Ambient glow B (cyan, bottom-right)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowB, Color.Transparent),
                    center = Offset(size.width * bx, size.height * by),
                    radius = size.maxDimension * 0.5f
                ),
                radius = size.maxDimension * 0.5f,
                center = Offset(size.width * bx, size.height * by)
            )
            // Ambient glow C (purple, center)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowC, Color.Transparent),
                    center = Offset(size.width * cx, size.height * cy),
                    radius = size.maxDimension * 0.45f
                ),
                radius = size.maxDimension * 0.45f,
                center = Offset(size.width * cx, size.height * cy)
            )
        }
    )
}
