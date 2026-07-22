package com.agentcontrolcenter.app.agent.model

/**
 * Agent 通信协议类型。
 *
 * 与 iOS `AgentProtocol` 完全对齐：rawValue 字符串相同（"WebSocket" /
 * "HttpSSE" / "MCP" / "Local"），跨端持久化兼容。
 *
 * 用于 [TransportFactory] 路由：决定走 WebSocket / OpenAI HTTP-SSE / MCP / 本地进程。
 * 旧实现按 [AgentType] 路由，过渡期两者并存——新逻辑优先按 [protocolType] 路由，
 * 未设置时按 [AgentType] 兼容回退。
 *
 * rawValue 字符串与 iOS `AgentProtocol` 一致，保证跨端 schema 兼容
 * （iOS `AgentConfigEntity.protocolType` 默认 "webSocket" → 本枚举 `WebSocket.rawValue`）。
 */
enum class AgentProtocol(val rawValue: String, val displayName: String) {
    WebSocket("WebSocket", "WebSocket"),
    HttpSSE("HttpSSE", "HTTP SSE"),
    MCP("MCP", "MCP"),
    Local("Local", "Local");

    companion object {
        /**
         * 从存储的 rawValue 字符串解析，未匹配时回退到 [WebSocket]。
         *
         * 注意：iOS 端在 init 中默认 "webSocket"（小写），与枚举 rawValue 不一致，
         * 是历史遗留 typo。两端都用此函数解析时，会统一回退到 [WebSocket]，
         * 不影响新写入的数据。
         */
        fun fromRawValue(value: String?): AgentProtocol {
            if (value.isNullOrBlank()) return WebSocket
            return entries.firstOrNull { it.rawValue == value } ?: WebSocket
        }
    }
}
