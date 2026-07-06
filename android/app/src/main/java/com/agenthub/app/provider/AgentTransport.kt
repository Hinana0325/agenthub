package com.agenthub.app.provider

import com.agenthub.app.data.model.AgentConfig
import com.agenthub.app.data.model.AgentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一的 Agent 传输层契约。
 *
 * 不同 [AgentType] 由 [TransportFactory] 路由到具体实现：
 *  - [WebSocketTransport]  : Hermes / OpenClaw / OpenCode（WebSocket 协议）
 *  - [OpenAIHttpTransport] : OpenAI / OpenRouter / Ollama / LM Studio / Xiaomi MiMo（HTTP + SSE）
 */
sealed interface AgentTransport {
    val events: Flow<AgentEvent>
    val connectionState: StateFlow<AgentConnectionState>
    fun connect(config: AgentConfig, e2eKey: String? = null)
    suspend fun sendMessage(sessionId: String, content: String)
    fun disconnect()
}

sealed class AgentEvent {
    data class Connected(val serverUrl: String, val agentType: AgentType) : AgentEvent()
    data class Disconnected(val reason: String = "") : AgentEvent()
    data class MessageReceived(val content: String, val isDelta: Boolean = false) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data object Reconnecting : AgentEvent()
}

data class AgentConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0
)
