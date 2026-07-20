package com.agentcontrolcenter.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 风格加载指示器。
 *
 * 三个圆点交替缩放和淡入淡出，配合 spring 动效，
 * 符合 Material 3 Expressive 的 Motion 设计语言。
 *
 * 替代标准 CircularProgressIndicator，提供更有活力的加载反馈。
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val transition = rememberInfiniteTransition(label = "loading")

    // 三个点交错动画，每个延迟 200ms
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1Alpha"
    )
    val dot1Scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1Scale"
    )

    val dot2Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2Alpha"
    )
    val dot2Scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2Scale"
    )

    val dot3Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3Alpha"
    )
    val dot3Scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3Scale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingDot(
            alpha = dot1Alpha,
            scale = dot1Scale,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        LoadingDot(
            alpha = dot2Alpha,
            scale = dot2Scale,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        LoadingDot(
            alpha = dot3Alpha,
            scale = dot3Scale,
            color = MaterialTheme.colorScheme.primary
        )
        if (label != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun LoadingDot(
    alpha: Float,
    scale: Float,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

/**
 * M3 Expressive 骨架屏加载行。
 *
 * 多行 shimmer 占位块，用于列表首屏加载。
 */
@Composable
fun ExpressiveSkeletonList(
    itemCount: Int = 5,
    modifier: Modifier = Modifier
) {
    repeat(itemCount) { index ->
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            SkeletonBox(
                modifier = Modifier.size(48.dp),
                cornerRadius = 24
            )
            // Text lines
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 3 == 0) 0.4f else 0.6f)
                        .height(16.dp)
                )
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                )
            }
        }
    }
}
