import Foundation
import Observation

/// 智能通知管理器 — 基于 Agent 消息内容匹配优先级并过滤通知
/// 对应 Android SmartNotificationManager
@Observable
final class SmartNotificationManager {

    // MARK: - 优先级

    /// 消息通知优先级
    enum Priority {
        case high      // 重要：错误、警告、紧急请求
        case medium    // 一般：普通消息
        case low       // 低优：心跳、确认回复
    }

    // MARK: - 通知配置

    /// 用户可配置的通知过滤规则
    ///
    /// 修复 6: 原 `NotificationConfig` 是纯内存结构，`config` 字段只在 Manager 实例化时
    /// 用默认值初始化一次。SettingsView 的 `notifyHighPriority/Medium/Low` 三个 Toggle
    /// 写到 UserDefaults 后没有任何代码读取，形成"死 UI"。
    /// 修正：在 `config` 的 `didSet` 中持久化到 UserDefaults，初始化时从 UserDefaults
    /// 读取。SettingsView 直接绑定到 `appState.notificationManager.config` 的字段。
    struct NotificationConfig {
        /// 是否启用高优先级通知（默认开启）
        var highPriorityEnabled: Bool = true
        /// 是否启用中优先级通知（默认开启）
        var mediumPriorityEnabled: Bool = true
        /// 是否启用低优先级通知（默认关闭）
        var lowPriorityEnabled: Bool = false
        /// 是否启用免打扰时段（默认关闭）
        var quietHoursEnabled: Bool = false
        /// 免打扰开始时间（小时，24 小时制，默认 23 点）
        var quietHoursStart: Int = 23
        /// 免打扰结束时间（小时，24 小时制，默认 8 点）
        var quietHoursEnd: Int = 8

        // 修复 6: UserDefaults 键常量
        static let highPriorityKey = "notifyHighPriority"
        static let mediumPriorityKey = "notifyMediumPriority"
        static let lowPriorityKey = "notifyLowPriority"

        /// 从 UserDefaults 读取并构建配置（缺失键时使用结构体默认值）
        static func loadFromUserDefaults() -> NotificationConfig {
            var config = NotificationConfig()
            let defaults = UserDefaults.standard
            // 用 `object(forKey:)` 区分"未设置"与"显式 false"
            if defaults.object(forKey: highPriorityKey) != nil {
                config.highPriorityEnabled = defaults.bool(forKey: highPriorityKey)
            }
            if defaults.object(forKey: mediumPriorityKey) != nil {
                config.mediumPriorityEnabled = defaults.bool(forKey: mediumPriorityKey)
            }
            if defaults.object(forKey: lowPriorityKey) != nil {
                config.lowPriorityEnabled = defaults.bool(forKey: lowPriorityKey)
            }
            return config
        }

        /// 将优先级开关写入 UserDefaults（免打扰时段保留为内存配置，UI 未暴露）
        func savePriorityToUserDefaults() {
            let defaults = UserDefaults.standard
            defaults.set(highPriorityEnabled, forKey: Self.highPriorityKey)
            defaults.set(mediumPriorityEnabled, forKey: Self.mediumPriorityKey)
            defaults.set(lowPriorityEnabled, forKey: Self.lowPriorityKey)
        }
    }

    // MARK: - 属性

    /// 当前通知配置
    ///
    /// 修复 6: 原 `var config = NotificationConfig()` 是纯内存默认值，SettingsView
    /// 的 `notifyHighPriority/Medium/Low` 三个 Toggle 写到 UserDefaults 后无人读取。
    /// 修正：初始化时从 UserDefaults 读取，并提供 `updateConfig(_:)` 方法在修改后
    /// 持久化。不能用 `didSet` — `@Observable` 宏会把存储属性转为计算属性，
    /// `didSet` 不会触发（参考 Apple Forums thread/731113）。
    var config: NotificationConfig = NotificationConfig.loadFromUserDefaults()

    /// 更新通知配置并持久化到 UserDefaults。
    /// SettingsView 通过此方法修改 config，避免直接赋值触发 didSet（不会触发）。
    func updateConfig(_ update: (inout NotificationConfig) -> Void) {
        update(&config)
        config.savePriorityToUserDefaults()
    }

    // MARK: - 正则规则
    //
    // CI-fix: 原使用 Swift Regex 字面量 `/\b...\b/i`，在 Xcode 16.4 / Swift 6 strict
    // concurrency 下有两个问题：
    // 1. Regex 字面量解析报 "consecutive declarations on a line must be separated by ';'"
    //    （特定 Swift 版本对带 flag 的字面量解析异常）
    // 2. `Regex<Substring>` 非 Sendable，`static let` 触发 "is not concurrency-safe"
    // 改为 `try! Regex("...").ignoresCase()` 初始化器形式，并用 `nonisolated(unsafe)`
    // 标注（Regex 实例本身线程安全，仅类型未标注 Sendable）。

    /// 高优先级规则 1：错误 / 异常 / 失败 / 崩溃
    nonisolated(unsafe) private static let highPriorityErrorPattern = try! Regex(#"\b(?:error|exception|failed|crash)\b"#).ignoresCase()

    /// 高优先级规则 2：警告
    nonisolated(unsafe) private static let highPriorityWarningPattern = try! Regex(#"\b(?:warning|warn)\b"#).ignoresCase()

    /// 高优先级规则 3：紧急请求
    nonisolated(unsafe) private static let highPriorityUrgentPattern = try! Regex(#"\b(?:please|need|require|urgent)\b"#).ignoresCase()

    /// 低优先级规则 1：心跳 / 保活
    nonisolated(unsafe) private static let lowPriorityHeartbeatPattern = try! Regex(#"\b(?:heartbeat|ping|alive)\b"#).ignoresCase()

    /// 低优先级规则 2：完成确认
    nonisolated(unsafe) private static let lowPriorityDonePattern = try! Regex(#"\b(?:ok|done|finished)\b"#).ignoresCase()

    // MARK: - 优先级判断

    /// 根据消息内容判断通知优先级。
    ///
    /// 匹配顺序：高优先级规则 > 低优先级规则 > 默认 medium。
    /// - Parameter message: 聊天消息
    /// - Returns: 消息优先级
    func shouldNotify(message: Message) -> Priority {
        let content = message.content

        // 高优先级：错误 / 异常 / 失败 / 崩溃
        if content.firstMatch(of: Self.highPriorityErrorPattern) != nil {
            return .high
        }

        // 高优先级：警告
        if content.firstMatch(of: Self.highPriorityWarningPattern) != nil {
            return .high
        }

        // 高优先级：紧急请求
        if content.firstMatch(of: Self.highPriorityUrgentPattern) != nil {
            return .high
        }

        // 低优先级：心跳 / 保活
        if content.firstMatch(of: Self.lowPriorityHeartbeatPattern) != nil {
            return .low
        }

        // 低优先级：完成确认
        if content.firstMatch(of: Self.lowPriorityDonePattern) != nil {
            return .low
        }

        // 默认中等优先级
        return .medium
    }

    /// 综合判断是否应展示系统通知。
    ///
    /// 依次检查：
    /// 1. 仅处理 assistant / system 角色的消息（忽略用户自己发送的）
    /// 2. 当前优先级是否启用
    /// 3. 是否处于免打扰时段
    /// - Parameter message: 聊天消息
    /// - Returns: 是否应展示通知
    func shouldShowNotification(message: Message) -> Bool {
        // 只对 assistant 和 system 消息发送通知
        guard message.role == .assistant || message.role == .system else {
            return false
        }

        let priority = shouldNotify(message: message)

        // 根据配置过滤优先级
        switch priority {
        case .high:
            guard config.highPriorityEnabled else { return false }
        case .medium:
            guard config.mediumPriorityEnabled else { return false }
        case .low:
            guard config.lowPriorityEnabled else { return false }
        }

        // 免打扰时段判断
        if config.quietHoursEnabled && isInQuietHours() {
            return false
        }

        return true
    }

    /// 生成通知标题。
    ///
    /// 根据消息角色返回对应的前缀，方便用户在通知中心快速识别来源。
    /// - Parameter message: 聊天消息
    /// - Returns: 通知标题字符串
    func getNotificationTitle(message: Message) -> String {
        switch message.role {
        case .assistant:
            return "Agent 消息"
        case .system:
            return "系统通知"
        case .tool:
            return "工具执行"
        case .user:
            return "我的消息"
        }
    }

    // MARK: - 私有方法

    /// 判断当前时间是否处于免打扰时段。
    ///
    /// 支持跨午夜场景（如 23:00 → 8:00）。
    /// - Returns: 是否在免打扰时段内
    private func isInQuietHours() -> Bool {
        let calendar = Calendar.current
        let now = calendar.component(.hour, from: Date())
        let start = config.quietHoursStart
        let end = config.quietHoursEnd

        if start < end {
            // 不跨午夜：如 22:00 → 6:00 不在此分支
            // 同日判断：start <= now < end
            return now >= start && now < end
        } else {
            // 跨午夜：如 23:00 → 8:00
            // now >= 23 或 now < 8
            return now >= start || now < end
        }
    }
}
