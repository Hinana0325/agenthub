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
        localModelManager = LocalModelManager()
        performanceMonitor = PerformanceMonitor()
        notificationManager = SmartNotificationManager()
        updateManager = UpdateManager()
        collaborationManager = CollaborationManager()
        deviceSyncManager = DeviceSyncManager()
    }
}
