package com.agentcontrolcenter.app.runtime.agent

import com.agentcontrolcenter.app.agent.model.Agent
import com.agentcontrolcenter.app.agent.model.AgentCapability
import com.agentcontrolcenter.app.agent.model.AgentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 管理器 — Agent Control Center 的核心调度中心。
 *
 * 职责：
 * - 维护所有已注册 Agent 的生命周期
 * - 提供 Agent 状态的响应式流
 * - 协调 Transport 层与 Agent 实例的绑定
 *
 * 设计理念：Agent 是第一公民，不再以 Chat 为中心。
 * User → Agent → Chat/Task/Workflow/Tool/Memory
 *
 * 线程安全：@Singleton 会被多协程并发访问，所有 StateFlow 写入均通过
 * `update { }` 原子完成，避免「读-改-写」竞争丢失并发更新。
 *
 * Phase 4.3: 注入 [AgentRegistry]，registerAgent/unregisterAgent 同步到
 * Registry 以支持按 Capability 查询。getAgentsByCapability 委托给 Registry。
 */
@Singleton
class AgentManager @Inject constructor(
    private val registry: AgentRegistry
) {

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _activeAgent = MutableStateFlow<Agent?>(null)
    val activeAgent: StateFlow<Agent?> = _activeAgent.asStateFlow()

    fun registerAgent(agent: Agent) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) current.toMutableList().also { it[index] = agent } else current + agent
        }
        // Phase 4.3: 同步到 Registry，支持按 Capability 查询
        registry.register(agent)
    }

    fun unregisterAgent(agentId: String) {
        _agents.update { it.filter { agent -> agent.id != agentId } }
        if (_activeAgent.value?.id == agentId) {
            _activeAgent.value = null
        }
        // Phase 4.3: 同步到 Registry
        registry.unregister(agentId)
    }

    fun setActiveAgent(agentId: String) {
        _activeAgent.value = _agents.value.find { it.id == agentId }
    }

    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        var updatedAgent: Agent? = null
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agentId }
            if (index >= 0) {
                val updated = current[index].copy(status = status)
                updatedAgent = updated
                current.toMutableList().also { it[index] = updated }
            } else {
                current
            }
        }

        // Phase 4.3: 同步状态变更到 Registry
        updatedAgent?.let { registry.register(it) }
    }

    fun getAgent(agentId: String): Agent? = _agents.value.find { it.id == agentId }

    /**
     * Phase 4.3: 按 [capability] 过滤 Agent，委托给 [AgentRegistry]。
     * 若 Registry 中无数据（未注册），回退到内存列表的 capabilities 过滤。
     */
    fun getAgentsByCapability(capability: AgentCapability): List<Agent> {
        val fromRegistry = registry.getByCapability(capability)
        if (fromRegistry.isNotEmpty()) return fromRegistry
        // 回退：直接过滤内存列表
        return _agents.value.filter { it.capabilities.contains(capability) }
    }
}
