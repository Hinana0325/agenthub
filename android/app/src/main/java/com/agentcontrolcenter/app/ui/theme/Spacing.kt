package com.agentcontrolcenter.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * v5.0: 统一间距 token，替代散落各处的硬编码 dp。
 * 4-8-16-24-32 黄金间距比例 + 扩展档位。
 */
object Spacing {
    val Xxs = 2.dp    // 极小间距（图标内间距）
    val Xs = 4.dp     // 小间距（图标与文字）
    val Sm = 8.dp     // 紧凑间距（列表项内）
    val Md = 12.dp    // 中等间距
    val Lg = 16.dp    // 标准间距（卡片内 padding）
    val Xl = 24.dp    // 大间距（Section 间）
    val Xxl = 32.dp   // 超大间距（页面边距大屏）
    val Xxxl = 48.dp  // 极大间距（空状态图标间距）

    /** 常用 padding 组合 */
    val CardPadding = Lg
    val ScreenPadding = Lg
    val ItemSpacing = Sm
}
