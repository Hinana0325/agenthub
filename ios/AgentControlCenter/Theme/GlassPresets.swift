import SwiftUI

/// 液态玻璃预设 View 扩展
///
/// 封装 iOS 26 `.glassEffect(_:in:)` 的常用形态，避免在调用点重复书写
/// `Glass` variant 与 `Shape` 组合。所有方法严格遵循 HIG：仅用于浮动控件，
/// 不可用于内容层（卡片 / 气泡 / 列表 cell）。
///
/// 与 Android 端 `GlassPill` / `GlassFloatingActionButton` 等组件对应。
extension View {

    /// 浮动药丸玻璃 —— 用于状态条 / 录制药丸 / 快捷动作
    ///
    /// 使用 `GlassTokens.interactiveVariant`（带按压反馈）+ `Capsule` 形状。
    /// - Returns: 应用了液态玻璃效果的视图
    func glassPill() -> some View {
        self.glassEffect(GlassTokens.interactiveVariant, in: GlassTokens.pillShape)
    }

    /// 圆形浮动按钮玻璃 —— 用于 FAB / 录音按钮 / 发送按钮
    ///
    /// 使用 `GlassTokens.interactiveVariant` + `Circle` 形状。
    /// - Returns: 应用了液态玻璃效果的视图
    func glassFloating() -> some View {
        self.glassEffect(GlassTokens.interactiveVariant, in: GlassTokens.circleShape)
    }

    /// 通用交互式玻璃 —— 用于 CommandPalette / 自定义悬浮控件
    ///
    /// 使用 `GlassTokens.interactiveVariant` + 自定义 `Shape`。
    /// - Parameter shape: 玻璃裁剪形状
    /// - Returns: 应用了液态玻璃效果的视图
    func glassInteractive<S: Shape>(in shape: S) -> some View {
        self.glassEffect(GlassTokens.interactiveVariant, in: shape)
    }

    /// 非交互式玻璃 —— 用于纯展示型浮动元素（无按压反馈）
    ///
    /// 使用 `GlassTokens.regularVariant` + 自定义 `Shape`。
    /// 适用场景：不可点击的状态徽章、信息浮层。
    /// - Parameter shape: 玻璃裁剪形状
    /// - Returns: 应用了液态玻璃效果的视图
    func glassStatic<S: Shape>(in shape: S) -> some View {
        self.glassEffect(GlassTokens.regularVariant, in: shape)
    }
}
