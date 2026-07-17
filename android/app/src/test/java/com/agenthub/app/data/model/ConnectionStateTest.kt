package com.agenthub.app.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * ConnectionState 数据模型测试。
 */
class ConnectionStateTest {

    @Test
    fun `default connection state is disconnected`() {
        val state = ConnectionState()
        assertFalse(state.isConnected)
        assertEquals("", state.serverUrl)
        assertEquals(AgentType.Hermes, state.agentType)
        assertEquals(0L, state.latency)
        assertEquals("", state.modelName)
        assertEquals("", state.sessionToken)
        assertEquals(0L, state.totalTokens)
    }

    @Test
    fun `connected state stores values correctly`() {
        val state = ConnectionState(
            isConnected = true,
            serverUrl = "wss://agent.example.com/ws",
            agentType = AgentType.OpenClaw,
            latency = 42,
            modelName = "gpt-4-turbo",
            sessionToken = "tok_abc123",
            totalTokens = 15000
        )
        assertTrue(state.isConnected)
        assertEquals("wss://agent.example.com/ws", state.serverUrl)
        assertEquals(AgentType.OpenClaw, state.agentType)
        assertEquals(42L, state.latency)
        assertEquals("gpt-4-turbo", state.modelName)
        assertEquals(15000L, state.totalTokens)
    }
}
