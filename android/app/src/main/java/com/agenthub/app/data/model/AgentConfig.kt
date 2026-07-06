package com.agenthub.app.data.model

data class AgentConfig(
    val id: String = "default",
    val name: String = "Default Agent",
    val type: AgentType = AgentType.Hermes,
    val serverUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
)

enum class AgentType(val displayName: String) {
    Hermes("Hermes"),
    OpenCode("OpenCode"),
    OpenClaw("OpenClaw"),
    OpenAI("OpenAI Compatible"),
    XiaomiMiMo("Xiaomi MiMo"),
    LocalModel("Local Model")
}
