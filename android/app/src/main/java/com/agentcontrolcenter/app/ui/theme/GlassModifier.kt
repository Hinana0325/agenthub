package com.agentcontrolcenter.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Whether the current composable tree is in glass mode.
 *
 * Liquid Glass system has been removed — this is always `false` and kept only for
 * backward compatibility with call sites that still read it.
 */
val LocalIsGlass = staticCompositionLocalOf { false }

// ---------------------------------------------------------------------------
// Legacy glass CompositionLocals — no longer consumed by any rendering path.
// Kept so that existing `LocalXxx.current` reads across the codebase compile.
// ---------------------------------------------------------------------------

/** Default glass blur radius (unused) */
val LocalGlassBlurRadius = staticCompositionLocalOf { 16.dp }

/** Default glass tint opacity (unused) */
val LocalGlassTintAlpha = staticCompositionLocalOf { 0.20f }

/** Default glass border opacity (unused) */
val LocalGlassBorderAlpha = staticCompositionLocalOf { 0.15f }

/** Dynamic shine (highlight) opacity (unused) */
val LocalGlassShineAlpha = staticCompositionLocalOf { 0.10f }

/** Dispersion (chromatic aberration) strength (unused) */
val LocalGlassDispersion = staticCompositionLocalOf { 1.5f }

/** Depth shadow elevation (dp) (unused) */
val LocalGlassShadowElevation = staticCompositionLocalOf { 8.dp }

/**
 * No-op modifier — Liquid Glass system has been removed.
 *
 * Returns the receiver unchanged so that existing call sites that chain
 * `.glassBackground(...)` continue to compile and behave as a plain Modifier.
 */
@Composable
fun Modifier.glassBackground(
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
    shape: Shape = RoundedCornerShape(16.dp),
    animateShine: Boolean = true,
): Modifier = this

/**
 * Standard Material3 [Surface] container — Liquid Glass disabled.
 * Delegates directly to [Surface] with a solid `surfaceContainer` color.
 */
@Composable
fun AppSurfaceBox(
    modifier: Modifier = Modifier,
    tintColor: Color = Color.White,
    borderColor: Color = Color.White,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(content = content)
    }
}

/**
 * Standard Material3 [TopAppBar] — Liquid Glass disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier,
        scrollBehavior = scrollBehavior
    )
}

/**
 * Standard Material3 [NavigationBar] — Liquid Glass disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
        modifier = modifier,
    )
}

/**
 * Standard Material3 [NavigationRail] — Liquid Glass disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationRail(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NavigationRail(
        header = header,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
        modifier = modifier,
    )
}

/**
 * Standard Material3 [Card] (clickable) — Liquid Glass disabled.
 */
@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    elevation: androidx.compose.material3.CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        interactionSource = interactionSource,
        modifier = modifier,
        content = content,
    )
}

/**
 * Standard Material3 [Card] (non-clickable) — Liquid Glass disabled.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    elevation: androidx.compose.material3.CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

/**
 * Standard Material3 [FloatingActionButton] — Liquid Glass disabled.
 */
@Composable
fun AppFAB(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content
    )
}

/**
 * Floating pill control — standard Material3 [Surface] with a pill shape.
 * Liquid Glass disabled.
 */
@Composable
fun AppPill(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tintColor: Color = Color.White,
    content: @Composable RowScope.() -> Unit,
) {
    val pillShape = RoundedCornerShape(100.dp)
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = pillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) { content() }
    }
}

/**
 * Standard Material3 [DropdownMenu] — Liquid Glass disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content
    )
}

/**
 * Standard Material3 [DropdownMenuItem] — Liquid Glass disabled.
 */
@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
    )
}

/**
 * Standard Material3 [ModalBottomSheet] — Liquid Glass disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: @Composable (() -> Unit)? = {
        androidx.compose.material3.BottomSheetDefaults.DragHandle(
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = dragHandle,
        content = content
    )
}
