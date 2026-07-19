package com.agenthub.app.transport

import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.transport.http.OpenAIHttpTransport
import com.agenthub.app.transport.protocol.AgentTransport
import com.agenthub.app.transport.websocket.WebSocketTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按 [AgentType] 路由到具体的传输实现。
 *
 * - Hermes / OpenClaw / OpenCode → [WebSocketTransport]（WebSocket 协议）
 * - OpenAI / XiaomiMiMo / LocalModel → [OpenAIHttpTransport]（HTTP + SSE，覆盖 Ollama / LM Studio 等）
 *
 * Phase 3.3: 从 `object` 改为 `@Singleton class`，通过 Hilt 注入，
 * 便于测试时替换为 mock 实现。
 */
@Singleton
class TransportFactory @Inject constructor() {
    fun create(type: AgentType): AgentTransport = when (type) {
        AgentType.Hermes,
        AgentType.OpenClaw,
        AgentType.OpenCode -> WebSocketTransport()

        AgentType.OpenAI,
        AgentType.XiaomiMiMo,
        AgentType.LocalModel -> OpenAIHttpTransport()
    }
}
