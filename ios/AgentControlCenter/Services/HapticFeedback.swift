import UIKit

// MARK: - HapticFeedback
// 对应 Android HapticFeedback (core/ui/HapticFeedback.kt)
// iOS 使用 UIFeedbackGenerator 系列实现触觉反馈

/// 触觉反馈工具，提供与 Android 一致的 light / medium / error 接口。
///
/// 使用方式：
/// ```swift
/// HapticFeedback.light()
/// HapticFeedback.medium()
/// HapticFeedback.error()
/// HapticFeedback.success()
/// ```
///
/// CI-fix: `UIImpactFeedbackGenerator` / `UINotificationFeedbackGenerator` /
/// `UISelectionFeedbackGenerator` 在 Swift 6 strict concurrency 下均为
/// MainActor-isolated（其 `init(style:)` / `prepare()` / `impactOccurred()` /
/// `notificationOccurred(_:)` / `selectionChanged()` 均标注为 MainActor）。
/// 将 `HapticFeedback` 整体声明为 `@MainActor`，所有静态方法即可在
/// MainActor 上下文中调用这些 API。所有调用方均为 SwiftUI 视图
/// （Button action / 触摸回调），天然 MainActor-isolated，不受影响。
@MainActor
enum HapticFeedback {

    // MARK: - Impact Feedback

    /// 轻触反馈 — 对应 Android `light(context)`
    static func light() {
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        generator.impactOccurred()
    }

    /// 中等强度反馈 — 对应 Android `medium(context)`
    static func medium() {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.prepare()
        generator.impactOccurred()
    }

    /// 重击反馈
    static func heavy() {
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.prepare()
        generator.impactOccurred()
    }

    // MARK: - Notification Feedback

    /// 错误反馈 — 对应 Android `error(context)`
    /// 连续振动模式（0-30-50-30ms）→ iOS 用 .error 通知反馈近似
    static func error() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.error)
    }

    /// 成功反馈
    static func success() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
    }

    /// 警告反馈
    static func warning() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.warning)
    }

    // MARK: - Selection Feedback

    /// 选择变化反馈 — 用于 Picker / FilterChip 切换
    static func selection() {
        let generator = UISelectionFeedbackGenerator()
        generator.prepare()
        generator.selectionChanged()
    }
}
