import SwiftUI

/// `GlassEffectContainer` 的便捷封装
///
/// 根据 WWDC 2025 Session 323 的强制要求：多个带 `.glassEffect()` 的子视图
/// 必须放在同一个 `GlassEffectContainer` 中，否则各自独立采样会导致：
/// 1. 视觉不一致（每块玻璃采样区域不同）
/// 2. 性能下降（多次渲染 pass）
/// 3. 无法触发 morph 形变动画
///
/// 本封装统一 `spacing` 默认值为 `GlassTokens.containerSpacing`，避免在调用点散落魔法数字。
///
/// 用法：
/// ```swift
/// GlassContainer {
///     HStack {
///         Button("编辑") {}.glassEffect()
///         Button("分享") {}.glassEffect()
///     }
/// }
/// ```
struct GlassContainer<Content: View>: View {

    /// 子元素接近此距离时开始融合；nil 表示使用系统默认
    private let spacing: CGFloat?

    /// 内容闭包
    private let content: () -> Content

    /// 创建玻璃容器
    /// - Parameters:
    ///   - spacing: 子元素融合间距，默认 `GlassTokens.containerSpacing`
    ///   - content: 包含多个 `.glassEffect()` 子视图的闭包
    init(spacing: CGFloat? = GlassTokens.containerSpacing,
         @ViewBuilder content: @escaping () -> Content) {
        self.spacing = spacing
        self.content = content
    }

    var body: some View {
        GlassEffectContainer(spacing: spacing, content: content)
    }
}
