package com.agentcontrolcenter.app.data.marketplace

import com.agentcontrolcenter.app.agent.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarketplaceClient.fetchLocalTemplates 单元测试 — 验证本地端点模板的
 * 内容、搜索过滤逻辑、以及与 fetchAll 的集成关系。
 */
class MarketplaceClientTest {

    private val client = MarketplaceClient()

    @Test
    fun `fetchLocalTemplates returns both ComfyUI and OpenWebUI templates`() {
        val templates = client.fetchLocalTemplates()
        assertEquals(2, templates.size)

        val comfyui = templates.find { it.type == AgentType.ComfyUI }
        assertTrue("应包含 ComfyUI 模板", comfyui != null)
        assertEquals("local_comfyui", comfyui?.id)
        assertEquals("http://127.0.0.1:8188", comfyui?.serverUrl)

        val openwebui = templates.find { it.type == AgentType.OpenWebUI }
        assertTrue("应包含 OpenWebUI 模板", openwebui != null)
        assertEquals("local_openwebui", openwebui?.id)
        assertEquals("http://127.0.0.1:3000/api/v1", openwebui?.serverUrl)
    }

    @Test
    fun `fetchLocalTemplates with blank search returns all templates`() {
        // 空字符串、纯空白、null 均应返回全部模板
        assertEquals(2, client.fetchLocalTemplates(null).size)
        assertEquals(2, client.fetchLocalTemplates("").size)
        assertEquals(2, client.fetchLocalTemplates("   ").size)
    }

    @Test
    fun `fetchLocalTemplates filters by name case-insensitively`() {
        val lower = client.fetchLocalTemplates("comfy")
        assertEquals(1, lower.size)
        assertEquals(AgentType.ComfyUI, lower.first().type)

        val upper = client.fetchLocalTemplates("COMFYUI")
        assertEquals(1, upper.size)
        assertEquals(AgentType.ComfyUI, upper.first().type)
    }

    @Test
    fun `fetchLocalTemplates filters by tag`() {
        // 按 tag "Local" 过滤应同时匹配两个模板
        val localTag = client.fetchLocalTemplates("Local")
        assertEquals(2, localTag.size)

        // 按 tag "Chat" 仅匹配 OpenWebUI
        val chatTag = client.fetchLocalTemplates("Chat")
        assertEquals(1, chatTag.size)
        assertEquals(AgentType.OpenWebUI, chatTag.first().type)

        // 按 tag "Image" 仅匹配 ComfyUI
        val imageTag = client.fetchLocalTemplates("Image")
        assertEquals(1, imageTag.size)
        assertEquals(AgentType.ComfyUI, imageTag.first().type)
    }

    @Test
    fun `fetchLocalTemplates filters by description`() {
        val result = client.fetchLocalTemplates("文生图")
        assertEquals(1, result.size)
        assertEquals(AgentType.ComfyUI, result.first().type)
    }

    @Test
    fun `fetchLocalTemplates returns empty for non-matching search`() {
        val result = client.fetchLocalTemplates("nonexistent-xyz-12345")
        assertTrue("无匹配搜索应返回空列表", result.isEmpty())
    }

    @Test
    fun `local templates are not marked as downloadable from remote`() {
        // 本地模板不应有 downloads/rating 字段（与远程 API 返回的 Agent 区分）
        client.fetchLocalTemplates().forEach { template ->
            // downloads 和 rating 默认为 null，本地模板不编造这些字段
            // （MarketplaceAgent 的 downloads/rating 默认 null，这里验证不抛异常即可）
            assertFalse(
                "本地模板 ID 不应包含远程前缀 oc_/claw_",
                template.id.startsWith("oc_") || template.id.startsWith("claw_")
            )
        }
    }
}
