package com.agentcontrolcenter.app.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 预测性返回手势包装器。
 *
 * 为内容添加预测性返回的缩放动画效果：
 * - 返回手势进行中时，内容随手指滑动缩小（最小 0.9）并淡出（最小 0.7）
 * - 手势完成（返回）后，内容完全消失
 * - 手势取消后，内容平滑恢复到原始状态
 *
 * 用于顶层导航容器，让返回手势有视觉反馈。
 *
 * 实现说明：
 * - 项目已依赖 `androidx.activity:activity-compose:1.10.1`（>= 1.8），
 *   因此直接使用 [PredictiveBackHandler]（基于 `Flow<BackEventCompat>`）
 *   以获得"动画跟随手指"的真实预测性体验；不回退到 [androidx.activity.compose.BackHandler]。
 * - 手势进行中：用 [Animatable.snapTo] 同步缩放/透明度，避免动画延迟。
 * - 手势完成：用 spring 动画将 alpha 推到 0，再回调 [onBack]，最后重置。
 * - 手势取消：在独立协程中用 spring 平滑恢复，避免被父协程的取消信号打断。
 *
 * @param enabled 是否启用预测性返回动画。通常在栈底（无 previousBackStackEntry）时设为 false，
 *                让系统接管返回（例如退出 App）。
 * @param onBack  返回手势完成时回调。通常为 `navController.popBackStack()`。
 * @param content 被包裹的内容。
 */
@Composable
fun PredictiveBackWrapper(
    enabled: Boolean = true,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // 手势进行中的目标值：缩到 0.9、淡到 0.7
    val minScale = 0.9f
    val minAlpha = 0.7f

    // PredictiveBackHandler 的 lambda 是 suspend，接收手势事件流。
    // - progress.collect 期间：根据 backEvent.progress 同步缩放/透明度（跟随手指）
    // - flow 正常结束（手势提交）：spring 淡出到 0，执行 onBack，最后重置状态
    // - 抛出 CancellationException（手势取消）：在新协程中 spring 恢复，然后重新抛出
    PredictiveBackHandler(enabled = enabled) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { backEvent ->
                // 跟随手势进度同步缩放和透明度，避免动画延迟
                scale.snapTo(1f - (1f - minScale) * backEvent.progress)
                alpha.snapTo(1f - (1f - minAlpha) * backEvent.progress)
            }
            // 手势完成：完全淡出后执行返回
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            onBack()
            // 重置状态，供下次返回手势使用
            scale.snapTo(1f)
            alpha.snapTo(1f)
        } catch (e: CancellationException) {
            // 手势取消：在新协程中平滑恢复（避免被父协程取消信号打断）
            scope.launch {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            throw e
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
    ) {
        content()
    }
}
