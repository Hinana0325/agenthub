import Foundation
import Observation

// MARK: - AppState
// 对应 Android com.agentcontrolcenter.app.di.* (Hilt 依赖注入容器)

/// 应用全局状态 — 中央依赖容器。
///
/// 聚合应用运行所需的全部核心服务，通过 `@Observable` 暴露给 SwiftUI 视图树。
/// 子视图通过 `@Environment(AppState.self)` 获取依赖，无需手动传递。
///
/// 职责类似 Android 端的 Hilt `@Singleton` 组件：
/// - `dataController`: 持久化层（SwiftData CRUD）
/// - `agentManager`: Agent 注册与生命周期管理
/// - `sessionManager`: 会话管理（创建 / 切换 / 置顶）
/// - `taskManager`: 异步任务管理
/// - `workflowEngine`: 多节点 DAG 工作流引擎
/// - `mcpBridge`: MCP 协议桥接（工具调用）
/// - `pluginExecutor`: 插件动作执行
///
/// 各服务以 `let` 引用持有，构造后不可替换（与 Hilt 单例语义一致）；
/// 服务内部的响应式状态由各自 `@Observable` 实现驱动 UI 刷新。
@Observable
final class AppState {

    /// 持久化控制器（SwiftData）
    let dataController: DataController

    /// Agent 管理器 — Agent 注册 / 状态 / 能力索引
    let agentManager: AgentManager

    /// 会话管理器 — 会话生命周期与列表状态
    let sessionManager: SessionManager

    /// 任务管理器 — 异步任务提交与状态追踪
    let taskManager: TaskManager

    /// 工作流引擎 — 多节点 DAG 编排
    let workflowEngine: WorkflowEngine

    /// MCP 桥接层 — Agent 工具调用与 MCP Server 协调
    let mcpBridge: McpBridge

    /// 插件执行器 — HTTP / 广播 / 工作流动作执行
    let pluginExecutor: PluginExecutor

    /// 创建应用状态并初始化全部依赖。
    ///
    /// 各服务均使用默认构造器创建；`McpBridge` 内部默认装配 `McpRegistry` 与 `McpClient`。
    /// 初始化顺序无隐式依赖（当前各服务互不注入），后续若需协作可在此处接线。
    init() {
        dataController = DataController()
        agentManager = AgentManager()
        sessionManager = SessionManager()
        taskManager = TaskManager()
        workflowEngine = WorkflowEngine()
        mcpBridge = McpBridge()
        pluginExecutor = PluginExecutor()
    }
}
