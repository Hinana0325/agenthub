import Foundation
import Observation

// MARK: - AppState
// 对应 Android com.agentcontrolcenter.app.di.* (Hilt 依赖注入容器)

/// 应用全局状态 — 中央依赖容器。
///
/// 聚合应用运行所需的全部核心服务，通过 `@Observable` 暴露给 SwiftUI 视图树。
/// 子视图通过 `@Environment(AppState.self)` 获取依赖，无需手动传递。
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

    /// 创建应用状态并初始化全部依赖
    init() {
        dataController = DataController()
        agentManager = AgentManager()
        sessionManager = SessionManager()
        taskManager = TaskManager()
        workflowEngine = WorkflowEngine()
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
    }
}
