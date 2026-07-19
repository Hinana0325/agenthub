package com.agenthub.app.agent.model

/**
 * Agent 连接状态 — 包含运行时元数据。
 *
 * 原位于 com.agenthub.app.data.model.ConnectionState。
 */
data class ConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0,
    val modelName: String = "",
    val sessionToken: String = "",
    val totalTokens: Long = 0
)
