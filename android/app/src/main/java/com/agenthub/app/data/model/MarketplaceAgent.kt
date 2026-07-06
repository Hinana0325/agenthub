package com.agenthub.app.data.model

data class MarketplaceAgent(
    val id: String,
    val name: String,
    val description: String,
    val type: AgentType,
    val serverUrl: String,
    val author: String,
    val downloads: Int,
    val rating: Float,
    val tags: List<String>
)
