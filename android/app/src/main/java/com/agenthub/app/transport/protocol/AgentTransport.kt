package com.agenthub.app.transport.protocol

import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.agent.model.AgentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一的 Agent 传输层契约。
 *
 * 不同 [AgentType] 由 [TransportFactory] 路由到具体实现：
 *  - [WebSocketTransport]  : Hermes / OpenClaw / OpenCode（WebSocket 协议）
 *  - [OpenAIHttpTransport] : OpenAI / OpenRouter / Ollama / LM Studio / Xiaomi MiMo（HTTP + SSE）
 *
 * 多轮对话历史管理：
 *  - WebSocket 传输：服务端通过 `sessionId` 维护会话状态，客户端仅需在每条消息中
 *    携带正确的 `sessionId` 即可。客户端侧的 [clearHistory] / [clearAllHistory]
 *    主要用于清空本地展示用的消息缓存（如有），不会影响服务端的会话状态。
 *  - HTTP 传输：服务端无状态，多轮上下文需由客户端维护并随请求一起发送。
 *    [clearHistory] / [clearAllHistory] 用于清空客户端侧维护的会话历史。
 */
sealed interface AgentTransport {
    val events: Flow<AgentEvent>
    val connectionState: StateFlow<AgentConnectionState>
    fun connect(config: AgentConfig, e2eKey: String? = null)
    suspend fun sendMessage(sessionId: String, content: String)
    fun disconnect()

    /**
     * 清空指定 [sessionId] 的本地会话历史。
     *
     * 对于 WebSocket 传输：仅清空客户端侧用于展示的本地消息缓存，
     * 服务端的会话状态由 `sessionId` 标识并独立维护，不受此调用影响。
     * 如需重置服务端会话，应使用一个新的 `sessionId`。
     *
     * 对于 HTTP 传输：清空客户端侧维护的会话历史，后续请求将不再携带该会话的上下文。
     *
     * 默认实现为空操作，兼容不需要历史管理的传输实现。
     *
     * 注：声明为 `suspend` 以便客户端维护历史的传输（如 OpenAIHttpTransport）
     * 可以在锁内安全地修改共享历史结构。
     */
    suspend fun clearHistory(sessionId: String) { }

    /**
     * 清空所有会话的本地历史。
     *
     * 语义同 [clearHistory]，但作用于当前传输实例持有的全部会话。
     * 默认实现为空操作。
     */
    suspend fun clearAllHistory() { }
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
