import Foundation

/// Widget 相关常量
/// 此文件在主 App Target 中编译，供 WidgetUpdater 和 ChatView 等调用
/// Widget Extension Target 也有自己的副本
enum WidgetConstants {
    /// App Group ID — 主 App 和 Widget 共享数据的容器
    /// 需要在 Xcode 中为两个 Target 都添加此 App Group capability
    static let appGroupId = "group.com.agentcontrolcenter.app.ios"

    /// UserDefaults 中存储 Widget 数据的键
    enum StorageKey {
        static let lastMessage = "widget_last_message"
        static let sessionTitle = "widget_session_title"
        static let agentName = "widget_agent_name"
        static let isConfigured = "widget_configured"
    }

    /// Widget URL Scheme — 用于从 Widget 点击跳转到指定会话
    /// 格式: agentcontrolcenter://open-session?sessionId=xxx
    static let urlScheme = "agentcontrolcenter"
    static let hostOpenSession = "open-session"
    static let querySessionId = "sessionId"
}
