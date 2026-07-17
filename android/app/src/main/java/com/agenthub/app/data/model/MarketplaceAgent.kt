package com.agenthub.app.data.model

data class MarketplaceAgent(
    val id: String,
    val name: String,
    val description: String,
    val type: AgentType,
    val serverUrl: String,
    val author: String,
    // 仅当真实 API 提供时才非 null；否则 UI 不展示，避免编造统计数据
    val downloads: Int? = null,
    val rating: Float? = null,
    val tags: List<String>
)
