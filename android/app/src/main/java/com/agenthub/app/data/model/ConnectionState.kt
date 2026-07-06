package com.agenthub.app.data.model

data class ConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0,
    val modelName: String = "",
    val sessionToken: String = "",
    val totalTokens: Long = 0
)
