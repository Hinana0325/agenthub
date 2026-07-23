import SwiftUI

/// 统一错误状态视图。
/// 显示错误图标、标题、描述和可选的重试按钮。
///
/// 用于在 View body 中替代「错误反馈」类 alert：当 `errorMessage != nil` 时
/// 居中渲染本视图覆盖主内容，并提供「重试」按钮触发回调。
/// 「确认操作」类提示（如删除确认）请继续使用 `.alert`。
struct ErrorStateView: View {
    /// SF Symbol 图标名（默认警告三角，调用方可传入与场景相关的图标）
    var icon: String = "exclamationmark.triangle.fill"
    /// 错误标题（headline 字号）
    var title: String = "加载失败"
    /// 错误描述（subheadline 字号，建议给出可操作建议）
    var message: String
    /// 重试按钮回调（nil 时不渲染重试按钮）
    var onRetry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 56))
                .foregroundStyle(.red.opacity(0.6))

            Text(title)
                .font(.headline)
                .fontWeight(.semibold)

            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if let onRetry {
                Button(action: onRetry) {
                    Label("重试", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
