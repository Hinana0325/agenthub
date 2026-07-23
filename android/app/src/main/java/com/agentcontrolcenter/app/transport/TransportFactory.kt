package com.agentcontrolcenter.app.transport

import android.content.Context
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.transport.comfyui.ComfyUITransport
import com.agentcontrolcenter.app.transport.http.CertificatePinnerFactory
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
 * - OpenAI / XiaomiMiMo / LocalModel / OpenWebUI → [OpenAIHttpTransport]（HTTP + SSE）
 * - ComfyUI → [ComfyUITransport]（HTTP 工作流提交 + 轮询，图像生成）
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
        AgentType.LocalModel,
        AgentType.OpenWebUI -> OpenAIHttpTransport(
            context = context,
            // J4: 证书锁定动态启用——仅当 CertificatePinnerFactory 配置了真实
            // （非占位）pin 时才开启（[hasRealPins]）。OkHttp 的 CertificatePinner
            // 仅校验已登记 pin 的公网主机（如 api.openai.com），本地 LLM
            // （127.0.0.1 / 10.0.x / 192.168.x）与用户自定义服务器因未登记 pin 而
            // 不受影响，故构造时无需依据 serverUrl 判断（serverUrl 在 connect() 时
            // 才可知，[CertificatePinnerFactory.isPublicEndpoint] 供运行时决策参考）。
            // 占位 pin（含 "PLACEHOLDER"）期间 hasRealPins() 返回 false，自动降级为
            // 不锁定，确保不会因占位值导致连接失败。
            enableCertificatePinning = CertificatePinnerFactory.hasRealPins()
        )

        AgentType.ComfyUI -> ComfyUITransport()
    }
}
