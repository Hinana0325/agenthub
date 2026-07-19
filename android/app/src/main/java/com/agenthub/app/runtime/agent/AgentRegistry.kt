package com.agenthub.app.runtime.agent

import com.agenthub.app.agent.model.Agent
import com.agenthub.app.agent.model.AgentCapability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 注册表 — 按 ID 和 Capability 索引 Agent 实例。
 *
 * 与 [AgentManager] 的区别：
 * - AgentManager 管理生命周期和状态
 * - AgentRegistry 提供查询能力（按 ID、按 Capability）
 */
@Singleton
class AgentRegistry @Inject constructor() {

    private val agentsById = mutableMapOf<String, Agent>()
    private val agentsByCapability = mutableMapOf<AgentCapability, MutableList<String>>()

    fun register(agent: Agent) {
        agentsById[agent.id] = agent
        agent.capabilities.forEach { cap ->
            agentsByCapability.getOrPut(cap) { mutableListOf() }.add(agent.id)
        }
    }

    fun unregister(agentId: String) {
        val agent = agentsById.remove(agentId) ?: return
        agent.capabilities.forEach { cap ->
            agentsByCapability[cap]?.remove(agentId)
        }
    }

    fun getById(agentId: String): Agent? = agentsById[agentId]

    fun getByCapability(capability: AgentCapability): List<Agent> {
        val ids = agentsByCapability[capability] ?: return emptyList()
        return ids.mapNotNull { agentsById[it] }
    }

    fun getAll(): List<Agent> = agentsById.values.toList()

    fun clear() {
        agentsById.clear()
        agentsByCapability.clear()
    }
}
