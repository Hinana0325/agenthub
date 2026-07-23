import SwiftUI

/// 应用设计令牌系统
///
/// 统一管理颜色、间距、圆角、阴影、字体等设计变量。
/// 所有视觉常量均从此处取用，避免在视图层散落魔法数字。
///
/// 兼容性说明：
/// - `statusColors` / `taskStatusColors` 保留枚举键，以兼容现有调用
///   `AppTheme.statusColors[agent.status]` / `AppTheme.taskStatusColors[task.status]`。
/// - `timeAgo` 入参仍为毫秒级时间戳（与 `Session.updatedAt`、`Task.createdAt`、
///   `Message.timestamp` 等数据模型保持一致，均由 `Date().timeIntervalSince1970 * 1000` 生成）。
/// - `ThemePreference` 为全仓唯一的外观主题枚举（"light" / "dark" / "system"），
///   `SettingsView` 与 `ContentView` 共用同一个 `@AppStorage("theme")` 键。
enum AppTheme {

    // MARK: - 间距 (Spacing)

    /// 间距令牌（pt）
    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
    }

    // MARK: - 圆角 (CornerRadius)

    /// 圆角令牌（pt）
    enum CornerRadius {
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let pill: CGFloat = 999
    }

    // MARK: - 阴影 (Shadow)

    /// 阴影令牌（color / radius / x / y）
    enum Shadow {
        static let light = (color: Color.black.opacity(0.05), radius: CGFloat(4), x: CGFloat(0), y: CGFloat(2))
        static let medium = (color: Color.black.opacity(0.1), radius: CGFloat(8), x: CGFloat(0), y: CGFloat(4))
        static let heavy = (color: Color.black.opacity(0.15), radius: CGFloat(16), x: CGFloat(0), y: CGFloat(8))
    }

    // MARK: - 字体 (Typography)

    /// 字体令牌：基于 SwiftUI 语义文本层级，自动响应系统 Dynamic Type。
    ///
    /// 所有视图应优先使用此处的语义档位（`AppTheme.Typography.body` 等），
    /// 避免散落 `.font(.title)` 字面量或 `.font(.system(size:))` 魔法数字。
    /// 等宽字体（`mono` / `monoCaption`）用于代码与数字展示。
    enum Typography {
        // 动态字体：基于 .system(.style, design:)，自动响应 Dynamic Type
        static let largeTitle = Font.system(.largeTitle, design: .default)
        static let title = Font.system(.title, design: .default)
        static let title2 = Font.system(.title2, design: .default)
        static let title3 = Font.system(.title3, design: .default)
        static let headline = Font.system(.headline, design: .default)
        static let body = Font.system(.body, design: .default)
        static let callout = Font.system(.callout, design: .default)
        static let subheadline = Font.system(.subheadline, design: .default)
        static let footnote = Font.system(.footnote, design: .default)
        static let caption = Font.system(.caption, design: .default)
        static let caption2 = Font.system(.caption2, design: .default)
        // 等宽字体（代码/数字）
        static let mono = Font.system(.body, design: .monospaced)
        static let monoCaption = Font.system(.caption, design: .monospaced)
    }

    // MARK: - 品牌色

    /// 主品牌色（引用 Assets 中的 AccentColor，#214DF6）
    static let primaryColor = Color("AccentColor")

    /// 主品牌渐变（左上 → 右下）
    static let primaryGradient = LinearGradient(
        colors: [Color(red: 0.13, green: 0.30, blue: 0.96), Color(red: 0.25, green: 0.55, blue: 1.0)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    // MARK: - 语义色

    static let successColor = Color.green
    static let warningColor = Color.orange
    static let errorColor = Color.red
    static let infoColor = Color.blue

    // MARK: - 背景色

    static let backgroundColor = Color(.systemGroupedBackground)
    static let secondaryBackground = Color(.secondarySystemGroupedBackground)
    static let tertiaryBackground = Color(.tertiarySystemGroupedBackground)

    // MARK: - 气泡色

    /// 用户气泡背景（品牌蓝）
    static let userBubbleColor = Color(red: 0.13, green: 0.30, blue: 0.96)
    /// 用户气泡文字
    static let userBubbleTextColor = Color.white
    /// 助手气泡背景
    static let assistantBubbleColor = Color(.secondarySystemGroupedBackground)
    /// 助手气泡文字
    static let assistantBubbleTextColor = Color.primary
    /// 系统气泡背景
    static let systemBubbleColor = Color(.tertiarySystemGroupedBackground)

    // MARK: - 文本色

    static let primaryTextColor = Color.primary
    static let secondaryTextColor = Color.secondary
    static let tertiaryTextColor = Color(.tertiaryLabel)

    // MARK: - 边框

    static let separatorColor = Color(.separator)
    static let borderColor = Color(.separator).opacity(0.5)

    // MARK: - 状态色

    /// Agent 状态 -> 颜色映射
    /// 使用 `AgentStatus` 枚举作为键，兼容 `AppTheme.statusColors[agent.status]`、
    /// `AppTheme.statusColors[.online]` 等既有调用方式。
    static let statusColors: [AgentStatus: Color] = [
        .online: .green,
        .offline: .gray,
        .connecting: .orange,
        .error: .red
    ]

    // MARK: - 任务状态色

    /// 任务状态 -> 颜色映射
    /// 使用 `TaskStatus` 枚举作为键，兼容 `AppTheme.taskStatusColors[task.status]` 等既有调用方式。
    static let taskStatusColors: [TaskStatus: Color] = [
        .pending: .gray,
        .running: .blue,
        .completed: .green,
        .failed: .red,
        .cancelled: .orange
    ]

    // MARK: - 动态字体

    /// 根据用户设置的字体大小返回 Font
    /// - Parameters:
    ///   - size: 字号档位（小 / 中 / 大）
    ///   - weight: 字重
    /// - Returns: 对应的 SwiftUI Font
    ///
    /// CI-fix: 此前 `enum AppTheme` 内嵌套了一个 `FontSize` 枚举（与
    /// `SettingsView.swift` 顶层的 `FontSize` 同义但不同类型），导致
    /// `SettingsView` 中 `@AppStorage("fontSize") private var fontSize: FontSize`
    /// 在 Swift 6 strict concurrency 下出现 "no exact matches in call to initializer"
    /// （`@AppStorage` 宏展开时类型解析存在歧义）。移除嵌套定义，统一使用顶层
    /// `FontSize: String, CaseIterable, Identifiable` 即可。
    static func dynamicFont(size: FontSize = .medium, weight: Font.Weight = .regular) -> Font {
        switch size {
        case .small:  return .system(.subheadline, design: .default, weight: weight)
        case .medium: return .system(.body,        design: .default, weight: weight)
        case .large:  return .system(.title3,      design: .default, weight: weight)
        }
    }

    // MARK: - 主题偏好

    /// 外观主题偏好（全仓唯一枚举）
    ///
    /// rawValue（"light" / "dark" / "system"）由 `SettingsView` 与 `ContentView`
    /// 共用同一个 `@AppStorage("theme")` 键，保证根视图与设置页之间的主题切换互通。
    /// 中文展示名通过 `displayName` 提供，避免将本地化字符串作为 rawValue。
    enum ThemePreference: String, CaseIterable {
        case light = "light"
        case dark = "dark"
        case system = "system"

        /// 中文展示名
        var displayName: String {
            switch self {
            case .light:  "浅色"
            case .dark:   "深色"
            case .system: "跟随系统"
            }
        }

        /// 映射到 SwiftUI 的 ColorScheme；`system` 返回 nil 表示跟随系统
        var colorScheme: ColorScheme? {
            switch self {
            case .light:  .light
            case .dark:   .dark
            case .system: nil
            }
        }
    }

    // MARK: - 辅助方法

    /// 将毫秒级时间戳格式化为相对时间字符串（"刚刚 / X分钟前 / X小时前 / X天前 / MM/dd"）。
    ///
    /// 兼容性：入参为毫秒级时间戳（与 `Session.updatedAt`、`Task.createdAt`、
    /// `Message.timestamp` 等模型字段一致），内部除以 1000 转为秒。
    /// - Parameter timestamp: 毫秒级时间戳
    /// - Returns: 本地化的相对时间描述
    static func timeAgo(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000)
        let interval = Date().timeIntervalSince(date)
        if interval < 60 { return "刚刚" }
        if interval < 3600 { return "\(Int(interval / 60))分钟前" }
        if interval < 86400 { return "\(Int(interval / 3600))小时前" }
        if interval < 604800 { return "\(Int(interval / 86400))天前" }
        // SW-M4: 使用现代 FormatStyle 替代 DateFormatter（输出形如 "01/15"）
        return date.formatted(Date.FormatStyle()
            .month(.twoDigits)
            .day(.twoDigits)
            .locale(Locale.current))
    }
}

// MARK: - View 扩展

extension View {
    /// 应用卡片样式：内边距 + 二级背景 + 大圆角 + 轻阴影
    func appCard() -> some View {
        self
            .padding(AppTheme.Spacing.lg)
            .background(AppTheme.secondaryBackground)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.lg))
            .shadow(
                color: AppTheme.Shadow.light.color,
                radius: AppTheme.Shadow.light.radius,
                x: AppTheme.Shadow.light.x,
                y: AppTheme.Shadow.light.y
            )
    }

    /// 应用药丸标签样式
    /// - Parameters:
    ///   - foreground: 文字颜色
    ///   - background: 背景颜色
    func appPill(
        foreground: Color = AppTheme.secondaryTextColor,
        background: Color = AppTheme.tertiaryBackground
    ) -> some View {
        self
            .font(.caption2)
            .foregroundStyle(foreground)
            .padding(.horizontal, AppTheme.Spacing.sm)
            .padding(.vertical, AppTheme.Spacing.xs)
            .background(background)
            .clipShape(Capsule())
    }
}

// MARK: - 字体大小环境注入(P1-4)

/// 字体大小环境键:用于在视图树中注入用户在设置页选择的字体大小偏好,
/// 使 ChatView 的 MessageBubble / MarkdownText 等组件能响应"字体大小"设置。
/// 类型使用顶层 `FontSize`(与 SettingsView 的 @AppStorage("fontSize") 一致)。
private struct AppFontSizeEnvironmentKey: EnvironmentKey {
    static let defaultValue: FontSize = .medium
}

extension EnvironmentValues {
    /// 用户设置的字体大小偏好(默认 .medium)
    var appFontSize: FontSize {
        get { self[AppFontSizeEnvironmentKey.self] }
        set { self[AppFontSizeEnvironmentKey.self] = newValue }
    }
}

extension View {
    /// 注入字体大小偏好到环境,供子视图(如 MarkdownText)读取
    func appFontSize(_ size: FontSize) -> some View {
        environment(\.appFontSize, size)
    }
}
