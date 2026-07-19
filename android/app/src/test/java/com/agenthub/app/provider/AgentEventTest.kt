package com.agenthub.app.transport.protocol

import com.agenthub.app.agent.model.AgentType
import org.junit.Assert.*
import org.junit.Test

class AgentEventTest {
    @Test
    fun `Connected event stores data`() {
        val event = AgentEvent.Connected("wss://test.com", AgentType.Hermes)
        assertEquals("wss://test.com", event.serverUrl)
        assertEquals(AgentType.Hermes, event.agentType)
    }

    @Test
    fun `Disconnected event has optional reason`() {
        val event1 = AgentEvent.Disconnected()
        assertEquals("", event1.reason)
        val event2 = AgentEvent.Disconnected("timeout")
        assertEquals("timeout", event2.reason)
    }

    @Test
    fun `MessageReceived delta flag`() {
        val full = AgentEvent.MessageReceived("hello", isDelta = false)
        val delta = AgentEvent.MessageReceived(" world", isDelta = true)
        assertFalse(full.isDelta)
        assertTrue(delta.isDelta)
        assertEquals("hello", full.content)
        assertEquals(" world", delta.content)
    }

    @Test
    fun `Error event stores message`() {
        val event = AgentEvent.Error("connection failed")
        assertEquals("connection failed", event.message)
    }
}
