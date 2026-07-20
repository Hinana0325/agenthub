import SwiftUI

/// 全局主题定义
/// 集中管理颜色、状态色映射及时间格式化工具
enum AppTheme {
    // 主色调
    static let primaryColor = Color.blue
    // 背景色
    static let backgroundColor = Color(.systemBackground)
    static let secondaryBackground = Color(.secondarySystemBackground)

    // 聊天气泡颜色
    static let userBubbleColor = Color.blue
    static let assistantBubbleColor = Color(.secondarySystemBackground)

    // Agent 状态 -> 颜色映射(绿/灰/黄/红)
    static let statusColors: [AgentStatus: Color] = [
        .online: .green, .offline: .gray, .connecting: .yellow, .error: .red
    ]

    // 任务状态 -> 颜色映射(灰/蓝/绿/红/橙)
    static let taskStatusColors: [TaskStatus: Color] = [
        .pending: .gray, .running: .blue, .completed: .green,
        .failed: .red, .cancelled: .orange
    ]

    /// 将毫秒时间戳格式化为"几分钟前 / 几小时前"等相对时间字符串
    /// - Parameter timestamp: 毫秒级时间戳
    /// - Returns: 本地化的相对时间描述
    static func timeAgo(_ timestamp: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
