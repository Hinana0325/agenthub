package com.agentcontrolcenter.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.SheetState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Whether the current composable tree is in glass mode */
val LocalIsGlass = staticCompositionLocalOf { false }

/** Default glass blur radius */
val LocalGlassBlurRadius = staticCompositionLocalOf { 16.dp }

/** Default glass tint opacity */
val LocalGlassTintAlpha = staticCompositionLocalOf { 0.20f }

/** Default glass border opacity */
val LocalGlassBorderAlpha = staticCompositionLocalOf { 0.15f }

/** Dynamic shine (highlight) opacity — Android 17 glass soul */
val LocalGlassShineAlpha = staticCompositionLocalOf { 0.10f }

/** Dispersion (chromatic aberration) strength — simulated via colored edge */
val LocalGlassDispersion = staticCompositionLocalOf { 1.5f }

/** Depth shadow elevation (dp) */
val LocalGlassShadowElevation = staticCompositionLocalOf { 8.dp }

/**
 * Apply Android 17-style liquid glass background to a composable.
 *
 * Layers (back → front):
 *  1. Backdrop blur (API 31+ via [BlurEffect]; <31 degrades to [Modifier.blur])
 *  2. Tint (wallpaper bleed-through)
 *  3. Edge-light refraction (4-sided gradient border)
 *  4. Chromatic dispersion (subtle red/cyan offset edges — Android 17 soul)
 *  5. Dynamic shine (slow drifting highlight)
 *  6. Depth shadow (ambient + diffuse)
 */
@Composable
fun Modifier.glassBackground(
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    // Dynamic shine drift is only worth the (per-surface) infinite animation on larger
    // surfaces. Tiny surfaces — chat bubbles, pills — set this false: the drift is
    // imperceptible there, yet each instance would otherwise spin up its own forever
    // animation loop (dozens of them in a chat list = needless CPU/battery).
    animateShine: Boolean = true,
): Modifier {
    val isGlass = LocalIsGlass.current
    if (!isGlass) return this

    val tintAlpha = LocalGlassTintAlpha.current
    val borderAlpha = LocalGlassBorderAlpha.current
    val shineAlpha = LocalGlassShineAlpha.current
    val dispersion = LocalGlassDispersion.current
    val shadowElevation = LocalGlassShadowElevation.current

    val density = LocalDensity.current
    val borderPx = with(density) { 0.5.dp.toPx() }
    val dispPx = with(density) { dispersion.dp.toPx() }

    // --- Values that are constant across draw frames: hoist out of drawBehind so we
    //     don't re-allocate Color copies / color lists on every animation frame. ---
    val tintColorTinted = remember(tintColor, tintAlpha) { tintColor.copy(alpha = tintAlpha) }
    val tintColorShine = remember(tintColor, shineAlpha) { tintColor.copy(alpha = shineAlpha) }
    val edgeColor = remember(borderColor, borderAlpha) { borderColor.copy(alpha = borderAlpha) }
    val dispRed = remember { Color.Red.copy(alpha = 0.04f) }
    val dispCyan = remember { Color.Cyan.copy(alpha = 0.04f) }
    val shineColors = remember(tintColorShine) {
        listOf(Color.Transparent, tintColorShine, Color.Transparent)
    }

    // Shine phase — only run the infinite animation when requested.
    val shinePhase = if (animateShine) {
        val infinite = rememberInfiniteTransition(label = "glass-shine")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shine-phase"
        ).value
    } else {
        0.5f
    }

    var result: Modifier = this

    // NOTE: Backdrop blur is intentionally NOT applied on the content path.
    // RenderEffect / Modifier.blur blur the composable's OWN subtree (incl. text),
    // which would smear content. The frosted look here comes from translucency over
    // [GlassBackdrop]; [GlassBox] may layer an optional blurred backdrop behind content.

    // 3. Depth shadow (ambient + diffuse) — drawn FIRST so the rounded drop shadow
    //    is NOT clipped away by the glass outline below.
    result = result.shadow(
        elevation = shadowElevation,
        shape = shape,
        clip = false,
        ambientColor = tintColor.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.32f)
    )

    // Clip every layer after this (tint + content) to the glass [shape], so rounded
    // surfaces don't bleed square tint corners past their rounded outline.
    result = result.clip(shape)

    // 2. Tint + edge light + dispersion + dynamic shine (clipped to [shape] above)
    result = result.drawBehind {
        // Base tint (wallpaper bleed-through)
        drawRect(color = tintColorTinted, size = size)

        // Chromatic dispersion — subtle red/cyan offset edges (Android 17 soul)
        if (dispersion > 0f) {
            val disp = dispPx
            drawRect(
                color = dispRed,
                topLeft = androidx.compose.ui.geometry.Offset(disp, 0f),
                size = androidx.compose.ui.geometry.Size(borderPx, size.height)
            )
            drawRect(
                color = dispCyan,
                topLeft = androidx.compose.ui.geometry.Offset(-disp, 0f),
                size = androidx.compose.ui.geometry.Size(borderPx, size.height)
            )
        }

        // Dynamic diagonal shine — drifts with phase
        val shineStart = 0.15f + shinePhase * 0.4f
        val shineBrush = Brush.linearGradient(
            colors = shineColors,
            start = androidx.compose.ui.geometry.Offset(size.width * shineStart, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width * (shineStart + 0.35f), size.height)
        )
        drawRect(brush = shineBrush, size = size)

        // Edge-light refraction (4 borders, brighter top)
        if (borderAlpha > 0f) {
            drawRect(color = edgeColor, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, borderPx))
            drawRect(color = edgeColor, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(borderPx, size.height))
            drawRect(color = edgeColor, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - borderPx), size = androidx.compose.ui.geometry.Size(size.width, borderPx))
            drawRect(color = edgeColor, topLeft = androidx.compose.ui.geometry.Offset(size.width - borderPx, 0f), size = androidx.compose.ui.geometry.Size(borderPx, size.height))
        }
    }

    return result
}

/**
 * Apple-style frosted glass container box with Android 17 enhancements.
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    if (!isGlass) {
        Box(modifier = modifier, content = content)
        return
    }
    val blurRadius = LocalGlassBlurRadius.current
    val tintAlpha = LocalGlassTintAlpha.current
    val density = LocalDensity.current
    val blurPx = with(density) { blurRadius.toPx() }
    Box(modifier = modifier) {
        // Optional frosted backdrop layer behind content. Blurs this layer's own
        // (translucent) fill; the frosted depth reads as translucency over GlassBackdrop.
        // Kept for API symmetry / future View-backed backdrop snapshots.
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (Build.VERSION.SDK_INT >= 31) {
                        Modifier.graphicsLayer {
                            renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                        }
                    } else {
                        Modifier.blur(blurRadius)
                    }
                )
                .background(tintColor.copy(alpha = tintAlpha), shape)
        )
        Box(
            modifier = Modifier.glassBackground(tintColor, borderColor, shape),
            content = content
        )
    }
}

/**
 * Glass-styled [TopAppBar] with depth shadow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isGlass = LocalIsGlass.current
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.then(
            if (isGlass) Modifier.glassBackground(shape = RoundedCornerShape(0.dp)) else Modifier
        )
    )
}

/**
 * Glass-styled [NavigationBar] with active-pill spring indicator handled by caller.
 *
 * **Insets 契约**：Glass 模式下传入 `windowInsets = WindowInsets(0, 0, 0, 0)`，
 * 让底部系统栏 insets 由外层 Scaffold 的 `contentWindowInsets` 统一管理，
 * 避免 NavigationBar 默认 insets 与 Scaffold insets 双重叠加导致底部栏错位。
 * 同时显式 `tonalElevation = 0.dp`，避免在透明 containerColor 上叠加意外 tint。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    if (!isGlass) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
            modifier = modifier,
        )
        return
    }
    NavigationBar(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
        modifier = modifier.glassBackground(shape = RoundedCornerShape(0.dp)),
    )
}

/**
 * Glass-styled [NavigationRail].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassNavigationRail(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    if (!isGlass) {
        NavigationRail(
            header = header,
            containerColor = MaterialTheme.colorScheme.surface,
            content = content,
            modifier = modifier,
        )
        return
    }
    NavigationRail(
        header = header,
        containerColor = Color.Transparent,
        content = content,
        modifier = modifier.glassBackground(shape = RoundedCornerShape(0.dp)),
    )
}

/**
 * Glass-styled [Card] (clickable).
 */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    elevation: androidx.compose.material3.CardElevation = CardDefaults.cardElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = if (isGlass) CardDefaults.cardColors(containerColor = Color.Transparent) else colors,
        // Glass mode draws its own depth shadow via Modifier.glassBackground(); zero out
        // Card's built-in elevation so we don't get a doubled/heavy shadow.
        elevation = if (isGlass) CardDefaults.cardElevation(0.dp) else elevation,
        border = border,
        interactionSource = interactionSource,
        modifier = modifier.then(
            if (isGlass) Modifier.glassBackground() else Modifier
        ),
        content = content,
    )
}

/**
 * Glass-styled [Card] (non-clickable).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    elevation: androidx.compose.material3.CardElevation = CardDefaults.cardElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    Card(
        modifier = modifier.then(
            if (isGlass) Modifier.glassBackground() else Modifier
        ),
        shape = shape,
        colors = if (isGlass) CardDefaults.cardColors(containerColor = Color.Transparent) else colors,
        // Glass mode draws its own depth shadow via Modifier.glassBackground(); zero out
        // Card's built-in elevation so we don't get a doubled/heavy shadow.
        elevation = if (isGlass) CardDefaults.cardElevation(0.dp) else elevation,
        border = border,
        content = content,
    )
}

/**
 * Android 17 liquid floating action button.
 * Continuous subtle float + depth glow + spring press handled by caller via [scaleOnPress].
 */
@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    val isGlass = LocalIsGlass.current
    val density = LocalDensity.current
    val infinite = rememberInfiniteTransition(label = "fab-float")
    val floatY by infinite.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab-float-y"
    )
    val floatYpx = with(density) { floatY.dp.toPx() }

    Box(
        modifier = modifier
            .graphicsLayer { translationY = floatYpx }
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = containerColor.copy(alpha = 0.3f),
                spotColor = containerColor.copy(alpha = 0.4f)
            )
            .then(if (isGlass) Modifier.glassBackground(tintColor = containerColor, shape = shape) else Modifier)
            .background(if (isGlass) Color.Transparent else containerColor, shape)
    ) {
        // Inner clip keeps the ripple + content inside the shape WITHOUT clipping
        // away the outer drop shadow drawn by the shadow() modifier above.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .clickable(onClick = onClick),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * Floating pill control (Android 17 style) — for recording / status / quick actions.
 */
@Composable
fun GlassPill(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tintColor: Color = Color.White,
    content: @Composable RowScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .then(if (isGlass) Modifier.glassBackground(tintColor, shape = RoundedCornerShape(100.dp), animateShine = false) else Modifier)
            .background(
                if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(100.dp)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) { content() }
    }
}

/**
 * Glass-styled [DropdownMenu] — Android 17 translucent popup with edge-light and
 * dynamic shine. Falls back to the default Material3 surface container outside glass mode.
 *
 * Wrap the [content] with [GlassDropdownMenuItem] (see below) to keep individual rows
 * consistent, or supply any [ColumnScope] content.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(
            if (isGlass) {
                Modifier.glassBackground(
                    tintColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            } else {
                Modifier
            }
        ),
        offset = offset,
        shape = RoundedCornerShape(16.dp),
        // Glass mode already draws a single depth shadow via Modifier.glassBackground();
        // zero the popup's own surface shadow so it doesn't double up.
        tonalElevation = if (isGlass) 0.dp else 2.dp,
        shadowElevation = if (isGlass) 0.dp else 2.dp,
        containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        content = content
    )
}

/**
 * A single row inside a [GlassDropdownMenu], with Android 17 spring press feedback.
 * The same [MutableInteractionSource] feeds both [DropdownMenuItem]'s clickable and
 * [Modifier.glassPress], so the elastic scale reacts to real pointer presses.
 */
@Composable
fun GlassDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isGlass = LocalIsGlass.current
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.then(
            if (isGlass) Modifier.glassPress(interactionSource) else Modifier
        ),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        interactionSource = interactionSource
    )
}

/**
 * Glass-styled [ModalBottomSheet] — Android 17 liquid bottom sheet with frosted
 * translucency, edge-light, and a tinted drag handle. Falls back to the default
 * Material3 surface container outside glass mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: @Composable (() -> Unit)? = {
        androidx.compose.material3.BottomSheetDefaults.DragHandle(
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    },
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(
            if (isGlass) {
                Modifier.glassBackground(
                    tintColor = Color.White,
                    shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp)
                )
            } else {
                Modifier
            }
        ),
        sheetState = sheetState,
        shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp),
        containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = dragHandle,
        content = content
    )
}
