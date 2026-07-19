package com.agenthub.app.transport

import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.transport.http.OpenAIHttpTransport
import com.agenthub.app.transport.websocket.WebSocketTransport
import org.junit.Assert.*
import org.junit.Test

/**
 * TransportFactory 单元测试。
 * 验证按 AgentType 路由到正确的 Transport 实现。
 */
class TransportFactoryTest {

    @Test
    fun `Hermes creates WebSocketTransport`() {
        val transport = TransportFactory.create(AgentType.Hermes)
        assertTrue(transport is WebSocketTransport)
    }

    @Test
    fun `OpenClaw creates WebSocketTransport`() {
        val transport = TransportFactory.create(AgentType.OpenClaw)
        assertTrue(transport is WebSocketTransport)
    }

    @Test
    fun `OpenCode creates WebSocketTransport`() {
        val transport = TransportFactory.create(AgentType.OpenCode)
        assertTrue(transport is WebSocketTransport)
    }

    @Test
    fun `OpenAI creates OpenAIHttpTransport`() {
        val transport = TransportFactory.create(AgentType.OpenAI)
        assertTrue(transport is OpenAIHttpTransport)
    }

    @Test
    fun `XiaomiMiMo creates OpenAIHttpTransport`() {
        val transport = TransportFactory.create(AgentType.XiaomiMiMo)
        assertTrue(transport is OpenAIHttpTransport)
    }

    @Test
    fun `LocalModel creates OpenAIHttpTransport`() {
        val transport = TransportFactory.create(AgentType.LocalModel)
        assertTrue(transport is OpenAIHttpTransport)
    }

    @Test
    fun `all AgentTypes produce non-null transport`() {
        AgentType.entries.forEach { type ->
            val transport = TransportFactory.create(type)
            assertNotNull("Transport for $type should not be null", transport)
        }
    }

    @Test
    fun `each call creates new instance`() {
        val t1 = TransportFactory.create(AgentType.Hermes)
        val t2 = TransportFactory.create(AgentType.Hermes)
        assertNotSame("Each create() should return a new instance", t1, t2)
    }

    @Test
    fun `WebSocket types and HTTP types are distinct`() {
        val wsTypes = listOf(AgentType.Hermes, AgentType.OpenClaw, AgentType.OpenCode)
        val httpTypes = listOf(AgentType.OpenAI, AgentType.XiaomiMiMo, AgentType.LocalModel)

        wsTypes.forEach { type ->
            assertTrue("$type should be WebSocketTransport", TransportFactory.create(type) is WebSocketTransport)
        }
        httpTypes.forEach { type ->
            assertTrue("$type should be OpenAIHttpTransport", TransportFactory.create(type) is OpenAIHttpTransport)
        }
    }
}
