package com.agentcontrolcenter.app.transport.protocol

import com.agentcontrolcenter.app.agent.model.AgentType
import org.junit.Assert.*
import org.junit.Test

class AgentConnectionStateTest {
    @Test
    fun `default state is disconnected`() {
        val state = AgentConnectionState()
        assertFalse(state.isConnected)
        assertEquals("", state.serverUrl)
        assertEquals(AgentType.Hermes, state.agentType)
        assertEquals(0L, state.latency)
    }

    @Test
    fun `connected state stores values`() {
        val state = AgentConnectionState(
            isConnected = true,
            serverUrl = "wss://agent.example.com",
            agentType = AgentType.OpenClaw,
            latency = 42
        )
        assertTrue(state.isConnected)
        assertEquals("wss://agent.example.com", state.serverUrl)
        assertEquals(AgentType.OpenClaw, state.agentType)
        assertEquals(42L, state.latency)
    }
}
