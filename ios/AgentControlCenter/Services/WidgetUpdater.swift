import Foundation
import WidgetKit

/// Widget 更新辅助
/// 在主 App 中调用此方法刷新桌面 Widget 显示内容
/// 对应 Android WidgetDataProvider.notifyWidgetUpdate()
enum WidgetUpdater {

    /// 更新 Widget 显示的最近消息
    /// - Parameters:
    ///   - lastMessage: 最近一条消息内容
    ///   - sessionTitle: 会话标题
    ///   - agentName: Agent 名称
    static func updateWidget(
        lastMessage: String,
        sessionTitle: String,
        agentName: String
    ) {
        let defaults = UserDefaults(suiteName: WidgetConstants.appGroupId)
        defaults?.set(true, forKey: WidgetConstants.StorageKey.isConfigured)
        defaults?.set(lastMessage, forKey: WidgetConstants.StorageKey.lastMessage)
        defaults?.set(sessionTitle, forKey: WidgetConstants.StorageKey.sessionTitle)
        defaults?.set(agentName, forKey: WidgetConstants.StorageKey.agentName)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// 清除 Widget 显示内容
    static func clearWidget() {
        let defaults = UserDefaults(suiteName: WidgetConstants.appGroupId)
        defaults?.removeObject(forKey: WidgetConstants.StorageKey.lastMessage)
        defaults?.removeObject(forKey: WidgetConstants.StorageKey.sessionTitle)
        defaults?.removeObject(forKey: WidgetConstants.StorageKey.agentName)
        defaults?.set(false, forKey: WidgetConstants.StorageKey.isConfigured)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
