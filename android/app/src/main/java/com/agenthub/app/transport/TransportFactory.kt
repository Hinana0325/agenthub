package com.agenthub.app.transport

import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.transport.http.OpenAIHttpTransport
import com.agenthub.app.transport.protocol.AgentTransport
import com.agenthub.app.transport.websocket.WebSocketTransport

/**
 * 按 [AgentType] 路由到具体的传输实现。
 *
 * - Hermes / OpenClaw / OpenCode → [WebSocketTransport]（WebSocket 协议）
 * - OpenAI / XiaomiMiMo / LocalModel → [OpenAIHttpTransport]（HTTP + SSE，覆盖 Ollama / LM Studio 等）
 */
object TransportFactory {
    fun create(type: AgentType): AgentTransport = when (type) {
        AgentType.Hermes,
        AgentType.OpenClaw,
        AgentType.OpenCode -> WebSocketTransport()

        AgentType.OpenAI,
        AgentType.XiaomiMiMo,
        AgentType.LocalModel -> OpenAIHttpTransport()
    }
}
