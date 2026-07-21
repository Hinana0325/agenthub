package com.agentcontrolcenter.app.transport

import android.content.Context
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.transport.http.OpenAIHttpTransport
import com.agentcontrolcenter.app.transport.protocol.AgentTransport
import com.agentcontrolcenter.app.transport.websocket.WebSocketTransport
import dagger.hilt.android.qualifiers.ApplicationContext
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
class TransportFactory @Inject constructor(
    @ApplicationContext private val context: Context?
) {
    fun create(type: AgentType): AgentTransport = when (type) {
        AgentType.Hermes,
        AgentType.OpenClaw,
        AgentType.OpenCode -> WebSocketTransport()

        AgentType.OpenAI,
        AgentType.XiaomiMiMo,
        AgentType.LocalModel -> OpenAIHttpTransport(
            context = context,
            // J4: 证书锁定默认关闭。待 CertificatePinnerFactory 中填入实际 pin
            // 值后，可改为从 AgentConfig / 用户设置读取以按需启用。
            enableCertificatePinning = false
        )
    }
}
