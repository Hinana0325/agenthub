package com.agentcontrolcenter.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

/**
 * 共享元素动画上下文。
 *
 * 提供 SharedTransitionScope 和 AnimatedVisibilityScope，
 * 供列表项和详情页之间共享元素动画。
 *
 * 使用方式：
 * 1. 在 NavHost 外层包裹 SharedTransitionLayout
 * 2. 在 composable 中通过 LocalSharedTransitionScope 获取 scope
 * 3. 在列表项和详情项上使用 Modifier.sharedElement()
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * 当前页面的 AnimatedVisibilityScope，用于 sharedElement modifier。
 *
 * 在 NavHost 的 composable lambda 中，`this` 是 AnimatedContentScope，
 * 它实现了 AnimatedVisibilityScope，可直接 provides。
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
