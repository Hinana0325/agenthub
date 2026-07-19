package com.agenthub.app.data.model

import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.agent.model.AgentType
import org.junit.Assert.*
import org.junit.Test

/**
 * AgentConfig 数据模型测试。
 * 验证默认值和字段赋值的正确性。
 */
class AgentConfigTest {

    @Test
    fun `default values are correct`() {
        val config = AgentConfig()
        assertEquals("default", config.id)
        assertEquals("Default Agent", config.name)
        assertEquals(AgentType.Hermes, config.type)
        assertEquals("", config.serverUrl)
        assertEquals("", config.apiKey)
        assertEquals("", config.model)
        assertEquals("", config.systemPrompt)
        assertEquals(0.7f, config.temperature, 0.001f)
        assertEquals(4096, config.maxTokens)
    }

    @Test
    fun `custom values are stored correctly`() {
        val config = AgentConfig(
            id = "test-123",
            name = "My GPT",
            type = AgentType.OpenAI,
            serverUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            model = "gpt-4",
            systemPrompt = "You are helpful",
            temperature = 0.5f,
            maxTokens = 8192
        )
        assertEquals("test-123", config.id)
        assertEquals("My GPT", config.name)
        assertEquals(AgentType.OpenAI, config.type)
        assertEquals("https://api.openai.com/v1", config.serverUrl)
        assertEquals("sk-test", config.apiKey)
        assertEquals("gpt-4", config.model)
        assertEquals(0.5f, config.temperature, 0.001f)
        assertEquals(8192, config.maxTokens)
    }

    @Test
    fun `local model config works`() {
        val config = AgentConfig(
            id = "local",
            name = "Local Llama",
            type = AgentType.LocalModel,
            serverUrl = "http://127.0.0.1:11434",
            model = "llama3"
        )
        assertEquals(AgentType.LocalModel, config.type)
        assertEquals("http://127.0.0.1:11434", config.serverUrl)
    }
}
