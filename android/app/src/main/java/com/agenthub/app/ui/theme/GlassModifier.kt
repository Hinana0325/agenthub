package com.agenthub.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether the current composable tree is in glass mode */
val LocalIsGlass = staticCompositionLocalOf { false }

/** Default glass blur radius */
val LocalGlassBlurRadius = staticCompositionLocalOf { 16.dp }

/** Default glass tint opacity */
val LocalGlassTintAlpha = staticCompositionLocalOf { 0.20f }

/** Default glass border opacity */
val LocalGlassBorderAlpha = staticCompositionLocalOf { 0.15f }

/**
 * Apply Apple-style frosted glass background to a composable.
 *
 * Uses [Modifier.blur] for the blur effect (works on all API levels).
 * Draws a semi-transparent tint and thin border on top.
 */
@Composable
fun Modifier.glassBackground(
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
): Modifier {
    val isGlass = LocalIsGlass.current
    if (!isGlass) return this

    val blurRadius = LocalGlassBlurRadius.current
    val tintAlpha = LocalGlassTintAlpha.current
    val borderAlpha = LocalGlassBorderAlpha.current

    return this
        .drawBehind {
            drawRect(color = tintColor.copy(alpha = tintAlpha), size = size)
            if (borderAlpha > 0f) {
                val half = 0.5.dp.toPx()
                drawRect(color = borderColor.copy(alpha = borderAlpha), topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, half))
                drawRect(color = borderColor.copy(alpha = borderAlpha), topLeft = androidx.compose.ui.geometry.Offset.Zero, size = androidx.compose.ui.geometry.Size(half, size.height))
                drawRect(color = borderColor.copy(alpha = borderAlpha), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - half), size = androidx.compose.ui.geometry.Size(size.width, half))
                drawRect(color = borderColor.copy(alpha = borderAlpha), topLeft = androidx.compose.ui.geometry.Offset(size.width - half, 0f), size = androidx.compose.ui.geometry.Size(half, size.height))
            }
        }
        .blur(blurRadius)
}

/**
 * Apple-style frosted glass container box.
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
    content: @Composable BoxScope.() -> Unit,
) {
    val isGlass = LocalIsGlass.current
    if (!isGlass) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(modifier = modifier.glassBackground(tintColor, borderColor), content = content)
}

/**
 * Glass-styled [TopAppBar].
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
            if (isGlass) Modifier.glassBackground() else Modifier
        )
    )
}

/**
 * Glass-styled [NavigationBar].
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
        content = content,
        modifier = modifier.glassBackground(),
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
        modifier = modifier.glassBackground(),
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
        elevation = elevation,
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
        elevation = elevation,
        border = border,
        content = content,
    )
}
