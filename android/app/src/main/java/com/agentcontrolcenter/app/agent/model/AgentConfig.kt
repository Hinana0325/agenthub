package com.agentcontrolcenter.app.agent.model

/**
 * Agent 连接配置 — 向后兼容的迁移模型。
 *
 * 原位于 com.agentcontrolcenter.app.data.model.AgentConfig，现迁移至
 * com.agentcontrolcenter.app.agent.model.AgentConfig，作为 [Agent] 的配置载体。
 *
 * 未来 [Agent] 将逐步取代本类的角色，过渡期两者共存。
 */
data class AgentConfig(
    val id: String = "default",
    val name: String = "Default Agent",
    val type: AgentType = AgentType.Hermes,
    val serverUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    // 跨端 schema 对齐：与 iOS AgentConfig.protocolType 字段一致
    // （AgentProtocol.rawValue：WebSocket / HttpSSE / MCP / Local）
    val protocolType: AgentProtocol = AgentProtocol.WebSocket
)

/**
 * Agent 类型枚举 — 用于 Transport 路由。
 *
 * 迁移说明：未来将逐步由 [AgentProtocol] + [AgentCapability] 取代，
 * 过渡期保留以兼容现有 TransportFactory 逻辑。
 */
enum class AgentType(val displayName: String) {
    Hermes("Hermes"),
    OpenCode("OpenCode"),
    OpenClaw("OpenClaw"),
    OpenAI("OpenAI Compatible"),
    XiaomiMiMo("Xiaomi MiMo"),
    LocalModel("Local Model")
}
