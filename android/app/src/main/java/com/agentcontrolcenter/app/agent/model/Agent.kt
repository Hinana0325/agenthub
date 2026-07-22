package com.agentcontrolcenter.app.agent.model

/**
 * Agent — Agent Control Center 的第一公民。
 *
 * 统一模型：不再区分 OpenCode / Claude / OpenAI / Ollama，
 * 只看 [capabilities]。UI 和 Runtime 均通过能力集调度，
 * 而非硬编码 Agent 类型。
 *
 * 定位：Agent Control Center 是统一连接、管理和调度本地与远程 AI Agent 的移动控制中心。
 *
 * @see AgentCapability
 * @see AgentStatus
 */
data class Agent(
    val id: String,
    val name: String,
    /** 连接端点（URL 或 host:port）。 */
    val endpoint: String = "",
    /** 当前连接状态。 */
    val status: AgentStatus = AgentStatus.Offline,
    /** 该 Agent 支持的能力集；UI 据此决定展示哪些功能入口。 */
    val capabilities: List<AgentCapability> = listOf(AgentCapability.CHAT),
    /** 传输协议类型，决定走 WebSocket / HTTP+SSE / MCP 等。 */
    val protocol: AgentProtocol = AgentProtocol.WebSocket,
    /** 关联的 AgentConfig（向后兼容，迁移期保留）。 */
    val config: AgentConfig? = null
)

/**
 * Agent 传输协议类型。定义已迁移至 [AgentProtocol.kt]（含 rawValue / fromRawValue，
 * 与 iOS AgentProtocol 跨端对齐）。此处不再重复声明，避免 Redeclaration 冲突。
 */

/**
 * Agent 当前状态。
 */
enum class AgentStatus(val displayName: String) {
    Online("Online"),
    Offline("Offline"),
    Connecting("Connecting"),
    Error("Error")
}

/**
 * Agent 能力集 — UI 不再判断 Agent 类型，只看能力。
 *
 * 例如：
 * - MacBook 上的 OpenCode → [CHAT, TASK, TERMINAL, FILESYSTEM]
 * - 服务器上的 OpenManus  → [CHAT, TASK, WORKFLOW]
 * - NAS 上的 Ollama       → [CHAT]
 * - Claude Code           → [CHAT, TASK, MCP]
 */
enum class AgentCapability(val displayName: String) {
    CHAT("Chat"),
    TASK("Task Execution"),
    WORKFLOW("Workflow"),
    MCP("MCP Support"),
    FILESYSTEM("Filesystem"),
    TERMINAL("Terminal"),
    VOICE("Voice"),
    IMAGE_GEN("Image Generation"),
    CODE_EXECUTION("Code Execution")
}
