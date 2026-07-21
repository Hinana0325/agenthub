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
    }

    // MARK: - 属性

    /// 当前通知配置
    var config: NotificationConfig = NotificationConfig()

    // MARK: - 正则规则
    //
    // 使用 Swift Regex 字面量（编译期检查，无需 try!），case-insensitive 通过 `i` 标志开启。
    // 字面量在首次访问时由 Swift 运行时编译并缓存，性能与 NSRegularExpression 相当。

    /// 高优先级规则 1：错误 / 异常 / 失败 / 崩溃
    private static let highPriorityErrorPattern = /\b(?:error|exception|failed|crash)\b/i

    /// 高优先级规则 2：警告
    private static let highPriorityWarningPattern = /\b(?:warning|warn)\b/i

    /// 高优先级规则 3：紧急请求
    private static let highPriorityUrgentPattern = /\b(?:please|need|require|urgent)\b/i

    /// 低优先级规则 1：心跳 / 保活
    private static let lowPriorityHeartbeatPattern = /\b(?:heartbeat|ping|alive)\b/i

    /// 低优先级规则 2：完成确认
    private static let lowPriorityDonePattern = /\b(?:ok|done|finished)\b/i

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
