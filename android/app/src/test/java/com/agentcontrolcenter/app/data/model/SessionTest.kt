package com.agentcontrolcenter.app.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Session 数据模型测试。
 */
class SessionTest {

    @Test
    fun `session default values`() {
        val session = Session(id = "s1")
        assertEquals("s1", session.id)
        assertEquals("", session.title)
        assertFalse(session.isPinned)
        assertEquals(0, session.messageCount)
        assertEquals("", session.summary)
    }

    @Test
    fun `session with custom values`() {
        val session = Session(
            id = "s2",
            title = "Chat with GPT",
            createdAt = 1700000000000L,
            updatedAt = 1700001000000L,
            isPinned = true,
            messageCount = 25,
            summary = "Discussed project architecture"
        )
        assertEquals("s2", session.id)
        assertEquals("Chat with GPT", session.title)
        assertTrue(session.isPinned)
        assertEquals(25, session.messageCount)
        assertEquals("Discussed project architecture", session.summary)
    }
}
