package com.agentcontrolcenter.app.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentTypeUi 单元测试 — 覆盖图标映射、字段标签、apiKey 可选性、默认配置预填。
 *
 * 重点验证 withDefaults「不覆盖已填字段」语义，避免回归。
 */
class AgentTypeUiTest {

    // ── 图标映射 ──

    @Test
    fun `icon returns non-null for all agent types`() {
        // 图标映射必须覆盖全部 8 种 AgentType，否则 when 分支缺失会编译失败。
        // 这里不直接断言具体 ImageVector 实例（依赖 Compose 运行时），
        // 而是验证每种类型都有对应映射且不抛异常。
        AgentType.entries.forEach { type ->
            val icon = AgentTypeUi.icon(type)
            // ImageVector 是 object，非 null 即说明映射存在
            assertTrue("AgentType.$type 应有图标映射", icon !== null)
        }
    }

    // ── apiKey 可选性 ──

    @Test
    fun `apiKey optional for LocalModel and ComfyUI`() {
        assertTrue(AgentTypeUi.apiKeyOptional(AgentType.LocalModel))
        assertTrue(AgentTypeUi.apiKeyOptional(AgentType.ComfyUI))
    }

    @Test
    fun `apiKey required for remote types`() {
        // 远程服务都需要认证
        listOf(
            AgentType.Hermes,
            AgentType.OpenCode,
            AgentType.OpenClaw,
            AgentType.OpenAI,
            AgentType.XiaomiMiMo,
            AgentType.OpenWebUI
        ).forEach { type ->
            assertFalse("AgentType.$type 远程服务 apiKey 应必填", AgentTypeUi.apiKeyOptional(type))
        }
    }

    // ── serverUrlRequired ──

    @Test
    fun `serverUrl not required for LocalModel`() {
        assertFalse(AgentTypeUi.serverUrlRequired(AgentType.LocalModel))
    }

    @Test
    fun `serverUrl required for all remote types`() {
        listOf(
            AgentType.Hermes,
            AgentType.OpenAI,
            AgentType.ComfyUI,
            AgentType.OpenWebUI
        ).forEach { type ->
            assertTrue("AgentType.$type 远程服务应需要 serverUrl", AgentTypeUi.serverUrlRequired(type))
        }
    }

    // ── 字段标签 ──

    @Test
    fun `ComfyUI uses image-generation field labels`() {
        assertEquals("Checkpoint 文件名", AgentTypeUi.modelLabel(AgentType.ComfyUI))
        assertEquals("CFG Scale", AgentTypeUi.temperatureLabel(AgentType.ComfyUI))
        assertEquals("Steps（采样步数）", AgentTypeUi.maxTokensLabel(AgentType.ComfyUI))
        assertEquals("Negative Prompt（负向提示词）", AgentTypeUi.systemPromptLabel(AgentType.ComfyUI))
    }

    @Test
    fun `non-ComfyUI types use generic field labels`() {
        // 随机选一个非 ComfyUI 类型验证通用标签
        listOf(AgentType.Hermes, AgentType.OpenAI, AgentType.OpenWebUI, AgentType.LocalModel).forEach { type ->
            assertEquals("模型", AgentTypeUi.modelLabel(type))
            assertEquals("Temperature", AgentTypeUi.temperatureLabel(type))
            assertEquals("Max Tokens", AgentTypeUi.maxTokensLabel(type))
            assertEquals("System Prompt", AgentTypeUi.systemPromptLabel(type))
        }
    }

    // ── 占位提示 ──

    @Test
    fun `serverUrl placeholders point to correct endpoints`() {
        assertEquals("http://127.0.0.1:8188", AgentTypeUi.serverUrlPlaceholder(AgentType.ComfyUI))
        assertEquals("http://127.0.0.1:3000/api/v1", AgentTypeUi.serverUrlPlaceholder(AgentType.OpenWebUI))
        assertEquals("http://127.0.0.1:11434", AgentTypeUi.serverUrlPlaceholder(AgentType.LocalModel))
        assertEquals("https://api.openai.com/v1", AgentTypeUi.serverUrlPlaceholder(AgentType.OpenAI))
    }

    @Test
    fun `model placeholders are non-blank for all types`() {
        AgentType.entries.forEach { type ->
            assertTrue(
                "AgentType.$type model 占位提示不应为空",
                AgentTypeUi.modelPlaceholder(type).isNotBlank()
            )
        }
    }

    // ── withDefaults 预填 ──

    @Test
    fun `withDefaults prefills ComfyUI defaults when fields are blank`() {
        val blank = AgentConfig(type = AgentType.ComfyUI)
        val prefilled = AgentTypeUi.withDefaults(blank)

        assertEquals("http://127.0.0.1:8188", prefilled.serverUrl)
        assertEquals("v1-5-pruned-emaonly.safetensors", prefilled.model)
        assertEquals(7.0f, prefilled.temperature)
        assertEquals(20, prefilled.maxTokens)
        assertEquals("bad quality, blurry", prefilled.systemPrompt)
    }

    @Test
    fun `withDefaults does not overwrite user-provided ComfyUI fields`() {
        val userFilled = AgentConfig(
            type = AgentType.ComfyUI,
            serverUrl = "http://192.168.1.100:8188",
            model = "custom-model.safetensors",
            temperature = 12.0f,
            maxTokens = 30,
            systemPrompt = "lowres, bad anatomy"
        )
        val result = AgentTypeUi.withDefaults(userFilled)

        // 用户已填的字段应原样保留
        assertEquals("http://192.168.1.100:8188", result.serverUrl)
        assertEquals("custom-model.safetensors", result.model)
        assertEquals(12.0f, result.temperature)
        assertEquals(30, result.maxTokens)
        assertEquals("lowres, bad anatomy", result.systemPrompt)
    }

    @Test
    fun `withDefaults prefills OpenWebUI defaults when blank`() {
        val blank = AgentConfig(type = AgentType.OpenWebUI)
        val prefilled = AgentTypeUi.withDefaults(blank)

        assertEquals("http://127.0.0.1:3000/api/v1", prefilled.serverUrl)
        assertEquals("llama3", prefilled.model)
        // OpenWebUI 不预填 temperature/maxTokens/systemPrompt
        assertEquals(0.7f, prefilled.temperature)
        assertEquals(4096, prefilled.maxTokens)
    }

    @Test
    fun `withDefaults prefills LocalModel defaults when blank`() {
        val blank = AgentConfig(type = AgentType.LocalModel)
        val prefilled = AgentTypeUi.withDefaults(blank)

        assertEquals("http://127.0.0.1:11434", prefilled.serverUrl)
        assertEquals("llama3", prefilled.model)
    }

    @Test
    fun `withDefaults prefills OpenAI defaults when blank`() {
        val blank = AgentConfig(type = AgentType.OpenAI)
        val prefilled = AgentTypeUi.withDefaults(blank)

        assertEquals("https://api.openai.com/v1", prefilled.serverUrl)
        assertEquals("gpt-4o", prefilled.model)
    }

    @Test
    fun `withDefaults returns unchanged config for WebSocket types`() {
        // Hermes/OpenClaw/OpenCode 没有 withDefaults 预填逻辑，应原样返回
        val original = AgentConfig(
            type = AgentType.Hermes,
            serverUrl = "wss://custom.example.com/ws",
            model = "custom-model"
        )
        val result = AgentTypeUi.withDefaults(original)
        assertEquals(original, result)
    }

    @Test
    fun `withDefaults changes config when defaults applied`() {
        // 验证 withDefaults 确实会改变空配置（而非原样返回）
        val blank = AgentConfig(type = AgentType.ComfyUI)
        val prefilled = AgentTypeUi.withDefaults(blank)
        assertNotEquals(blank, prefilled)
    }
}
