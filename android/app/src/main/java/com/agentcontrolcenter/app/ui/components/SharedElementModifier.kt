package com.agentcontrolcenter.app.ui.components

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 共享元素动画修饰符扩展。
 *
 * 在列表项和详情页上使用相同的 key，
 * 切换时会自动动画过渡。
 *
 * 用法：
 * ```
 * Surface(modifier = Modifier.sharedBounds("session_${session.id}")) { ... }
 * ```
 *
 * 实现：通过 [composed] 在组合时从 [LocalSharedTransitionScope] 与
 * [LocalNavAnimatedVisibilityScope] 取出 scope。两个都存在时才生效，
 * 否则原样返回 Modifier，确保在未包裹 SharedTransitionLayout 的环境
 * （如 Preview、单测）中也能安全调用。
 *
 * @param key 共享元素唯一标识（如 "session_${session.id}"）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedBounds(
    key: String
): Modifier = composed {
    val sharedScope = LocalSharedTransitionScope.current
    val animatedScope = LocalNavAnimatedVisibilityScope.current

    if (sharedScope != null && animatedScope != null) {
        with(sharedScope) {
            // rememberSharedContentState 是 SharedTransitionScope 的成员函数，
            // 必须在 with(sharedScope) 块内调用。
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedScope
            )
        }
    } else {
        Modifier
    }
}
