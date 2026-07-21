package com.agentcontrolcenter.app.ui.adaptive

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * 折叠屏姿态检测。
 *
 * 使用 Jetpack WindowManager 监听窗口布局变化，
 * 检测铰链位置和姿态（半开 / 平铺）。
 *
 * I1: 折叠屏双栏布局优化。
 */

/**
 * 当前窗口的 [FoldingFeature]，如果设备不是折叠屏则为 null。
 *
 * 通过 [WindowInfoTracker] 监听 [androidx.window.layout.WindowLayoutInfo]
 * 的流式更新，自动过滤出第一个 [FoldingFeature]（多铰链设备理论上极少，
 * 取第一个即可）。非折叠屏设备始终返回 null，调用方需做 null 安全处理。
 *
 * 注意：必须在 [androidx.activity.ComponentActivity] 上下文中调用，
 * 否则 `context as Activity` 会抛出 ClassCastException。本 App 的
 * [com.agentcontrolcenter.app.MainActivity] 继承自 ComponentActivity，
 * 在 Compose 树中取到的 [LocalContext] 即为 Activity 实例。
 */
@Composable
fun currentFoldingFeature(): State<FoldingFeature?> {
    val context = LocalContext.current
    return produceState<FoldingFeature?>(initialValue = null, context) {
        val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
        windowInfoTracker.windowLayoutInfo(context as Activity).collect { layoutInfo ->
            value = layoutInfo.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .firstOrNull()
        }
    }
}

/**
 * 是否处于半开姿态（book mode）。
 *
 * 折叠屏半开且铰链垂直分隔屏幕时为 true，适合双栏布局
 * （list 在左、detail 在右，中间避开铰链）。
 *
 * 判断依据：
 * - state == [FoldingFeature.State.HALF_OPENED]
 * - orientation == [FoldingFeature.Orientation.VERTICAL]（铰链竖向）
 */
val FoldingFeature?.isBookMode: Boolean
    get() = this?.state == FoldingFeature.State.HALF_OPENED &&
            this.orientation == FoldingFeature.Orientation.VERTICAL

/**
 * 是否处于平铺姿态（table mode）。
 *
 * 折叠屏半开且铰链水平分隔屏幕时为 true，适合上下分栏
 * （上内容、下控制）。I1 暂未启用此模式，预留扩展。
 *
 * 判断依据：
 * - state == [FoldingFeature.State.HALF_OPENED]
 * - orientation == [FoldingFeature.Orientation.HORIZONTAL]（铰链横向）
 */
val FoldingFeature?.isTableMode: Boolean
    get() = this?.state == FoldingFeature.State.HALF_OPENED &&
            this.orientation == FoldingFeature.Orientation.HORIZONTAL

/**
 * 铰链宽度（**像素**），用于在布局中避开铰链区域。
 *
 * 注意：[FoldingFeature.getBounds] 返回的 [android.graphics.Rect] 单位为像素，
 * 此属性返回的 Int 即为像素值。调用方需要通过 [androidx.compose.ui.platform.LocalDensity]
 * 转换为 dp 后再用于 Compose 布局（如 `Modifier.width(...)`）：
 *
 * ```kotlin
 * val density = LocalDensity.current
 * val hingeDp = with(density) { foldingFeature.hingeWidthPx.toDp() }
 * ```
 *
 * 非折叠屏或全开（[FoldingFeature.State.FLAT]）时返回 0。
 */
val FoldingFeature?.hingeWidthPx: Int
    get() = this?.bounds?.width() ?: 0
