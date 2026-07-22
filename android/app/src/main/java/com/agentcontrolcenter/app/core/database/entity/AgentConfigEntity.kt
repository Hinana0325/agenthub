package com.agentcontrolcenter.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_configs")
data class AgentConfigEntity(
    @PrimaryKey val id: String = "default",
    val name: String = "Default Agent",
    val type: String = "Hermes",
    val serverUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    // 跨端 schema 对齐：与 iOS AgentConfigEntity.protocolType 字段一致
    // 存储 AgentProtocol.rawValue（WebSocket / HttpSSE / MCP / Local），默认 WebSocket。
    // MIGRATION_8_9 添加，DEFAULT 'WebSocket'。
    val protocolType: String = "WebSocket"
)
