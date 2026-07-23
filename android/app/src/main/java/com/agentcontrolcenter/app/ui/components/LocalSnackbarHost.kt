package com.agentcontrolcenter.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全局 [SnackbarHostState] 的 CompositionLocal 入口。
 *
 * 由根 [androidx.compose.material3.Scaffold]（[com.agentcontrolcenter.app.navigation.AppNavigation]）
 * 在应用启动时通过 [androidx.compose.runtime.CompositionLocalProvider] 提供单一实例，
 * 任意 Composable 子树可通过 [LocalSnackbarHost].current 拿到同一个 state，
 * 调用 [SnackbarHostState.showSnackbar] 即可在根 Scaffold 上展示消息——
 * 替代散落各处的 [android.widget.Toast]。
 *
 * 注意：[SnackbarHostState.showSnackbar] 是 suspend 操作，需在协程中执行，
 * 推荐在 [androidx.compose.runtime.rememberCoroutineScope] 或
 * [androidx.compose.runtime.LaunchedEffect] 中调用。Toast 的 LENGTH_SHORT/LONG
 * 习惯可通过 [androidx.compose.material3.SnackbarDuration].Short/Long 映射。
 */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }
