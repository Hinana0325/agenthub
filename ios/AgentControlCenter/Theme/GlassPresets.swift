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
    ///
    /// 黑框修复: 原 iOS 18 / Xcode 16 回退用 `shape.fill(.ultraThinMaterial)` 作基底，
    /// 再叠加 `shape.fill(tint)`。问题：当 `tint == .clear`（如 ChatView 非录音态语音按钮）
    /// 或 tint 低 opacity（如 `Color.gray.opacity(0.3)` 禁用态）时，tint 无法遮盖
    /// `.ultraThinMaterial` 的暗色基底，深色模式下显示为黑色圆圈/药丸（"黑框" bug）。
    ///
    /// 修复策略（按 tint alpha 分级）：
    /// - alpha < 0.01（透明）：不绘制任何 background，保持按钮自身样式，避免暗色基底透出
    /// - 0.01 ≤ alpha < 0.5（低 opacity）：tint 提升到 0.6 opacity 作为单一 fill，
    ///   不画 ultraThinMaterial，避免暗色基底与 tint 混合后偏黑
    /// - alpha ≥ 0.5（高 opacity）：保留原 ultraThinMaterial + tint 叠加，tint 足以遮盖基底
    @ViewBuilder
    func glassTinted<S: Shape>(_ tint: Color, in shape: S) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(GlassTokens.interactiveVariant.tint(tint), in: shape)
        } else {
            tintedFallback(tint, in: shape)
        }
        #else
        // Xcode 16 构建 — Glass API 不可用，使用 tint 分级回退
        tintedFallback(tint, in: shape)
        #endif
    }

    /// `glassTinted` 的 iOS 18 / Xcode 16 回退实现 — 按 tint alpha 分级避免黑框。
    @ViewBuilder
    private func tintedFallback<S: Shape>(_ tint: Color, in shape: S) -> some View {
        let alpha = tint.cgColor?.alpha ?? 1.0
        if alpha < 0.01 {
            // 透明 tint：不绘制 background，避免 ultraThinMaterial 暗色基底形成黑框
            self
        } else if alpha < 0.5 {
            // 低 opacity tint：tint 提升到 0.6 opacity 作为单一 fill
            self.background(tint.opacity(0.6), in: shape)
        } else {
            // 高 opacity tint：保留原 ultraThinMaterial + tint 叠加
            self.background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    shape.fill(tint)
                }
            }
        }
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
