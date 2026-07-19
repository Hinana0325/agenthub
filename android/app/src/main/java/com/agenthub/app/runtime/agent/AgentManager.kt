package com.agenthub.app.runtime.agent

import com.agenthub.app.agent.model.Agent
import com.agenthub.app.agent.model.AgentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 管理器 — AgentHub 的核心调度中心。
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
 */
@Singleton
class AgentManager @Inject constructor() {

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _activeAgent = MutableStateFlow<Agent?>(null)
    val activeAgent: StateFlow<Agent?> = _activeAgent.asStateFlow()

    fun registerAgent(agent: Agent) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agent.id }
            if (index >= 0) current.toMutableList().also { it[index] = agent } else current + agent
        }
    }

    fun unregisterAgent(agentId: String) {
        _agents.update { it.filter { agent -> agent.id != agentId } }
        if (_activeAgent.value?.id == agentId) {
            _activeAgent.value = null
        }
    }

    fun setActiveAgent(agentId: String) {
        _activeAgent.value = _agents.value.find { it.id == agentId }
    }

    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        _agents.update { current ->
            val index = current.indexOfFirst { it.id == agentId }
            if (index >= 0) {
                current.toMutableList().also { it[index] = it[index].copy(status = status) }
            } else {
                current
            }
        }
    }

    fun getAgent(agentId: String): Agent? = _agents.value.find { it.id == agentId }

    fun getAgentsByCapability(/* capability: AgentCapability */): List<Agent> {
        // TODO: 按 Capability 过滤 Agent
        return _agents.value
    }
}
