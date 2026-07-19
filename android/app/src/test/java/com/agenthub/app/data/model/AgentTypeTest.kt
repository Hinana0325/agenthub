package com.agenthub.app.data.model

import com.agenthub.app.agent.model.AgentType
import org.junit.Assert.*
import org.junit.Test

/**
 * AgentType 枚举测试。
 * 验证所有代理类型的 displayName 正确性及枚举解析的鲁棒性。
 */
class AgentTypeTest {

    @Test
    fun `all agent types have non-empty display names`() {
        AgentType.entries.forEach { type ->
            assertTrue("AgentType.${type.name} should have a non-empty displayName",
                type.displayName.isNotBlank())
        }
    }

    @Test
    fun `valueOf parses all known types`() {
        assertEquals(AgentType.Hermes, AgentType.valueOf("Hermes"))
        assertEquals(AgentType.OpenCode, AgentType.valueOf("OpenCode"))
        assertEquals(AgentType.OpenClaw, AgentType.valueOf("OpenClaw"))
        assertEquals(AgentType.OpenAI, AgentType.valueOf("OpenAI"))
        assertEquals(AgentType.XiaomiMiMo, AgentType.valueOf("XiaomiMiMo"))
        assertEquals(AgentType.LocalModel, AgentType.valueOf("LocalModel"))
    }

    @Test
    fun `valueOf throws for unknown type`() {
        try {
            AgentType.valueOf("NonExistent")
            fail("Should have thrown IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `display names match expected values`() {
        assertEquals("Hermes", AgentType.Hermes.displayName)
        assertEquals("OpenCode", AgentType.OpenCode.displayName)
        assertEquals("OpenClaw", AgentType.OpenClaw.displayName)
        assertEquals("OpenAI Compatible", AgentType.OpenAI.displayName)
        assertEquals("Xiaomi MiMo", AgentType.XiaomiMiMo.displayName)
        assertEquals("Local Model", AgentType.LocalModel.displayName)
    }
}
