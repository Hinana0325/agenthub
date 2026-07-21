import Foundation
import Observation

// MARK: - AppState
// 对应 Android com.agentcontrolcenter.app.di.* (Hilt 依赖注入容器)

/// 应用全局状态 — 中央依赖容器。
///
/// 聚合应用运行所需的全部核心服务，通过 `@Observable` 暴露给 SwiftUI 视图树。
/// 子视图通过 `@Environment(AppState.self)` 获取依赖，无需手动传递。
///
/// `@MainActor` 隔离保证 SwiftUI 视图树对 `AppState` 的读取均在主线程，
/// 避免视图更新时发生数据竞争。内部持有的 I/O 类型管理器
/// （McpClient / VoiceChatManager 等）仍可在自己的 actor / Task 中工作，
/// 仅为 AppState 自身属性访问加主线程约束。
@MainActor
@Observable
final class AppState {

    /// 持久化控制器（SwiftData）
    let dataController: DataController

    /// Agent 管理器
    let agentManager: AgentManager

    /// 会话管理器
    let sessionManager: SessionManager

    /// 任务管理器
    let taskManager: TaskManager

    /// 工作流引擎
    let workflowEngine: WorkflowEngine

    /// MCP 桥接层
    let mcpBridge: McpBridge

    /// 插件执行器
    let pluginExecutor: PluginExecutor

    /// 语音输入管理器
    let voiceInputManager: VoiceInputManager

    /// 语音聊天管理器（录音 / 回放 / 波形可视化）
    ///
    /// 与 `voiceInputManager` 区分：
    /// - `voiceInputManager` 基于 SFSpeechRecognizer，负责语音转文字
    /// - `voiceChatManager` 基于 AVAudioRecorder/AVAudioPlayer，负责录制语音消息与回放
    let voiceChatManager: VoiceChatManager

    /// 市场客户端（本地示例数据模拟 API）
    let marketplaceClient: MarketplaceClient

    /// 本地模型发现
    let localModelManager: LocalModelManager

    /// 性能监控
    let performanceMonitor: PerformanceMonitor

    /// 智能通知
    let notificationManager: SmartNotificationManager

    /// 更新检查
    let updateManager: UpdateManager

    /// 协作管理
    let collaborationManager: CollaborationManager

    /// 设备同步
    let deviceSyncManager: DeviceSyncManager

    /// 本地通知管理器（UserNotifications 执行层）
    let localNotificationManager: LocalNotificationManager

    /// 状态通知管理器（UI 内全局连接状态条）
    let statusNotificationManager: StatusNotificationManager

    /// 聊天数据仓库（文件持久化备份/导出/导入）
    let chatRepository: ChatRepository

    /// 数据分析管理器（聚合使用统计与洞察）
    let dataInsightsManager: DataInsightsManager

    /// P3-5: 待处理的快捷方式导航目标。
    ///
    /// 由 `IntentRouter` 在收到 App Intent 通知时设置，
    /// 由 `ContentView` 观察并消费（导航后清空）。
    ///
    /// - Note: 值为 `nil` 时表示无待处理请求；非 `nil` 时 ContentView 应立即导航并置回 `nil`
    var pendingShortcutDestination: AppShortcutDestination?

    /// 命令面板显示绑定。
    ///
    /// 由 App 级 `.commands { CommandMenu }` 中的 ⌘K 快捷键触发，
    /// 由 `ContentView` 观察并通过 `.sheet` 弹出 `CommandPaletteView`。
    /// 这样比之前在 ContentView 内部用隐藏 Button + keyboardShortcut 更符合 HIG：
    /// - 系统菜单栏会显示快捷键
    /// - VoiceOver 可访问
    /// - 不依赖透明零尺寸按钮 hack
    var showCommandPalette: Bool = false

    /// 云备份/恢复管理器（P3-3）
    ///
    /// 与 `chatRepository` 的区别：
    /// - `chatRepository` 是单会话级别的 JSON 导出/导入（`exportSession` / `importSession`）
    /// - `backupManager` 是应用级完整快照（sessions + messages + agentConfigs + settings），
    ///   并支持基于 `KeychainManager` 的硬件级加密备份
    let backupManager: BackupManager

    /// 隐私优先的本地埋点管理器（P3-1，对应 Android AnalyticsManager）
    ///
    /// 纯本地埋点，不集成第三方 SDK。事件写入内存 ring buffer（最多 1000 条），
    /// 可通过设置页导出为 JSON。用户可在设置页关闭埋点。
    let analyticsManager: AnalyticsManager

    /// Feature Flag 管理器（P3-2，对应 Android FeatureFlagManager）
    ///
    /// 为每个功能标志提供默认值（开发中的功能默认关闭），
    /// 支持用户通过设置页覆盖默认值，覆盖持久化到 UserDefaults。
    let featureFlagManager: FeatureFlagManager

    /// 创建应用状态并初始化全部依赖
    init() {
        dataController = DataController()
        agentManager = AgentManager()
        sessionManager = SessionManager()
        taskManager = TaskManager()
        // C9 修复：注入 dataController，让 WorkflowEngine 能解析 AGENT 节点配置。
        workflowEngine = WorkflowEngine(dataController: dataController)
        mcpBridge = McpBridge()
        pluginExecutor = PluginExecutor()
        voiceInputManager = VoiceInputManager()
        voiceChatManager = VoiceChatManager()
        marketplaceClient = MarketplaceClient()
        localModelManager = LocalModelManager()
        performanceMonitor = PerformanceMonitor()
        notificationManager = SmartNotificationManager()
        updateManager = UpdateManager()
        collaborationManager = CollaborationManager()
        deviceSyncManager = DeviceSyncManager()
        localNotificationManager = LocalNotificationManager()
        statusNotificationManager = StatusNotificationManager()
        chatRepository = ChatRepository()
        // DataInsightsManager 依赖 chatRepository / sessionManager / dataController
        dataInsightsManager = DataInsightsManager(
            chatRepository: chatRepository,
            sessionManager: sessionManager,
            dataController: dataController
        )
        // BackupManager 依赖 dataController
        backupManager = BackupManager(dataController: dataController)
        // P3-1 / P3-2: 埋点系统与 Feature Flag 系统
        analyticsManager = AnalyticsManager()
        featureFlagManager = FeatureFlagManager()
    }
}
