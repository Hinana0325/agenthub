import SwiftUI

/// 液态玻璃预设 View 扩展
///
/// 封装 iOS 26 `.glassEffect(_:in:)` 的常用形态，避免在调用点重复书写
/// `Glass` variant 与 `Shape` 组合。所有方法严格遵循 HIG：仅用于浮动控件，
/// 不可用于内容层（卡片 / 气泡 / 列表 cell）。
///
/// 与 Android 端 `GlassPill` / `GlassFloatingActionButton` 等组件对应。
///
/// R3: 部署目标回退到 iOS 18 后，所有方法使用 `@ViewBuilder` + `if #available(iOS 26, *)`
/// 双分支。iOS 26 走真正的 Liquid Glass；iOS 18 回退到 `.ultraThinMaterial`，
/// 视觉上为普通半透明材质（无 lensing / morph），但保留浮动控件的层次感。
///
/// CI-fix: 由于 `Glass` 类型与 `.glassEffect()` / `.glassEffectID()` 方法仅存在于
/// Xcode 26（Swift 6.2）的 iOS 26 SDK 中，Xcode 16.4 编译时无法解析这些符号。
/// `if #available(iOS 26, *)` 是运行时检查，不能让旧 SDK 编译器解析新类型。
/// 因此所有引用 iOS 26 Glass API 的分支均用 `#if compiler(>=6.2)` 做编译时门控，
/// 在 Xcode 16.4 上直接走 `#else` 回退分支（ultraThinMaterial）。
extension View {

    /// 浮动药丸玻璃 —— 用于状态条 / 录制药丸 / 快捷动作
    ///
    /// 使用 `GlassTokens.interactiveVariant`（带按压反馈）+ `Capsule` 形状。
    /// - Returns: 应用了液态玻璃效果的视图
    @ViewBuilder
    func glassPill() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.interactiveVariant, in: GlassTokens.pillShape)
        } else {
            self.background(.ultraThinMaterial, in: GlassTokens.pillShape)
        }
        #else
        // Xcode 16 构建 — Glass API 不可用，使用 ultraThinMaterial 回退
        self.background(.ultraThinMaterial, in: GlassTokens.pillShape)
        #endif
    }

    /// 圆形浮动按钮玻璃 —— 用于 FAB / 录音按钮 / 发送按钮
    ///
    /// 使用 `GlassTokens.interactiveVariant` + `Circle` 形状。
    /// - Returns: 应用了液态玻璃效果的视图
    @ViewBuilder
    func glassFloating() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.interactiveVariant, in: GlassTokens.circleShape)
        } else {
            self.background(.ultraThinMaterial, in: GlassTokens.circleShape)
        }
        #else
        self.background(.ultraThinMaterial, in: GlassTokens.circleShape)
        #endif
    }

    /// 通用交互式玻璃 —— 用于 CommandPalette / 自定义悬浮控件
    ///
    /// 使用 `GlassTokens.interactiveVariant` + 自定义 `Shape`。
    /// - Parameter shape: 玻璃裁剪形状
    /// - Returns: 应用了液态玻璃效果的视图
    @ViewBuilder
    func glassInteractive<S: Shape>(in shape: S) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.interactiveVariant, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
        #else
        self.background(.ultraThinMaterial, in: shape)
        #endif
    }

    /// 非交互式玻璃 —— 用于纯展示型浮动元素（无按压反馈）
    ///
    /// 使用 `GlassTokens.regularVariant` + 自定义 `Shape`。
    /// 适用场景：不可点击的状态徽章、信息浮层。
    /// - Parameter shape: 玻璃裁剪形状
    /// - Returns: 应用了液态玻璃效果的视图
    @ViewBuilder
    func glassStatic<S: Shape>(in shape: S) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.regularVariant, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
        #else
        self.background(.ultraThinMaterial, in: shape)
        #endif
    }

    /// 带 tint 的交互式玻璃 —— 用于状态条 / 录音按钮 / 发送按钮等需要状态色的浮动控件
    ///
    /// R4: 调用点原本直接 `.glassEffect(GlassTokens.interactiveVariant.tint(color), in: shape)`，
    /// 因 `Glass.tint(_:)` 与 `.glassEffect(_:in:)` 均为 iOS 26+ API，
    /// 此包装统一提供 iOS 18 回退（`ultraThinMaterial` + tint 色块叠加近似 tinted glass）。
    ///
    /// - Parameters:
    ///   - tint: 玻璃 tint 颜色（建议带 opacity）
    ///   - shape: 玻璃裁剪形状
    /// - Returns: 应用了 tint 玻璃效果的视图
    @ViewBuilder
    func glassTinted<S: Shape>(_ tint: Color, in shape: S) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.interactiveVariant.tint(tint), in: shape)
        } else {
            // R4: iOS 18 回退 —— tint 色块叠加在 ultraThinMaterial 之上
            self.background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    shape.fill(tint)
                }
            }
        }
        #else
        // Xcode 16 构建 — Glass API 不可用，使用 ultraThinMaterial + tint 回退
        self.background {
            ZStack {
                shape.fill(.ultraThinMaterial)
                shape.fill(tint)
            }
        }
        #endif
    }

    /// 玻璃 morph ID 兼容包装 —— 用于 `GlassEffectContainer` 内子视图间的形变过渡
    ///
    /// R4: 系统的 `.glassEffectID(_:in:)` 仅 iOS 26+ 可用，且与自身同名无法直接转发，
    /// 故重命名为 `glassMorphID`。iOS 26 走系统 morph ID 实现 send↔stop / voice↔action 形变；
    /// iOS 18 无 morph 概念，直接返回原视图（按钮仍然正常显示，仅缺失形变动画）。
    ///
    /// - Parameters:
    ///   - id: 子视图在容器内的稳定标识
    ///   - namespace: 与父 `GlassContainer` 共享的 Namespace
    /// - Returns: 应用了 morph ID 的视图
    @ViewBuilder
    func glassMorphID<ID: Hashable>(_ id: ID, in namespace: Namespace.ID) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            // R4: iOS 18 回退 —— 无 morph 动画
            self
        }
        #else
        // Xcode 16 构建 — glassEffectID 不可用，直接返回原视图
        self
        #endif
    }
}
