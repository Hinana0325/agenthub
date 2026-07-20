package com.agentcontrolcenter.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Agent Control Center 形状系统 — 基于 Material 3 Expressive。
 *
 * M3 Expressive 提供 35 种形状 token，覆盖从极小圆角到完全圆形的完整范围。
 * 形状按语义分类：
 * - XS (4-8dp): 标签、芯片、小按钮
 * - S (8-12dp): 卡片、输入框
 * - M (12-16dp): 容器、面板
 * - L (16-24dp): 大卡片、底部抽屉
 * - XL (24-32dp): 全屏卡片、模态
 * - Pill (100dp): 胶囊形按钮、FAB
 * - Circle: 头像、图标容器
 *
 * @see <a href="https://m3.material.io/styles/shape/shape-scale-tokens">M3 Shape Tokens</a>
 */

// ── Extra Small ──
val ShapeXs2 = RoundedCornerShape(2.dp)
val ShapeXs4 = RoundedCornerShape(4.dp)
val ShapeXs6 = RoundedCornerShape(6.dp)
val ShapeXs8 = RoundedCornerShape(8.dp)

// ── Small ──
val ShapeS10 = RoundedCornerShape(10.dp)
val ShapeS12 = RoundedCornerShape(12.dp)

// ── Medium ──
val ShapeM14 = RoundedCornerShape(14.dp)
val ShapeM16 = RoundedCornerShape(16.dp)

// ── Large ──
val ShapeL18 = RoundedCornerShape(18.dp)
val ShapeL20 = RoundedCornerShape(20.dp)
val ShapeL24 = RoundedCornerShape(24.dp)

// ── Extra Large ──
val ShapeXl28 = RoundedCornerShape(28.dp)
val ShapeXl32 = RoundedCornerShape(32.dp)

// ── Pill ──
val ShapePill = RoundedCornerShape(100.dp)

// ── Circle ──
val ShapeCircle = CircleShape

// ── 便捷别名（兼容已有代码引用）──
// 注意：别名必须在 AppShapes 之前定义，且不能与上方主定义重名。
val ShapeXs = ShapeXs4   // 4dp
val ShapeS8 = ShapeXs8   // 8dp
val ShapeM12 = ShapeS12  // 12dp
val ShapeL16 = ShapeM16  // 16dp

/**
 * Material 3 Shapes 配置。
 *
 * 映射到 MaterialTheme 的 shapes token，供所有 M3 组件使用。
 * 默认值遵循 M3 标准：extraSmall=4dp, small=8dp, medium=12dp, large=16dp, extraLarge=28dp。
 */
val AppShapes = Shapes(
    extraSmall = ShapeXs4,
    small = ShapeS8,
    medium = ShapeM12,
    large = ShapeL16,
    extraLarge = ShapeXl28
)
