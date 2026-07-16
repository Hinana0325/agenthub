package com.agenthub.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/** Android 17 spring physics — bouncy, natural touch feedback */
val SpringBounce = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)

/** Smooth spring — no overshoot, fluid motion */
val SpringSmooth = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

/** Exit spring — quick settle */
val SpringExit = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh
)

/** Elastic enter transition — fade + spring scale (Android 17 entrance).
 *  Computed once (not a `get()`) so every AnimatedVisibility that references it
 *  reuses the same transition instance instead of rebuilding fadeIn + scaleIn. */
val GlassEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(220, easing = LinearEasing)) +
            scaleIn(initialScale = 0.92f, animationSpec = SpringBounce)

/** Elastic exit transition */
val GlassExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(160, easing = LinearEasing)) +
            scaleOut(targetScale = 0.96f, animationSpec = SpringExit)

/**
 * Continuous subtle float offset (px) — gives glass surfaces "liquid" life.
 * Use with Modifier.graphicsLayer { translationY = it }.
 */
@Composable
fun rememberFloatingOffset(distance: Dp = 4.dp): State<Float> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val distPx = with(density) { distance.toPx() }
    val infinite = rememberInfiniteTransition(label = "float")
    return infinite.animateFloat(
        initialValue = 0f,
        targetValue = distPx,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float-offset"
    )
}

/**
 * Shape morphing — animate corner radius between two values with spring physics.
 * Returns a [Shape] that smoothly transitions (squircle ↔ pill ↔ rounded).
 */
@Composable
fun rememberMorphCornerShape(
    targetRadius: Dp,
    fromRadius: Dp = 16.dp,
): Shape {
    val radius by animateDpAsState(
        targetValue = targetRadius,
        animationSpec = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "corner-morph"
    )
    return androidx.compose.foundation.shape.RoundedCornerShape(radius)
}

/**
 * Glass press feedback — elastic scale-down on press, spring back on release.
 * Replaces plain alpha/scale for tactile Android 17 feel.
 */
fun Modifier.glassPress(
    interactionSource: MutableInteractionSource? = null,
    pressedScale: Float = 0.92f,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) SpringBounce else SpringSmooth,
        label = "glass-press-scale"
    )
    this then Modifier.scale(scale)
}

/**
 * Glass ripple + spring — clickable with Android 17 tactile feedback.
 */
fun Modifier.glassClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this
        .glassPress(interactionSource)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

/** Lerp helper for Dp (avoids extra import noise at call sites) */
fun lerpDp(from: Dp, to: Dp, fraction: Float): Dp = lerp(from, to, fraction)
