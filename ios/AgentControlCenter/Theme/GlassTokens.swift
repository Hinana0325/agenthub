import SwiftUI

/// 液态玻璃设计令牌
///
/// 集中管理 iOS 26 Liquid Glass 的 `Glass` variant、形状、间距、弹簧等少量参数。
/// 与 Android 端 `ui/theme/Color.kt` + `GlassModifier.kt` 中的令牌对应，但 iOS 端
/// 严格遵循 WWDC 2025 Session 219/323 的 HIG：玻璃仅用于「导航层」悬浮元素，
/// 不用于内容层（卡片、气泡、列表 cell）。因此本文件远比 Android 端精简。
///
/// 设计原则：
/// 1. 不自研色散 / 边缘光 / 动态光泽 —— iOS 26 系统级 lensing 已包含这些效果
/// 2. 不模糊内容 —— 仅在浮动控件上调用 `.glassEffect()`
/// 3. 多个玻璃元素必须用 `GlassEffectContainer` 包裹（HIG 强制）
enum GlassTokens {

    // MARK: - Glass Variant

    /// 默认玻璃 variant（透镜感最自然，适用于大多数浮动控件）
    ///
    /// R3: `Glass` 类型本身为 iOS 26+ API，故该属性需标注 `@available(iOS 26, *)`。
    /// iOS 18 调用点应通过 `if #available(iOS 26, *)` 守卫后再访问此属性，
    /// 回退分支使用 `.ultraThinMaterial` 等普通材质（见 GlassPresets.swift）。
    @available(iOS 26, *)
    static let regularVariant: Glass = .regular

    /// 交互式玻璃 variant（带按压反馈，适用于可点击的浮动按钮 / 药丸）
    ///
    /// R3: 同上，`Glass` 类型仅 iOS 26+ 可用。
    @available(iOS 26, *)
    static let interactiveVariant: Glass = .regular.interactive()

    // MARK: - 间距

    /// `GlassEffectContainer` 默认 spacing：子元素接近此距离时开始融合
    static let containerSpacing: CGFloat = 16

    // MARK: - 弹簧（对齐 Android GlassMotion）

    /// 弹跳弹簧 —— 用于浮动元素按压 / 入场（对应 Android `SpringBounce`）
    static let bounceSpring: Spring = .spring(response: 0.35, dampingFraction: 0.7)

    /// 平滑弹簧 —— 用于展开 / 折叠过渡（对应 Android `SpringSmooth`）
    static let smoothSpring: Spring = .spring(response: 0.5, dampingFraction: 0.85)

    /// 退出弹簧 —— 用于消失动画（对应 Android `SpringExit`）
    static let exitSpring: Spring = .spring(response: 0.3, dampingFraction: 0.9)

    // MARK: - 形状

    /// 浮动药丸 / 状态条默认形状
    static let pillShape: Capsule = Capsule()

    /// 圆形浮动按钮（FAB / 录音按钮）形状
    static let circleShape: Circle = Circle()

    /// 命令面板等中等圆角矩形的圆角半径
    static let sheetCornerRadius: CGFloat = AppTheme.CornerRadius.xl

    /// 命令面板矩形形状
    static var sheetShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: sheetCornerRadius, style: .continuous)
    }
}
