import AppIntents
import Foundation

// MARK: - P3-5: iOS App Intents / 快捷方式（Shortcuts & Siri）

/// 通知名称常量 — 各 AppIntent 在 `perform()` 中通过 `NotificationCenter` 发布，
/// 由 `IntentRouter` 监听并路由到对应页面。
///
/// 使用 Notification 而非直接修改 AppState 的原因：
/// 1. AppIntent 运行在独立的作用域中，不持有 AppState 引用
/// 2. NotificationCenter 提供松耦合的事件传递，便于测试与扩展
/// 3. IntentRouter 负责将通知转换为 AppState 上的可观察属性变更
enum ShortcutIntentNotification {
    /// 新建聊天
    static let newChat = Notification.Name("com.agentcontrolcenter.app.shortcut.newChat")
    /// 新建 Agent
    static let newAgent = Notification.Name("com.agentcontrolcenter.app.shortcut.newAgent")
    /// 打开设置
    static let openSettings = Notification.Name("com.agentcontrolcenter.app.shortcut.openSettings")
}

// MARK: - NewChatIntent

/// 启动应用并打开新聊天。
///
/// 通过 Siri 语音或 Shortcuts 应用触发时：
/// 1. 系统将应用拉到前台（`openAppWhenRun = true`）
/// 2. `perform()` 通过 `NotificationCenter` 发布 [ShortcutIntentNotification.newChat]
/// 3. `IntentRouter` 接收通知并设置 `AppState.pendingShortcutDestination = .newChat`
/// 4. `ContentView` 观察到变更后导航到会话页面
@available(iOS 16, *)
struct NewChatIntent: AppIntent {

    /// 在 Shortcuts / Siri 中显示的标题
    static var title: LocalizedStringResource = "新对话"

    /// 详细描述，显示在 Shortcuts 应用的意图详情中
    static var description = IntentDescription("启动 Agent Control Center 并打开新聊天")

    /// 执行意图时是否将应用拉到前台 — 导航类意图必须为 `true`
    static var openAppWhenRun: Bool = true

    /// 执行意图：发布通知并由 IntentRouter 路由。
    ///
    /// 同时将目标写入 `ShortcutRelay.shared`（actor）暂存，
    /// 确保应用冷启动时（IntentRouter 尚未开始监听）目标不会丢失。
    func perform() async throws -> some IntentResult {
        await ShortcutRelay.shared.setPending(.newChat)
        NotificationCenter.default.post(name: ShortcutIntentNotification.newChat, object: nil)
        return .result()
    }
}

// MARK: - NewAgentIntent

/// 启动应用并打开 Agent 创建页面。
///
/// 通过 Siri 语音或 Shortcuts 应用触发时：
/// 1. 系统将应用拉到前台
/// 2. `perform()` 发布 [ShortcutIntentNotification.newAgent]
/// 3. `IntentRouter` 设置 `AppState.pendingShortcutDestination = .newAgent`
/// 4. `ContentView` 导航到 Agent 页面
@available(iOS 16, *)
struct NewAgentIntent: AppIntent {

    /// 在 Shortcuts / Siri 中显示的标题
    static var title: LocalizedStringResource = "新 Agent"

    /// 详细描述
    static var description = IntentDescription("启动 Agent Control Center 并打开 Agent 创建页面")

    /// 执行意图时是否将应用拉到前台
    static var openAppWhenRun: Bool = true

    /// 执行意图：发布通知并由 IntentRouter 路由。
    func perform() async throws -> some IntentResult {
        await ShortcutRelay.shared.setPending(.newAgent)
        NotificationCenter.default.post(name: ShortcutIntentNotification.newAgent, object: nil)
        return .result()
    }
}

// MARK: - OpenSettingsIntent

/// 启动应用并打开设置。
///
/// 通过 Siri 语音或 Shortcuts 应用触发时：
/// 1. 系统将应用拉到前台
/// 2. `perform()` 发布 [ShortcutIntentNotification.openSettings]
/// 3. `IntentRouter` 设置 `AppState.pendingShortcutDestination = .settings`
/// 4. `ContentView` 导航到设置页面
@available(iOS 16, *)
struct OpenSettingsIntent: AppIntent {

    /// 在 Shortcuts / Siri 中显示的标题
    static var title: LocalizedStringResource = "设置"

    /// 详细描述
    static var description = IntentDescription("启动 Agent Control Center 并打开设置")

    /// 执行意图时是否将应用拉到前台
    static var openAppWhenRun: Bool = true

    /// 执行意图：发布通知并由 IntentRouter 路由。
    func perform() async throws -> some IntentResult {
        await ShortcutRelay.shared.setPending(.settings)
        NotificationCenter.default.post(name: ShortcutIntentNotification.openSettings, object: nil)
        return .result()
    }
}

// MARK: - AppShortcutsProvider

/// App Shortcuts 提供者 — 向系统声明应用支持的全部快捷方式。
///
/// 使用 `AppShortcutsProvider` 协议配合 `@AppShortcutsBuilder` 结果构建器宏
/// 声明快捷方式。声明后，用户无需手动在 Shortcuts 应用中配置即可：
/// - 在主屏幕长按应用图标看到快捷方式
/// - 通过 Siri 语音短语触发
/// - 在 Spotlight / 锁屏建议中出现
///
/// 每个 `AppShortcut` 包含：
/// - `intent`: 对应的 AppIntent 实例
/// - `phrases`: Siri 语音触发短语（`\(.applicationName)` 会被替换为应用名）
/// - `shortTitle`: 短标题（显示在快捷方式列表中）
/// - `systemImageName`: SF Symbol 图标名
@available(iOS 16, *)
struct AgentControlCenterShortcuts: AppShortcutsProvider {

    /// 通过 `@AppShortcutsBuilder` 结果构建器声明全部快捷方式
    @AppShortcutsBuilder
    static var appShortcuts: [AppShortcut] {
        // 新对话 — 新建聊天
        AppShortcut(
            intent: NewChatIntent(),
            phrases: [
                "在 \(.applicationName) 中新建聊天",
                "新对话 \(.applicationName)",
                "New chat in \(.applicationName)"
            ],
            shortTitle: "新对话",
            systemImageName: "plus.circle"
        )

        // 新 Agent — 新建 Agent
        AppShortcut(
            intent: NewAgentIntent(),
            phrases: [
                "在 \(.applicationName) 中新建 Agent",
                "新 Agent \(.applicationName)",
                "New agent in \(.applicationName)"
            ],
            shortTitle: "新 Agent",
            systemImageName: "plus.circle"
        )

        // 设置 — 打开设置
        AppShortcut(
            intent: OpenSettingsIntent(),
            phrases: [
                "打开 \(.applicationName) 设置",
                "设置 \(.applicationName)",
                "Open \(.applicationName) settings"
            ],
            shortTitle: "设置",
            systemImageName: "gear"
        )
    }
}
