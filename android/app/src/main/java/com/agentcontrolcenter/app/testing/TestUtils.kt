package com.agentcontrolcenter.app.testing

import com.agentcontrolcenter.app.agent.model.Agent
import com.agentcontrolcenter.app.agent.model.AgentCapability
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentProtocol
import com.agentcontrolcenter.app.agent.model.AgentStatus
import com.agentcontrolcenter.app.agent.model.AgentType

/**
 * 测试工具 — 提供测试用的 Agent 和配置工厂方法。
 */
object TestUtils {

    fun createMockAgent(
        id: String = "test-agent",
        name: String = "Test Agent",
        capabilities: List<AgentCapability> = listOf(AgentCapability.CHAT),
        status: AgentStatus = AgentStatus.Online
    ): Agent = Agent(
        id = id,
        name = name,
        endpoint = "ws://localhost:8080",
        status = status,
        capabilities = capabilities,
        protocol = AgentProtocol.WebSocket
    )

    fun createMockConfig(
        id: String = "default",
        type: AgentType = AgentType.Hermes,
        serverUrl: String = "ws://localhost:8080"
    ): AgentConfig = AgentConfig(
        id = id,
        type = type,
        serverUrl = serverUrl
    )
}
